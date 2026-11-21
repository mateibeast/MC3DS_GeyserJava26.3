package dev.mc3ds.bridge;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal RakNet server (protocol 8, as used by Minecraft PE 1.1 / protocol 113).
 * Implements unconnected ping, the open-connection handshake, reliable/ordered frames,
 * split packets, ACK/NACK and resends. Everything Minecraft-specific is handed to a
 * {@link Listener}.
 */
public final class RakNet {

    public interface Listener {
        /** Called when a client finished the RakNet handshake. */
        void onConnect(Session s);
        /** Called with the payload of a 0xFE game packet (without the 0xFE byte). */
        void onGamePacket(Session s, byte[] payload);
        void onDisconnect(Session s, String reason);
        /** MOTD shown in the server list. */
        String motd();
    }

    static final byte[] MAGIC = {0x00, (byte) 0xff, (byte) 0xff, 0x00, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, 0x12, 0x34, 0x56, 0x78};

    final DatagramSocket socket;
    final Listener listener;
    final long guid = new Random().nextLong();
    final Map<SocketAddress, Session> sessions = new ConcurrentHashMap<>();
    volatile boolean running = true;

    public RakNet(InetSocketAddress bind, Listener listener) throws IOException {
        this.socket = new DatagramSocket(null);
        this.socket.setReuseAddress(true);
        this.socket.setReceiveBufferSize(1 << 20);
        this.socket.setSendBufferSize(1 << 20);
        this.socket.bind(bind);
        this.listener = listener;
    }

    public void stop() {
        running = false;
        for (Session s : sessions.values()) s.close("bridge stopped", true);
        socket.close();
    }

    public void start() {
        Thread rx = new Thread(this::receiveLoop, "raknet-rx");
        rx.setDaemon(false);
        rx.start();
        Thread tick = new Thread(() -> {
            while (running) {
                long now = System.currentTimeMillis();
                for (Session s : sessions.values()) {
                    try { s.tick(now); } catch (Throwable t) { Log.err("RakNet tick error for " + s.address, t); }
                }
                try { Thread.sleep(10); } catch (InterruptedException ignored) { }
            }
        }, "raknet-tick");
        tick.setDaemon(true);
        tick.start();
    }

