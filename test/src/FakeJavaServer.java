import dev.mc3ds.bridge.Buf;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Minimal Java 1.12.2 server: logs what the bridge sends and serves a tiny flat world. */
public class FakeJavaServer {
    static final int SOUND_ZOMBIE = 480;
    static final List<String> received = Collections.synchronizedList(new ArrayList<>());
    static byte[] key;

    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(a[0]);
        key = a.length > 1 ? Files.readAllBytes(Paths.get(a[1])) : null;
        ServerSocket ss = new ServerSocket(port);
        System.out.println("[java] listening " + port);
        while (true) {
            Socket s = ss.accept();
            new Thread(() -> { try { handle(s); } catch (Exception e) { System.out.println("[java] conn ended: " + e); } }).start();
        }
    }

    static int threshold = -1;

    static void handle(Socket s) throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        OutputStream out = new BufferedOutputStream(s.getOutputStream());
        threshold = -1;
        Buf hs = read(in);
        int id = hs.jvarint(); int proto = hs.jvarint(); String host = hs.str(); int port = hs.beShort(); int next = hs.jvarint();
        log("handshake id=" + id + " proto=" + proto + " port=" + port + " next=" + next + " hostParts=" + host.split("\0").length);
        String[] parts = host.split("\0");
        if (parts.length > 1 && key != null) {
            String fg = parts[1];
            byte[] d = fg.getBytes(StandardCharsets.UTF_8);
            int hdr = "^Floodgate^".length() + 1;
            if (!fg.startsWith("^Floodgate^")) throw new IllegalStateException("bad floodgate header");
            int split = fg.indexOf('!', hdr);
            byte[] iv = Base64.getDecoder().decode(fg.substring(hdr, split));
            byte[] ct = Base64.getDecoder().decode(fg.substring(split + 1));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            String plain = new String(c.doFinal(ct), StandardCharsets.UTF_8);
            String[] f = plain.split("\0");
            log("FLOODGATE OK fields=" + f.length + " name=" + f[1] + " xuid=" + f[2] + " ip=" + f[7] + " subscribe=" + f[10]);
        }
        Buf ls = read(in); ls.jvarint(); String name = ls.str();
        log("login start name=" + name);
        write(out, new Buf().jvarint(0x03).jvarint(256));
        threshold = 256;
        write(out, new Buf().jvarint(0x02).str("00000000-0000-0000-0009-01f64f65c7c3").str(name));
        int eid = 42;
        write(out, new Buf().jvarint(0x23).beInt(eid).put(0).beInt(0).put(1).put(20).str("default").bool(false));
        // other player
        UUID u = UUID.fromString("11111111-2222-3333-4444-555555555555");
        String tex = Base64.getEncoder().encodeToString("{\"textures\":{\"SKIN\":{\"url\":\"http://127.0.0.1:8099/skin.png\",\"metadata\":{\"model\":\"slim\"}}}}".getBytes());
        write(out, new Buf().jvarint(0x2E).jvarint(0).jvarint(1).jUUID(u).str("Steve").jvarint(1).str("textures").str(tex).bool(true).str("sig").jvarint(0).jvarint(10).bool(false));
        write(out, new Buf().jvarint(0x05).jvarint(77).jUUID(u).beDouble(10).beDouble(64).beDouble(10).put(64).put(0).put(0xFF));
        write(out, new Buf().jvarint(0x03).jvarint(78).jUUID(UUID.randomUUID()).jvarint(90).beDouble(6).beDouble(64).beDouble(6).put(0).put(0).put(0).beShort(0).beShort(0).beShort(0).put(0xFF));
        //write(out, new Buf().jvarint(0x26).jvarint(77).beShort(4096).beShort(0).beShort(0).bool(true));
        // empty placeholder chunk at spawn, then real terrain arrives later (like a far-away, unloaded area)
        byte[] bio = new byte[256];
        write(out, new Buf().jvarint(0x20).beInt(0).beInt(0).bool(true).jvarint(0).jvarint(256).put(bio).jvarint(0));
        write(out, new Buf().jvarint(0x2F).beDouble(8.5).beDouble(65).beDouble(8.5).beFloat(90).beFloat(0).put(0).jvarint(7));
        Thread.sleep(Long.getLong("delay", 0L));
        for (int cx = -2; cx <= 2; cx++) for (int cz = -2; cz <= 2; cz++) write(out, chunk(cx, cz));
        write(out, new Buf().jvarint(0x47).beLong(100).beLong(1000));
        write(out, new Buf().jvarint(0x41).beFloat(18f).jvarint(17).beFloat(3f));
        write(out, new Buf().jvarint(0x0F).str("{\"translate\":\"chat.type.text\",\"with\":[{\"text\":\"Steve\"},{\"text\":\"hello 3ds!\",\"color\":\"yellow\"}]}").put(0));
        // inventory: diamond sword in hotbar 0, 64 stone in hotbar 1, glazed terracotta in main
        Buf wi = new Buf().jvarint(0x14).put(0).beShort(46);
        for (int i = 0; i < 46; i++) {
            if (i == 36) wi.beShort(276).put(1).beShort(0).put(0);
            else if (i == 37) wi.beShort(1).put(64).beShort(0).put(0);
            else if (i == 38) wi.beShort(17).put(4).beShort(0).put(0);
            else if (i == 10) { // item with NBT (display name) to test skipping
                wi.beShort(235).put(3).beShort(0);
                wi.put(10).beShort(0).put(10).beShort(7).put("display".getBytes()).put(8).beShort(4).put("Name".getBytes()).beShort(3).put("abc".getBytes()).put(0).put(0);
            }
            else wi.beShort(-1);
        }
        write(out, wi);
        write(out, new Buf().jvarint(0x0B).beLong(Buf.jPos(3, 64, 3)).jvarint(95 << 4 | 14)); // red stained glass
        write(out, new Buf().jvarint(0x1F).beLong(123456789L));
        out.flush();
        final OutputStream fout = out;
        Thread bg = new Thread(() -> {
            try {
                // lots of extra chunks like a real server with view distance 10
                for (int cx = -10; cx <= 10; cx++) for (int cz = -10; cz <= 10; cz++) if (Math.abs(cx) > 2 || Math.abs(cz) > 2) write(fout, chunk(cx, cz));
                for (int t = 0; t < 20; t++) {
                    Thread.sleep(1000);
                    write(fout, new Buf().jvarint(0x47).beLong(t).beLong(2000 + t * 20));
                    if (t % 5 == 0) write(fout, new Buf().jvarint(0x1F).beLong(1000 + t));
                    if (t == 6) write(fout, new Buf().jvarint(0x2F).beDouble(20.5).beDouble(70).beDouble(20.5).beFloat(0).beFloat(0).put(0).jvarint(99));
                    if (t == 3) write(fout, new Buf().jvarint(0x46).beLong(Buf.jPos(1, 70, 2)));
                    if (t == 2) {   // title, boss bar, a zombie groan, someone breaking stone
                        write(fout, new Buf().jvarint(0x48).jvarint(3).beInt(10).beInt(60).beInt(10));
                        write(fout, new Buf().jvarint(0x48).jvarint(0).str("{\"text\":\"Welcome!\",\"color\":\"gold\"}"));
                        write(fout, new Buf().jvarint(0x0C).jUUID(new UUID(1, 2)).jvarint(0).str("{\"text\":\"Ender Dragon\"}").beFloat(0.75f).jvarint(0).jvarint(0).put(0));
                        write(fout, new Buf().jvarint(0x49).jvarint(SOUND_ZOMBIE).jvarint(5).beInt(10 * 8).beInt(65 * 8).beInt(10 * 8).beFloat(1).beFloat(1));
                        write(fout, new Buf().jvarint(0x21).beInt(2001).beLong(Buf.jPos(9, 64, 9)).beInt(1).bool(false));
                    }
                    if (t == 7) {   // enchanting table: sword in hotbar 0, 64 lapis in hotbar 3
                        write(fout, new Buf().jvarint(0x13).put(9).str("minecraft:enchanting_table").str("{\"text\":\"Enchant\"}").put(0));
                        Buf ew = new Buf().jvarint(0x14).put(9).beShort(38);
                        for (int i = 0; i < 38; i++) { if (i == 29) ew.beShort(276).put(1).beShort(0).put(0); else if (i == 32) ew.beShort(351).put(64).beShort(4).put(0); else ew.beShort(-1); }
                        write(fout, ew);
                    }
                    if (t == 4) {   // villager trade window
                        write(fout, new Buf().jvarint(0x13).put(7).str("minecraft:villager").str("{\"text\":\"Farmer\"}").put(0));
                        Buf tl = new Buf().jvarint(0x18).str("MC|TrList").beInt(7).put(2);
                        tl.beShort(296).put(20).beShort(0).put(0).beShort(388).put(1).beShort(0).put(0).bool(false).bool(false).beInt(0).beInt(7);   // 20 wheat -> 1 emerald
                        tl.beShort(388).put(1).beShort(0).put(0).beShort(297).put(3).beShort(0).put(0).bool(false).bool(false).beInt(2).beInt(7);    // 1 emerald -> 3 bread
                        write(fout, tl);
                    }
                    if (t == 8) write(fout, new Buf().jvarint(0x30).jvarint(42).beLong(Buf.jPos(4, 64, 4)));      // we went to bed
                    if (t == 10) write(fout, new Buf().jvarint(0x06).jvarint(42).put(2));                          // woke up
                    if (t == 12) { // a plugin "refreshes" the player: 1.12 dimension bounce (nether and straight back)
                        write(fout, new Buf().jvarint(0x35).beInt(-1).put(1).put(0).str("default"));
                        write(fout, new Buf().jvarint(0x35).beInt(0).put(1).put(0).str("default"));
                        write(fout, new Buf().jvarint(0x2F).beDouble(1.5).beDouble(70).beDouble(2.5).beFloat(0).beFloat(0).put(0).jvarint(101));
                        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) write(fout, chunk(cx, cz));
                    }
                    if (t == 1) { // a skeleton with a bow shoots an arrow
                        write(fout, new Buf().jvarint(0x03).jvarint(600).jUUID(UUID.randomUUID()).jvarint(51).beDouble(12).beDouble(64).beDouble(12).put(0).put(0).put(0).beShort(0).beShort(0).beShort(0).put(255));
                        write(fout, new Buf().jvarint(0x3F).jvarint(600).jvarint(0).beShort(261).put(1).beShort(0).put(0));
                        write(fout, new Buf().jvarint(0x00).jvarint(601).jUUID(UUID.randomUUID()).put(60).beDouble(12).beDouble(65.5).beDouble(12).put(0).put(0).beInt(600).beShort(-8000).beShort(800).beShort(-8000));
                    }
                    if (t == 2) { // a white bed placed, then its colour block entity
                        write(fout, new Buf().jvarint(0x0B).beLong(Buf.jPos(4, 64, 4)).jvarint(26 << 4 | 9));
                        Buf nbt = new Buf().put(10).beShort(0).put(3).beShort(5).put("color".getBytes()).beInt(0).put(3).beShort(1).put("x".getBytes()).beInt(4).put(3).beShort(1).put("y".getBytes()).beInt(64).put(3).beShort(1).put("z".getBytes()).beInt(4).put(8).beShort(2).put("id".getBytes()).beShort(13).put("minecraft:bed".getBytes()).put(0);
                        write(fout, new Buf().jvarint(0x09).beLong(Buf.jPos(4, 64, 4)).put(11).put(nbt.data, 0, nbt.len));
                    }
                    if (t == 4) write(fout, new Buf().jvarint(0x41).beFloat(0f).jvarint(20).beFloat(5f));
                }
            } catch (Exception e) { System.out.println("[java] bg sender ended " + e); }
        });
        if (!Boolean.getBoolean("stall")) bg.start();
        long end = System.currentTimeMillis() + 25000;
        s.setSoTimeout(500);
        while (System.currentTimeMillis() < end) {
            Buf p;
            try { p = read(in); } catch (java.net.SocketTimeoutException te) { continue; }
            int pid = p.jvarint();
            String desc;
            switch (pid) {
                case 0x00: desc = "teleport_confirm " + p.jvarint(); break;
                case 0x02: desc = "chat '" + p.str() + "'"; break;
                case 0x04: desc = "settings locale=" + p.str() + " view=" + p.u8(); break;
                case 0x09: desc = "plugin " + p.str(); break;
                case 0x0B: desc = "keepalive " + p.beLong(); break;
                case 0x0E: desc = String.format("pos_look %.2f %.2f %.2f yaw=%.1f", p.beDouble(), p.beDouble(), p.beDouble(), p.beFloat()); break;
                case 0x14: { int st = p.jvarint(); int[] xyz = Buf.jPos(p.beLong());
                    if (st == 0) digT = System.currentTimeMillis();
                    desc = "dig status=" + st + " at " + Arrays.toString(xyz) + " face=" + p.u8() + " (+" + (System.currentTimeMillis() - digT) + "ms after start)";
                    if (st == 2) {
                        write(out, new Buf().jvarint(0x0B).beLong(Buf.jPos(xyz[0], xyz[1], xyz[2])).jvarint(0));
                        // the block drops a dirt item, which the player then picks up
                        write(out, new Buf().jvarint(0x00).jvarint(500).jUUID(UUID.randomUUID()).put(2).beDouble(8.5).beDouble(63.2).beDouble(8.5).put(0).put(0).beInt(1).beShort(0).beShort(0).beShort(0));
                        write(out, new Buf().jvarint(0x3C).jvarint(500).put(0).jvarint(0).put(0).put(6).jvarint(5).beShort(3).put(1).beShort(0).put(0).put(255));
                        write(out, new Buf().jvarint(0x4B).jvarint(500).jvarint(42).jvarint(1));
                        write(out, new Buf().jvarint(0x32).jvarint(1).jvarint(500));
                    }
                    break; }
                case 0x1A: desc = "held_item " + p.beShort(); break;
                case 0x01: { String t = p.str(); desc = "tab_complete '" + t + "'";
                    write(out, new Buf().jvarint(0x0E).jvarint(5).str("/spawn").str("/home").str("/minecraft:tp").str("/tpa").str("/sethome")); break; }
                case 0x03: { int a = p.jvarint(); desc = "client_status " + a;
                    if (a == 0) { write(out, new Buf().jvarint(0x35).beInt(0).put(1).put(0).str("default"));
                                  write(out, new Buf().jvarint(0x41).beFloat(20f).jvarint(20).beFloat(5f));
                                  write(out, new Buf().jvarint(0x2F).beDouble(1.5).beDouble(70).beDouble(2.5).beFloat(0).beFloat(0).put(0).jvarint(100)); }
                    break; }
                case 0x1D: desc = "swing"; break;
                case 0x1F: { int[] xyz = Buf.jPos(p.beLong()); desc = "place at " + Arrays.toString(xyz) + " face=" + p.jvarint();
                    // pretend it's a chest: open a 27-slot window with 10 diamonds in slot 0
                    write(out, new Buf().jvarint(0x13).put(5).str("minecraft:chest").str("{\"text\":\"Chest\"}").put(27));
                    Buf wi2 = new Buf().jvarint(0x14).put(5).beShort(63);
                    for (int i = 0; i < 63; i++) { if (i == 0) wi2.beShort(264).put(10).beShort(0).put(0); else if (i == 27 + 27) wi2.beShort(276).put(1).beShort(0).put(0); else if (i == 55) wi2.beShort(1).put(64).beShort(0).put(0); else if (i == 56) wi2.beShort(17).put(4).beShort(0).put(0); else wi2.beShort(-1); }
                    write(out, wi2);
                    break; }
                case 0x07: { int w = p.u8(); int sl = (short) p.beShort(); int btn = p.u8(); int act = p.beShort(); int mode = p.jvarint(); int iid = (short) p.beShort(); String it = iid < 0 ? "empty" : (iid + " x" + p.u8());
                    desc = "click window=" + w + " slot=" + sl + " " + (btn == 0 ? "LEFT" : "RIGHT") + " (slot had " + it + ")"; break; }
                case 0x08: desc = "close_window " + p.u8(); break;
                case 0x06: desc = "enchant_item window=" + p.u8() + " option=" + p.u8(); break;
                default: desc = "0x" + Integer.toHexString(pid);
            }
            if (pid == 0x0E) posMoves++;
            if (pid == 0x0C || pid == 0x0E || pid == 0x0F) { moves++; if (firstMove == 0) firstMove = System.currentTimeMillis(); lastMove = System.currentTimeMillis(); }
            if (pid != 0x0E && pid != 0x0C && pid != 0x0F || !received.contains(desc)) { if (pid != 0x0C && pid != 0x0F) log("<- " + desc); }
            received.add(desc);
        }
        s.close();
    }
    static long moves, firstMove, lastMove, digT, posMoves;
    static { Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("[java] position packets: " + posMoves + ", all movement packets: " + moves + " over " + (lastMove - firstMove) + " ms = " + String.format("%.1f", moves * 1000.0 / Math.max(1, lastMove - firstMove)) + "/s"))); }

    static Buf chunk(int cx, int cz) {
        // one section (y 0-15): bedrock y0, stone y1-14, grass y15 ; palette bits 4
        int[] palette = {0, 7 << 4, 1 << 4, 2 << 4};
        Buf data = new Buf();
        // section 0 full, section 3 (y48-63) dirt top + grass at y63
        for (int sec : new int[]{0, 3}) {
            data.put(4).jvarint(palette.length);
            for (int p : palette) data.jvarint(p);
            long[] longs = new long[4096 * 4 / 64];
            for (int i = 0; i < 4096; i++) {
                int y = i >> 8;
                int v = sec == 0 ? (y == 0 ? 1 : 2) : (y < 15 ? 2 : 3);
                longs[(i * 4) / 64] |= ((long) v) << ((i * 4) % 64);
            }
            data.jvarint(longs.length);
            for (long l : longs) data.beLong(l);
            byte[] bl = new byte[2048]; data.put(bl);
            byte[] sl = new byte[2048]; Arrays.fill(sl, (byte) 0xFF); data.put(sl);
        }
        byte[] biomes = new byte[256]; Arrays.fill(biomes, (byte) 1); data.put(biomes);
        return new Buf().jvarint(0x20).beInt(cx).beInt(cz).bool(true).jvarint(1 | 8).jvarint(data.len).put(data.data, 0, data.len).jvarint(0);
    }

    static void log(String m) { System.out.println("[java] " + m); received.add(m); }

    static int readVarInt(DataInputStream in) throws IOException {
        int v = 0, shift = 0;
        while (true) { int b = in.readUnsignedByte(); v |= (b & 0x7F) << shift; if ((b & 0x80) == 0) return v; shift += 7; }
    }
    static Buf read(DataInputStream in) throws Exception {
        int len = readVarInt(in);
        byte[] d = new byte[len]; in.readFully(d);
        if (threshold < 0) return new Buf(d);
        Buf b = new Buf(d);
        int dl = b.jvarint();
        if (dl == 0) return new Buf(b.rest());
        Inflater inf = new Inflater(); inf.setInput(d, b.pos, d.length - b.pos);
        byte[] r = new byte[dl]; inf.inflate(r); return new Buf(r);
    }
    static synchronized void write(OutputStream out, Buf p) throws IOException {
        byte[] body = p.toArray();
        Buf f = new Buf();
        if (threshold < 0) f.jvarint(body.length).put(body);
        else if (body.length < threshold) f.jvarint(body.length + 1).put(0).put(body);
        else {
            Deflater d = new Deflater(); d.setInput(body); d.finish();
            byte[] tmp = new byte[body.length + 64]; int n = d.deflate(tmp);
            Buf inner = new Buf().jvarint(body.length).put(tmp, 0, n);
            f.jvarint(inner.len).put(inner.data, 0, inner.len);
        }
        out.write(f.data, 0, f.len); out.flush();
    }
}
