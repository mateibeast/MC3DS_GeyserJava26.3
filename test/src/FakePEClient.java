import dev.mc3ds.bridge.Buf;

import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Pretends to be a Minecraft PE 1.1 (protocol 113) client: does the RakNet handshake, logs in,
 * walks through resource packs, and decodes what the bridge sends using the independent
 * sel-utils pocket113 decoders.
 */
public class FakePEClient {
    static DatagramSocket sock;
    static InetSocketAddress server;
    static final byte[] MAGIC = {0x00, (byte) 0xff, (byte) 0xff, 0x00, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, 0x12, 0x34, 0x56, 0x78};
    static int seq = 0, msgIndex = 0, orderIndex = 0;
    static final Map<Integer, byte[][]> splits = new HashMap<>();
    static final TreeMap<Integer, byte[]> ordered = new TreeMap<>();
    static int expectOrder = 0;
    static final Set<Integer> seenMsg = new HashSet<>();
    static final List<String> events = new ArrayList<>();
    static long eid = -1;
    static int chunks = 0;
    static boolean spawned = false;
    static double LOSS = Double.parseDouble(System.getProperty("loss", "0"));
    static java.util.Random rnd = new java.util.Random(7);
    static int dropped = 0;
    static float walk = 0;
    static boolean pressed = false;
    static int movesSent = 0;
    static boolean movedChest = false;