    private void receiveLoop() {
        byte[] buf = new byte[2048];
        while (running) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
                byte[] d = Arrays.copyOf(p.getData(), p.getLength());
                handle((InetSocketAddress) p.getSocketAddress(), d);
            } catch (Throwable t) {
                if (running) Log.err("RakNet receive error", t);
            }
        }
    }

    void send(SocketAddress to, byte[] d) {
        try { socket.send(new DatagramPacket(d, d.length, to)); } catch (IOException e) { Log.debug("send failed: " + e); }
    }

    private void handle(InetSocketAddress from, byte[] d) throws IOException {
        if (d.length == 0) return;
        int id = d[0] & 0xFF;
        Session s = sessions.get(from);
        if (s != null && (id & 0x80) != 0) { s.receiveDatagram(d); s.dispatch(); return; }

        Buf in = new Buf(d);
        in.pos = 1;
        switch (id) {
            case 0x01: case 0x02: { // unconnected ping
                long time = in.beLong();
                Buf out = new Buf().put(0x1C).beLong(time).beLong(guid).put(MAGIC);
                byte[] motd = listener.motd().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.beShort(motd.length).put(motd);
                send(from, out.toArray());
                break;
            }
            case 0x05: { // open connection request 1
                in.bytes(16);
                int proto = in.u8();
                int mtu = d.length + 28;
                Log.info("RakNet: connection attempt from " + from + " (raknet protocol " + proto + ", mtu " + mtu + ")");
                if (proto != 8) Log.info("RakNet: note — client uses RakNet protocol " + proto + " (PE 1.1 normally uses 8). Trying anyway.");
                Buf out = new Buf().put(0x06).put(MAGIC).beLong(guid).put(0).beShort(Math.min(mtu, 1492));
                send(from, out.toArray());
                break;
            }
            case 0x07: { // open connection request 2
                in.bytes(16);
                readAddress(in);
                int mtu = in.beShort();
                long clientGuid = in.beLong();
                mtu = Math.max(576, Math.min(mtu, 1492));
                Session old = sessions.remove(from);
                if (old != null) old.close("reconnect", false);
                Session ns = new Session(from, mtu, clientGuid);
                sessions.put(from, ns);
                Buf out = new Buf().put(0x08).put(MAGIC).beLong(guid);
                writeAddress(out, from);
                out.beShort(mtu).put(0);
                send(from, out.toArray());
                break;
            }
            default:
                Log.debug("Unknown unconnected packet 0x" + Integer.toHexString(id) + " from " + from);
        }
    }

    static InetSocketAddress readAddress(Buf in) throws IOException {
        int ver = in.u8();
        if (ver == 4) {
            byte[] a = in.bytes(4);
            for (int i = 0; i < 4; i++) a[i] = (byte) ~a[i];
            int port = in.beShort();
            return new InetSocketAddress(InetAddress.getByAddress(a), port);
        } else {
            in.leShort();              // family
            int port = in.beShort();
            in.beInt();                // flow info
            byte[] a = in.bytes(16);
            in.beInt();                // scope
            return new InetSocketAddress(InetAddress.getByAddress(a), port);
        }
    }

    static void writeAddress(Buf out, InetSocketAddress a) {
        InetAddress ip = a.getAddress();
        if (ip instanceof Inet4Address || ip == null) {
            byte[] b = ip == null ? new byte[]{127, 0, 0, 1} : ip.getAddress();
            out.put(4);
            for (byte x : b) out.put(~x & 0xFF);
            out.beShort(a.getPort());
        } else {
            out.put(6).leShort(23).beShort(a.getPort()).beInt(0).put(ip.getAddress()).beInt(0);
        }
    }

    // =====================================================================================
    public final class Session {
        public final InetSocketAddress address;
        final int mtu;
        final long clientGuid;
        public Object attachment;
        boolean connected, closed;
        long lastReceive = System.currentTimeMillis();

        // ---- receive state
        final TreeSet<Integer> ackQueue = new TreeSet<>();
        final TreeSet<Integer> nackQueue = new TreeSet<>();
        int highestSeq = -1;
        final Set<Integer> seenReliable = new HashSet<>();
        int reliableWindowStart = 0;
        final int[] orderExpected = new int[32];
        final List<Map<Integer, byte[]>> orderQueues = new ArrayList<>();
        final int[] seqHighest = new int[32];
        final Map<Integer, byte[][]> splits = new HashMap<>();

        // ---- send state
        int sendSeq = 0, msgIndex = 0, orderIndex = 0, splitId = 0;
        final ArrayDeque<byte[]> outFrames = new ArrayDeque<>();
        final Map<Integer, Sent> recovery = new LinkedHashMap<>();
        // events produced while holding the session lock, dispatched to the listener afterwards
        private final ArrayDeque<Object[]> inbox = new ArrayDeque<>();
        private boolean pendingConnect, pendingDisconnect; private String disconnectReason;

        /** Deliver queued events to the listener without holding the session lock (avoids deadlocks). */
        void dispatch() {
            while (true) {
                Object[] ev;
                boolean conn, disc; String why;
                synchronized (this) {
                    conn = pendingConnect; pendingConnect = false;
                    ev = conn ? null : inbox.poll();
                    disc = !conn && ev == null && pendingDisconnect; if (disc) pendingDisconnect = false;
                    why = disconnectReason;
                }
                if (conn) { listener.onConnect(this); continue; }
                if (ev != null) { listener.onGamePacket(this, (byte[]) ev[0]); continue; }
                if (disc) listener.onDisconnect(this, why);
                return;
            }
        }

        final class Sent { final List<byte[]> frames; long time; Sent(List<byte[]> f, long t) { frames = f; time = t; } }

        Session(InetSocketAddress address, int mtu, long clientGuid) {
            this.address = address; this.mtu = mtu; this.clientGuid = clientGuid;
            for (int i = 0; i < 32; i++) { orderQueues.add(new HashMap<>()); seqHighest[i] = -1; }
        }

        // ------------------------------------------------------------------ receive
        synchronized void receiveDatagram(byte[] d) throws IOException {
            lastReceive = System.currentTimeMillis();
            int id = d[0] & 0xFF;
            Buf in = new Buf(d); in.pos = 1;
            if ((id & 0x40) != 0) { // ACK
                for (int seq : readAckRecords(in)) recovery.remove(seq);
                return;
            }
            if ((id & 0x20) != 0) { // NACK
                for (int seq : readAckRecords(in)) {
                    Sent s = recovery.remove(seq);
                    if (s != null) sendDatagram(s.frames);
                }
                return;
            }
            int seq = in.leTriad();
            ackQueue.add(seq);
            nackQueue.remove(seq);
            if (seq > highestSeq) {
                for (int i = highestSeq + 1; i < seq && seq - i < 64; i++) nackQueue.add(i);
                highestSeq = seq;
            }
            while (!in.eof()) readFrame(in);
        }

        private List<Integer> readAckRecords(Buf in) throws IOException {
            List<Integer> r = new ArrayList<>();
            int count = in.beShort();
            for (int i = 0; i < count && !in.eof(); i++) {
                boolean single = in.bool();
                int a = in.leTriad();
                int b = single ? a : in.leTriad();
                for (int x = a; x <= b && r.size() < 4096; x++) r.add(x);
            }
            return r;
        }

        private void readFrame(Buf in) throws IOException {
            int flags = in.u8();
            int rel = flags >> 5;
            boolean split = (flags & 0x10) != 0;
            int bits = in.beShort();
            int length = (bits + 7) / 8;
            int mIndex = -1, oIndex = 0, oChannel = 0, sIndex = -1;
            if (rel == 2 || rel == 3 || rel == 4 || rel == 6 || rel == 7) mIndex = in.leTriad();
            if (rel == 1 || rel == 4) sIndex = in.leTriad();
            if (rel == 1 || rel == 3 || rel == 4 || rel == 7) { oIndex = in.leTriad(); oChannel = in.u8() & 31; }
            int sCount = 0, sId = 0, sIdx = 0;
            if (split) { sCount = in.beInt(); sId = in.beShort(); sIdx = in.beInt(); }
            byte[] payload = in.bytes(length);

            if (mIndex >= 0) {
                if (mIndex < reliableWindowStart || !seenReliable.add(mIndex)) return; // duplicate
                if (seenReliable.size() > 4096) {
                    reliableWindowStart = mIndex - 2048;
                    seenReliable.removeIf(x -> x < reliableWindowStart);
                }
            }
            if (split) {
                if (sCount <= 0 || sCount > 4096 || sIdx < 0 || sIdx >= sCount) return;
                final int fCount = sCount;
                byte[][] parts = splits.computeIfAbsent(sId, k -> new byte[fCount][]);
                if (parts.length != sCount) return;
                parts[sIdx] = payload;
                for (byte[] p : parts) if (p == null) return;
                splits.remove(sId);
                payload = Buf.concat(parts);
            }
            if (rel == 1 || rel == 4) { // sequenced
                if (sIndex < seqHighest[oChannel]) return;
                seqHighest[oChannel] = sIndex;
                deliver(payload);
            } else if (rel == 3 || rel == 7) { // ordered
                Map<Integer, byte[]> q = orderQueues.get(oChannel);
                if (oIndex < orderExpected[oChannel]) return;
                q.put(oIndex, payload);
                byte[] next;
                while ((next = q.remove(orderExpected[oChannel])) != null) {
                    orderExpected[oChannel]++;
                    deliver(next);
                }
            } else {
                deliver(payload);
            }
        }

        private void deliver(byte[] p) throws IOException {
            if (p.length == 0) return;
            int id = p[0] & 0xFF;
            Buf in = new Buf(p); in.pos = 1;
            switch (id) {
                case 0x00: { // connected ping
                    long t = in.beLong();
                    sendRaw(new Buf().put(0x03).beLong(t).beLong(System.currentTimeMillis()).toArray(), 0);
                    break;
                }
                case 0x03: break; // pong
                case 0x09: { // connection request
                    in.beLong();
                    long time = in.beLong();
                    Buf out = new Buf().put(0x10);
                    writeAddress(out, address);
                    out.beShort(0);
                    InetSocketAddress dummy = new InetSocketAddress(InetAddress.getByAddress(new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255}), 19132);
                    for (int i = 0; i < 10; i++) writeAddress(out, dummy);
                    out.beLong(time).beLong(System.currentTimeMillis());
                    sendRaw(out.toArray(), 2);
                    break;
                }
                case 0x13: // new incoming connection
                    if (!connected) { connected = true; pendingConnect = true; }
                    break;
                case 0x15: // disconnect notification
                    close("client disconnected", false);
                    break;
                case 0xFE:
                    if (!connected) { connected = true; pendingConnect = true; }
                    inbox.add(new Object[]{Arrays.copyOfRange(p, 1, p.length)});
                    break;
                default:
                    Log.debug("Unhandled RakNet message 0x" + Integer.toHexString(id));
            }
        }

        // ------------------------------------------------------------------ send
        /** Queue a game packet (already batched/compressed, without 0xFE). */
        public synchronized void sendGame(byte[] batch) {
            if (closed) return;
            byte[] p = new byte[batch.length + 1];
            p[0] = (byte) 0xFE;
            System.arraycopy(batch, 0, p, 1, batch.length);
            sendRaw(p, 3);
        }

        /** reliability: 0 unreliable, 2 reliable, 3 reliable ordered. */
        synchronized void sendRaw(byte[] payload, int rel) {
            int maxPayload = mtu - 28 - 4 - 30;
            if (payload.length > maxPayload) {
                int count = (payload.length + maxPayload - 1) / maxPayload;
                int sid = (splitId++) & 0xFFFF;
                int oi = rel == 3 ? orderIndex++ : 0;
                for (int i = 0; i < count; i++) {
                    int off = i * maxPayload;
                    int n = Math.min(maxPayload, payload.length - off);
                    outFrames.add(encodeFrame(Arrays.copyOfRange(payload, off, off + n), rel == 0 ? 2 : rel, oi, true, count, sid, i));
                }
            } else {
                int oi = rel == 3 ? orderIndex++ : 0;
                outFrames.add(encodeFrame(payload, rel, oi, false, 0, 0, 0));
            }
        }

        private byte[] encodeFrame(byte[] payload, int rel, int oi, boolean split, int sCount, int sId, int sIdx) {
            Buf f = new Buf(payload.length + 24);
            f.put((rel << 5) | (split ? 0x10 : 0));
            f.beShort(payload.length * 8);
            if (rel == 2 || rel == 3 || rel == 4 || rel == 6 || rel == 7) f.leTriad(msgIndex++);
            if (rel == 1 || rel == 3 || rel == 4 || rel == 7) { f.leTriad(oi); f.put(0); }
            if (split) { f.beInt(sCount); f.beShort(sId); f.beInt(sIdx); }
            f.put(payload);
            return f.toArray();
        }

        private void sendDatagram(List<byte[]> frames) {
            int seq = sendSeq++ & 0xFFFFFF;
            Buf d = new Buf(mtu).put(0x84).leTriad(seq);
            for (byte[] f : frames) d.put(f);
            recovery.put(seq, new Sent(frames, System.currentTimeMillis()));
            send(address, d.toArray());
        }

        void tick(long now) {
            if (!closed && now - lastReceive > 20000) { close("timed out", true); return; }
            synchronized (this) { tickLocked(now); }
        }

        private void tickLocked(long now) {
            if (closed && outFrames.isEmpty()) return;
            // ACKs / NACKs
            if (!ackQueue.isEmpty()) { send(address, encodeAck(0xC0, ackQueue)); ackQueue.clear(); }
            if (!nackQueue.isEmpty()) { send(address, encodeAck(0xA0, nackQueue)); nackQueue.clear(); }
            // resend lost datagrams
            if (!recovery.isEmpty()) {
                List<Integer> old = new ArrayList<>();
                for (Map.Entry<Integer, Sent> e : recovery.entrySet()) if (now - e.getValue().time > 1500) old.add(e.getKey());
                for (int seq : old) { Sent s = recovery.remove(seq); if (s != null) sendDatagram(s.frames); }
            }
            // flush frames into datagrams (limit burst to keep a small console happy)
            int budget = 64;
            List<byte[]> cur = new ArrayList<>();
            int size = 4;
            while (!outFrames.isEmpty() && budget > 0) {
                byte[] f = outFrames.peek();
                if (size + f.length > mtu - 28 && !cur.isEmpty()) { sendDatagram(cur); budget--; cur = new ArrayList<>(); size = 4; continue; }
                outFrames.poll();
                cur.add(f); size += f.length;
            }
            if (!cur.isEmpty()) sendDatagram(cur);
        }

        private byte[] encodeAck(int id, TreeSet<Integer> seqs) {
            Buf b = new Buf();
            List<int[]> ranges = new ArrayList<>();
            int start = -1, prev = -1;
            for (int s : seqs) {
                if (start == -1) { start = prev = s; continue; }
                if (s == prev + 1) { prev = s; continue; }
                ranges.add(new int[]{start, prev}); start = prev = s;
            }
            if (start != -1) ranges.add(new int[]{start, prev});
            b.put(id).beShort(ranges.size());
            for (int[] r : ranges) {
                if (r[0] == r[1]) b.put(1).leTriad(r[0]);
                else b.put(0).leTriad(r[0]).leTriad(r[1]);
            }
            return b.toArray();
        }

        public void close(String reason, boolean notifyClient) {
            synchronized (this) {
                if (closed) return;
                if (notifyClient) {
                    sendRaw(new byte[]{0x15}, 2);
                    tickLocked(System.currentTimeMillis());
                }
                closed = true;
                sessions.remove(address, this);
                pendingDisconnect = true;
                disconnectReason = reason;
            }
            dispatch();
        }

        public boolean isClosed() { return closed; }
    }
}
