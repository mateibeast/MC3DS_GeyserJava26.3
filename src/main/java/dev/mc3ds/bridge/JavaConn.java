package dev.mc3ds.bridge;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A Java Edition 1.12.2 (protocol 340) client connection. ViaVersion/ViaBackwards on the
 * server translate it to whatever version the server runs.
 */
public final class JavaConn {
    public static final int PROTOCOL = 340;

    public interface Handler {
        void onJavaLoginSuccess(String uuid, String name);
        void onJavaPacket(int id, Buf in) throws IOException;
        void onJavaDisconnect(String reason);
    }

    private final Socket socket = new Socket();
    private DataInputStream in;
    private OutputStream out;
    private final Handler handler;
    private int threshold = -1;
    private boolean play;
    private volatile boolean closed;
    private final Deflater deflater = new Deflater();
    private final Inflater inflater = new Inflater();
    // reader -> processor queue, and writer queue: socket IO never waits on the 3DS session
    private final java.util.concurrent.LinkedBlockingQueue<Buf> inbox = new java.util.concurrent.LinkedBlockingQueue<>();
    private final java.util.concurrent.LinkedBlockingQueue<byte[]> outbox = new java.util.concurrent.LinkedBlockingQueue<>();
    public volatile long lastRead = System.currentTimeMillis(), lastProcessed = System.currentTimeMillis();
    public volatile Thread readerThread, processorThread, writerThread;
    public volatile int lastPacketId = -1;
    public long packetsRead;

    public JavaConn(Handler handler) { this.handler = handler; }

    public void connect(String host, int port, String handshakeHost, String username) {
        Thread t = new Thread(() -> {
            String reason = "Connection closed";
            try {
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(host, port), 10000);
                in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                out = new BufferedOutputStream(socket.getOutputStream());
                send(new Buf().jvarint(0x00).jvarint(PROTOCOL).str(handshakeHost).beShort(port).jvarint(2));
                send(new Buf().jvarint(0x00).str(username));
                while (!closed) {
                    Buf p = readPacket();
                    lastRead = System.currentTimeMillis();
                    packetsRead++;
                    int id = p.jvarint();
                    if (!play) {
                        switch (id) {
                            case 0x00: reason = Chat.toText(p.str()); closed = true; break;
                            case 0x01:
                                reason = "The Java server is in online mode and asked for a Mojang login. " +
                                        "Put Floodgate's key.pem next to the bridge jar (floodgate-key in bridge.properties).";
                                closed = true; break;
                            case 0x02: {
                                String uuid = p.str(); String name = p.str();
                                play = true;
                                startProcessor();
                                handler.onJavaLoginSuccess(uuid, name);
                                break;
                            }
                            case 0x03: threshold = p.jvarint(); break;
                            case 0x04: { // login plugin request (1.13+, shouldn't happen) -> not understood
                                int msgId = p.jvarint();
                                send(new Buf().jvarint(0x02).jvarint(msgId).bool(false));
                                break;
                            }
                            default: Log.debug("Unknown login packet 0x" + Integer.toHexString(id));
                        }
                    } else {
                        p.pos = 0;
                        inbox.add(p);
                    }
                }
            } catch (EOFException e) {
                // closed by server
            } catch (Throwable e) {
                if (!closed) { reason = "Lost connection to Java server: " + e.getMessage(); Log.err("Java connection error", e); }
            } finally {
                closed = true;
                try { socket.close(); } catch (IOException ignored) { }
                inbox.add(new Buf(0)); // wake the processor
                final String r = reason;
                // let the processor finish queued packets (e.g. a kick message) before reporting
                Thread pt = processorThread;
                if (pt != null) { try { pt.join(2000); } catch (InterruptedException ignored) { } }
                handler.onJavaDisconnect(r);
            }
        }, "java-read-" + username);
        t.setDaemon(true);
        readerThread = t;
        Thread w = new Thread(() -> {
            try {
                while (!closed || !outbox.isEmpty()) {
                    byte[] f = outbox.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (f == null || out == null) continue;
                    out.write(f);
                    if (outbox.isEmpty()) out.flush();
                }
            } catch (Exception e) {
                Log.debug("Java writer ended: " + e);
                close();
            }
        }, "java-write-" + username);
        w.setDaemon(true);
        writerThread = w;
        t.start();
    }

    private void startProcessor() {
        Thread pr = new Thread(() -> {
            while (true) {
                Buf p;
                try { p = inbox.take(); } catch (InterruptedException e) { return; }
                if (p.len == 0) { if (closed && inbox.isEmpty()) return; continue; }
                int id = -1;
                try {
                    id = p.jvarint();
                    lastPacketId = id;
                    handler.onJavaPacket(id, p);
                } catch (Throwable e) {
                    Log.err("Error translating Java packet 0x" + Integer.toHexString(id), e);
                }
                lastProcessed = System.currentTimeMillis();
            }
        }, "java-process");
        pr.setDaemon(true);
        processorThread = pr;
        pr.start();
    }

    public int queued() { return inbox.size(); }

    private Buf readPacket() throws IOException {
        int length = readVarInt();
        if (length <= 0 || length > 8 * 1024 * 1024) throw new IOException("Bad packet length " + length);
        byte[] data = new byte[length];
        in.readFully(data);
        if (threshold < 0) return new Buf(data);
        Buf b = new Buf(data);
        int dataLen = b.jvarint();
        if (dataLen == 0) return new Buf(b.rest());
        if (dataLen > 32 * 1024 * 1024) throw new IOException("Packet too large " + dataLen);
        byte[] res = new byte[dataLen];
        inflater.reset();
        inflater.setInput(data, b.pos, data.length - b.pos);
        try {
            int n = 0;
            while (n < dataLen) {
                int r = inflater.inflate(res, n, dataLen - n);
                if (r == 0 && (inflater.finished() || inflater.needsInput())) break;
                n += r;
            }
        } catch (DataFormatException e) { throw new IOException(e); }
        return new Buf(res);
    }

    private int readVarInt() throws IOException {
        int v = 0, shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
            if (shift > 35) throw new IOException("VarInt too big");
        }
    }

    public synchronized void send(Buf packet) {
        if (closed) return;
        try {
            byte[] body = packet.toArray();
            Buf frame = new Buf(body.length + 8);
            if (threshold < 0) {
                frame.jvarint(body.length).put(body);
            } else if (body.length < threshold) {
                frame.jvarint(body.length + 1).put(0).put(body);
            } else {
                deflater.reset();
                deflater.setInput(body);
                deflater.finish();
                Buf comp = new Buf(body.length);
                byte[] tmp = new byte[8192];
                while (!deflater.finished()) { int n = deflater.deflate(tmp); comp.put(tmp, 0, n); }
                Buf inner = new Buf().jvarint(body.length).put(comp.data, 0, comp.len);
                frame.jvarint(inner.len).put(inner.data, 0, inner.len);
            }
            if (!play) { // during login write directly (writer thread not needed yet)
                out.write(frame.data, 0, frame.len);
                out.flush();
            } else {
                if (writerThread != null && writerThread.getState() == Thread.State.NEW) writerThread.start();
                outbox.add(java.util.Arrays.copyOf(frame.data, frame.len));
            }
        } catch (IOException e) {
            Log.debug("Java send failed: " + e);
            close();
        }
    }

    public boolean isPlay() { return play; }
    public boolean isClosed() { return closed; }

    public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
    }
}