    public static void main(String[] a) throws Exception {
        server = new InetSocketAddress("127.0.0.1", Integer.parseInt(a[0]));
        sock = new DatagramSocket();
        sock.setSoTimeout(50);

        // 1. unconnected ping
        raw(new Buf().put(0x01).beLong(1).put(MAGIC).beLong(99).toArray());
        byte[] pong = recvRaw();
        Buf pb = new Buf(pong); pb.pos = 1 + 8 + 8 + 16;
        int ml = pb.beShort();
        ev("PONG motd=" + new String(pb.bytes(ml), StandardCharsets.UTF_8));
        // 2. OCR1 with mtu padding
        Buf r1 = new Buf().put(0x05).put(MAGIC).put(8);
        r1.put(new byte[1400 - r1.len - 28]);
        raw(r1.toArray());
        byte[] rep1 = recvRaw();
        ev("OCR1 reply id=0x" + Integer.toHexString(rep1[0] & 0xff) + " mtu=" + new Buf(rep1, 1 + 16 + 8 + 1, 2).beShort());
        // 3. OCR2
        Buf r2 = new Buf().put(0x07).put(MAGIC).put(4).put(~127 & 0xff).put(~0 & 0xff).put(~0 & 0xff).put(~1 & 0xff).beShort(server.getPort()).beShort(1400).beLong(99);
        raw(r2.toArray());
        byte[] rep2 = recvRaw();
        ev("OCR2 reply id=0x" + Integer.toHexString(rep2[0] & 0xff));
        // 4. connection request
        sendFrame(new Buf().put(0x09).beLong(99).beLong(System.currentTimeMillis()).put(0).toArray());
        pump(1000);
        // 5. new incoming connection
        Buf nic = new Buf().put(0x13).put(4).put(~127 & 0xff).put(~0 & 0xff).put(~0 & 0xff).put(~1 & 0xff).beShort(server.getPort());
        for (int i = 0; i < 10; i++) nic.put(4).put(0).put(0).put(0).put(0).beShort(0);
        nic.beLong(1).beLong(2);
        sendFrame(nic.toArray());
        // 6. login
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"extraData\":{\"displayName\":\"Toad 3DS\",\"identity\":\"x\"},\"identityPublicKey\":\"k\"}".getBytes());
        String chain = "{\"chain\":[\"eyJhbGciOiJFUzM4NCJ9." + payload + ".sig\"]}";
        String cdata = "eyJhbGciOiJFUzM4NCJ9." + Base64.getUrlEncoder().withoutPadding().encodeToString("{\"SkinId\":\"Standard_Steve\",\"ServerAddress\":\"x:19133\"}".getBytes()) + ".sig";
        Buf body = new Buf();
        byte[] cb = chain.getBytes(); body.leInt(cb.length).put(cb);
        byte[] db = cdata.getBytes(); body.leInt(db.length).put(db);
        Buf login = new Buf().put(0x01).beInt(113).put(0).strBytes(body.toArray());
        sendGame(login.toArray());
        long end = System.currentTimeMillis() + 15000;
        boolean movedAndChatted = false;
        while (System.currentTimeMillis() < end) {
            pump(200);
            if (spawned && !movedAndChatted) {
                movedAndChatted = true;
                // walk a bit, chat, break & place, change held item
                sendGame(new Buf().put(0x13).uvarint(eid).peVec3(9.5f, 65 + 1.62f, 8.5f).leFloat(0).leFloat(45).leFloat(45).put(0).bool(true).uvarint(0).toArray());
                sendGame(new Buf().put(0x09).put(1).str("Toad_3DS").str("hi from 3ds").toArray());
                sendGame(new Buf().put(0x24).uvarint(eid).varint(0).peBlockPos(8, 63, 8).varint(1).toArray());
                Thread.sleep(100); // the 3DS "breaks" the grass after 100ms - much faster than Java's 900ms
                sendGame(new Buf().put(0x15).peBlockPos(8, 63, 8).toArray());
                sendGame(new Buf().put(0x23).peBlockPos(8, 62, 8).uvarint(1).varint(1).peVec3(0.5f, 1f, 0.5f).peVec3(8, 63, 8).varint(1).varint(0).toArray());
                sendGame(new Buf().put(0x1f).uvarint(eid).varint(0).put(10).put(1).put(0).toArray());
                end = Math.min(end, System.currentTimeMillis() + 12000);
            }
            if (spawned) sendGame(new Buf().put(0x21).put(4).uvarint(0).toArray()); // interact/mouseover spam like the real 3DS
            if (spawned) for (int k = 0; k < 4; k++) { walk += 0.05f; movesSent++; sendGame(new Buf().put(0x13).uvarint(eid).peVec3(9.5f + walk, 65 + 1.62f, 8.5f).leFloat(0).leFloat(45).leFloat(45).put(0).bool(true).uvarint(0).toArray()); Thread.sleep(35 + rnd.nextInt(30)); }
        }
        System.out.println("=== PE client summary: chunks=" + chunks + " spawned=" + spawned + " droppedDatagrams=" + dropped + " moves sent=" + movesSent);
    }

    static void ev(String s) { System.out.println("[pe] " + s); events.add(s); }

    // ------------------------------------------------------------------ game
    static void handleGame(byte[] batch) throws Exception {
        Inflater inf = new Inflater(); inf.setInput(batch);
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] t = new byte[65536];
        while (!inf.finished()) { int n = inf.inflate(t); if (n == 0 && inf.needsInput()) break; o.write(t, 0, n); }
        Buf b = new Buf(o.toByteArray());
        while (!b.eof()) {
            byte[] pk = b.strRaw();
            int id = pk[0] & 0xff;
            Class<?> c = sul.protocol.pocket113.Packets.PLAY.get(id);
            String name = c == null ? "0x" + Integer.toHexString(id) : c.getSimpleName();
            try {
                switch (id) {
                    case 0x02: {
                        int st = new Buf(pk, 1, pk.length - 1).beInt();
                        ev("PlayStatus " + st);
                        if (st == 3) { spawned = true; ev("SPAWNED after " + chunks + " chunks");
                            new Thread(() -> { try { Thread.sleep(2500);
                                java.util.UUID u = java.util.UUID.nameUUIDFromBytes("S 1 1 17:0 5:0:4".getBytes("UTF-8"));
                                ev("-> CraftingEvent planks");
                                sendGame(new Buf().put(0x36).put(0).varint(1).peUUID(u).uvarint(0).uvarint(0).toArray());
                            } catch (Exception ex) { ex.printStackTrace(); } }).start(); }
                        break;
                    }
                    case 0x06: ev("ResourcePacksInfo len=" + pk.length + " -> reply HAVE_ALL_PACKS"); sendGame(new Buf().put(0x08).put(3).leShort(0).toArray()); break;
                    case 0x07: ev("ResourcePackStack len=" + pk.length + " -> reply COMPLETED"); sendGame(new Buf().put(0x08).put(4).leShort(0).toArray()); break;
                    case 0x0b: {
                        sul.protocol.pocket113.play.StartGame sg = new sul.protocol.pocket113.play.StartGame();
                        sg.decode(pk);
                        eid = sg.runtimeId;
                        ev("StartGame (sel-utils decode) " + sg + " consumed=" + sg._index + "/" + pk.length);
                        sendGame(new Buf().put(0x45).varint(8).toArray());
                        break;
                    }
                    case 0x3a: {
                        sul.protocol.pocket113.play.FullChunkData fc = new sul.protocol.pocket113.play.FullChunkData();
                        fc.decode(pk);
                        chunks++;
                        if (fc.position.x == 0 && fc.position.z == 0) {
                            byte[] d = fc.data.sections.length > 0 ? null : null;
                            ev("FullChunkData " + fc.position + " sections=" + fc.data.sections.length + " heights[0]=" + fc.data.heights[0]
                                    + " biome0=" + fc.data.biomes[0] + " sec0 id(x0,y0,z0)=" + (fc.data.sections[0].blockIds[0] & 0xff)
                                    + " id(x0,y1,z0)=" + (fc.data.sections[0].blockIds[1] & 0xff)
                                    + (fc.data.sections.length > 3 ? " sec3 top=" + (fc.data.sections[3].blockIds[15] & 0xff) : "")
                                    + " consumed=" + fc._index + "/" + pk.length);
                        }
                        break;
                    }
                    case 0x0a: { ev("SetTime"); break; }
                    case 0x3d: { ev("!! ChangeDimension received"); break; }
                    case 0x0f: { ev("AddItemEntity hex=" + Buf.hex(pk, 40)); break; }
                    case 0x11: { ev("TakeItemEntity hex=" + Buf.hex(pk, 10)); break; }
                    case 0x0e: { ev("RemoveEntity hex=" + Buf.hex(pk, 10)); break; }
                    case 0x2d: { sul.protocol.pocket113.play.Respawn r = new sul.protocol.pocket113.play.Respawn(); r.decode(pk); ev("Respawn packet " + r.position + " -> pressing Respawn");
                                 if (!pressed) { pressed = true; sendGame(new Buf().put(0x24).uvarint(eid).varint(7).peBlockPos(0, 0, 0).varint(-1).toArray()); } break; }
                    case 0x2a: { ev("SetHealth"); break; }
                    case 0x1e: { Buf ab = new Buf(pk); ab.pos = 1; ab.uvarlong(); ab.uvarint(); ab.leFloat(); ab.leFloat(); float hp = ab.leFloat(); ev("UpdateAttributes health=" + hp + (pressed ? " (after pressing respawn)" : "")); break; }
                    case 0x1f: { ev("MobEquipment hex=" + Buf.hex(pk, 20)); break; }
                    case 0x0d: { Buf ae = new Buf(pk); ae.pos = 1; ae.varlong(); ae.uvarlong(); ev("AddEntity type=" + ae.uvarint()); break; }
                    case 0x27: { ev("SetEntityData len=" + pk.length + " hex=" + Buf.hex(pk, 24)); break; }
                    case 0x2c: { ev("Animate hex=" + Buf.hex(pk, 12)); break; }
                    case 0x30: { ev("ContainerOpen hex=" + Buf.hex(pk, 16));
                                 if (pk[2] == 3) new Thread(() -> { try {   // enchant like a 3DS: only inventory changes are reported
                                     Thread.sleep(400); ev("-> sword into enchanting table");
                                     sendGame(new Buf().put(0x32).put(0).varint(0).varint(0).varint(0).put(0).toArray());
                                     Thread.sleep(400); ev("-> lapis into enchanting table");
                                     sendGame(new Buf().put(0x32).put(0).varint(3).varint(0).varint(0).put(0).toArray());
                                     Thread.sleep(400); ev("-> enchant (InventoryAction 2), take both back (2 lapis used)");
                                     // enchanted sword comes back first (with its PE ench NBT), the lapis 300 ms later, the "enchanted" message last
                                     byte[] en = new Buf().put(10).leShort(0).put(9).leShort(4).put("ench".getBytes()).put(10).leInt(1).put(2).leShort(2).put("id".getBytes()).leShort(9).put(2).leShort(3).put("lvl".getBytes()).leShort(3).put(0).put(0).toArray();
                                     sendGame(new Buf().put(0x32).put(0).varint(0).varint(0).varint(276).varint(1).leShort(en.length).put(en).varint(0).varint(0).put(0).toArray());
                                     Thread.sleep(300);
                                     sendGame(new Buf().put(0x32).put(0).varint(3).varint(0).varint(351).varint((4 << 8) | 62).leShort(0).varint(0).varint(0).put(0).toArray());
                                     sendGame(new Buf().put(0x2f).varint(2).varint(276).varint(1).leShort(0).varint(0).varint(0).varint(9).varint(1).toArray());
                                     Thread.sleep(800); sendGame(new Buf().put(0x31).put(pk[1]).toArray());
                                 } catch (Exception ex) { ex.printStackTrace(); } }).start();
                                 break; }
                    case 0x34: { Buf cb = new Buf(pk); cb.pos = 1; int w = cb.uvarint(); if (w == 5) { ev("Chest contents received (window 5)");
                                 if (!movedChest) { movedChest = true;
                                   // take 4 of the 10 diamonds into inventory slot 9 (chest keeps 6)
                                   // the two halves of the move arrive 200 ms apart (gain first), like a real 3DS sometimes does
                                   sendGame(new Buf().put(0x32).put(0).varint(9).varint(0).varint(264).varint(4).leShort(0).varint(0).varint(0).put(0).toArray());
                                   Thread.sleep(200);
                                   sendGame(new Buf().put(0x32).put(5).varint(0).varint(0).varint(264).varint(6).leShort(0).varint(0).varint(0).put(0).toArray());
                                   Thread.sleep(300);
                                   sendGame(new Buf().put(0x31).put(5).toArray()); } } break; }
                    case 0x4e: { Buf cb = new Buf(pk); cb.pos = 1; String js = cb.str(); ev("AvailableCommands " + js.length() + " chars: " + js.substring(0, Math.min(170, js.length())));
                                 sendGame(new Buf().put(0x4f).str("home").str("default").uvarint(0).uvarint(0).bool(true).uvarint(1).str("{\"args\":\"base\"}").str("null").toArray()); break; }
                    case 0x38: { ev("BlockEntityData " + new String(pk, java.nio.charset.StandardCharsets.ISO_8859_1).replaceAll("[^A-Za-z0-9]", ".") + " hex=" + Buf.hex(pk, 60)); break; }
                    case 0x0c: { ev("AddPlayer (other player visible)"); break; }
                    case 0x13: { sul.protocol.pocket113.play.MovePlayer mp = new sul.protocol.pocket113.play.MovePlayer(); mp.decode(pk); ev("MovePlayer x=" + mp.position.x + " z=" + mp.position.z + " hex=" + Buf.hex(pk, 40)); break; }
                    case 0x16: {
                        sul.protocol.pocket113.play.UpdateBlock ub = new sul.protocol.pocket113.play.UpdateBlock(); ub.decode(pk);
                        ev("UpdateBlock " + ub); break;
                    }
                    default: {
                        if (c != null) {
                            sul.utils.Packet p = (sul.utils.Packet) c.newInstance();
                            p.decode(pk);
                            String s = p.toString();
                            if (s.length() > 400) s = s.substring(0, 400) + "...";
                            ev(name + " " + s + " consumed=" + p._index + "/" + pk.length);
                        } else ev("unknown " + name);
                    }
                }
            } catch (Throwable e) {
                ev("!! DECODE FAIL " + name + ": " + e + " hex=" + Buf.hex(pk, 80));
            }
        }
    }

    static void sendGame(byte[] packet) throws Exception {
        Buf raw = new Buf().strBytes(packet);
        Deflater d = new Deflater(); d.setInput(raw.toArray()); d.finish();
        byte[] tmp = new byte[raw.len + 128]; int n = d.deflate(tmp);
        Buf p = new Buf().put(0xFE).put(tmp, 0, n);
        sendFrame(p.toArray());
    }

    // ------------------------------------------------------------------ raknet client
    static void raw(byte[] d) throws Exception { sock.send(new DatagramPacket(d, d.length, server)); }
    static byte[] recvRaw() throws Exception {
        byte[] b = new byte[2048]; DatagramPacket p = new DatagramPacket(b, b.length);
        sock.receive(p); return Arrays.copyOf(b, p.getLength());
    }
    static void sendFrame(byte[] payload) throws Exception {
        int max = 1300;
        if (payload.length <= max) {
            Buf f = new Buf().put(0x84).leTriad(seq++);
            f.put(3 << 5).beShort(payload.length * 8).leTriad(msgIndex++).leTriad(orderIndex++).put(0).put(payload);
            raw(f.toArray());
        } else {
            int count = (payload.length + max - 1) / max; int oi = orderIndex++;
            for (int i = 0; i < count; i++) {
                byte[] part = Arrays.copyOfRange(payload, i * max, Math.min(payload.length, (i + 1) * max));
                Buf f = new Buf().put(0x84).leTriad(seq++);
                f.put((3 << 5) | 0x10).beShort(part.length * 8).leTriad(msgIndex++).leTriad(oi).put(0).beInt(count).beShort(5).beInt(i).put(part);
                raw(f.toArray());
            }
        }
    }
    static void pump(long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            byte[] d;
            try { d = recvRaw(); } catch (SocketTimeoutException e) { continue; }
            int id = d[0] & 0xff;
            if ((id & 0x80) == 0 || (id & 0x40) != 0 || (id & 0x20) != 0) continue;
            if (LOSS > 0 && rnd.nextDouble() < LOSS) { dropped++; continue; } // simulate packet loss (no ACK)
            Buf in = new Buf(d); in.pos = 1;
            int s = in.leTriad();
            raw(new Buf().put(0xC0).beShort(1).put(1).leTriad(s).toArray());
            while (!in.eof()) {
                int flags = in.u8(); int rel = flags >> 5; boolean split = (flags & 0x10) != 0;
                int len = (in.beShort() + 7) / 8;
                int mi = -1, oi = 0;
                if (rel == 2 || rel == 3 || rel == 4 || rel == 6 || rel == 7) mi = in.leTriad();
                if (rel == 1 || rel == 4) in.leTriad();
                if (rel == 1 || rel == 3 || rel == 4 || rel == 7) { oi = in.leTriad(); in.u8(); }
                int sc = 0, sid = 0, si = 0;
                if (split) { sc = in.beInt(); sid = in.beShort(); si = in.beInt(); }
                byte[] p = in.bytes(len);
                if (mi >= 0 && !seenMsg.add(mi)) continue;
                if (split) {
                    final int fc = sc;
                    byte[][] parts = splits.computeIfAbsent(sid, k -> new byte[fc][]);
                    parts[si] = p;
                    boolean done = true; for (byte[] x : parts) if (x == null) done = false;
                    if (!done) continue;
                    splits.remove(sid); p = Buf.concat(parts);
                }
                if (rel == 3) {
                    ordered.put(oi, p);
                    while (ordered.containsKey(expectOrder)) deliver(ordered.remove(expectOrder++));
                } else deliver(p);
            }
        }
    }
    static void deliver(byte[] p) throws Exception {
        int id = p[0] & 0xff;
        if (id == 0x10) ev("ConnectionRequestAccepted len=" + p.length);
        else if (id == 0xFE) handleGame(Arrays.copyOfRange(p, 1, p.length));
        else if (id == 0x03) { }
        else ev("raknet msg 0x" + Integer.toHexString(id));
    }
}
