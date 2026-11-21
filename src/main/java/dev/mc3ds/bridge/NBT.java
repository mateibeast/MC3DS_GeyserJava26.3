package dev.mc3ds.bridge;

import java.io.IOException;

/** Java Edition (big-endian) NBT reading, and PE "network" NBT writing. */
final class NBT {
    /** Read a named root compound into a map (empty map for TAG_End). */
    static java.util.Map<String, Object> readRoot(Buf in) throws IOException {
        int type = in.u8();
        if (type == 0) return new java.util.HashMap<>();
        in.bytes(in.beShort());
        Object o = read(in, type, 0);
        if (o instanceof java.util.Map) {
            @SuppressWarnings("unchecked") java.util.Map<String, Object> m = (java.util.Map<String, Object>) o;
            return m;
        }
        return new java.util.HashMap<>();
    }

    private static Object read(Buf in, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT too deep");
        switch (type) {
            case 1: return (int) in.s8();
            case 2: return (int) (short) in.beShort();
            case 3: return in.beInt();
            case 4: return in.beLong();
            case 5: return in.beFloat();
            case 6: return in.beDouble();
            case 7: return in.bytes(in.beInt());
            case 8: return new String(in.bytes(in.beShort()), java.nio.charset.StandardCharsets.UTF_8);
            case 9: {
                int t = in.u8(); int n = in.beInt();
                java.util.List<Object> l = new java.util.ArrayList<>();
                for (int i = 0; i < n; i++) l.add(read(in, t, depth + 1));
                return l;
            }
            case 10: {
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                while (true) {
                    int t = in.u8();
                    if (t == 0) return m;
                    String k = new String(in.bytes(in.beShort()), java.nio.charset.StandardCharsets.UTF_8);
                    m.put(k, read(in, t, depth + 1));
                }
            }
            case 11: { int n = in.beInt(); in.bytes(n * 4); return null; }
            case 12: { int n = in.beInt(); in.bytes(n * 8); return null; }
            default: throw new IOException("Unknown NBT tag " + type);
        }
    }

    // ---- PE network NBT (varint ints, varint string lengths, little-endian shorts)
    static final class PEWriter {
        final Buf b = new Buf();
        PEWriter() { b.put(10).uvarint(0); }
        private PEWriter name(int type, String n) { b.put(type); return str(n); }
        private PEWriter str(String v) { byte[] x = v.getBytes(java.nio.charset.StandardCharsets.UTF_8); b.uvarint(x.length).put(x); return this; }
        PEWriter string(String n, String v) { name(8, n); return str(v); }
        PEWriter integer(String n, int v) { name(3, n); b.varint(v); return this; }
        PEWriter shortTag(String n, int v) { name(2, n); b.leShort(v); return this; }
        PEWriter byteTag(String n, int v) { name(1, n); b.put(v); return this; }
        PEWriter compound(String n) { return name(10, n); }
        PEWriter endCompound() { b.put(0); return this; }
        /** a list of compounds: write each element's tags, then endCompound() after each */
        PEWriter compoundList(String n, int count) { name(9, n); b.put(10); b.varint(count); return this; }
        PEWriter emptyList(String n) { name(9, n); b.put(0); b.varint(0); return this; }
        byte[] end() { b.put(0); return b.toArray(); }
    }

    static void skipRoot(Buf in) throws IOException {
        int type = in.u8();
        if (type == 0) return;
        in.bytes(in.beShort());   // name
        skip(in, type, 0);
    }

    private static void skip(Buf in, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT too deep");
        switch (type) {
            case 1: in.bytes(1); break;
            case 2: in.bytes(2); break;
            case 3: in.bytes(4); break;
            case 4: in.bytes(8); break;
            case 5: in.bytes(4); break;
            case 6: in.bytes(8); break;
            case 7: in.bytes(in.beInt()); break;
            case 8: in.bytes(in.beShort()); break;
            case 9: {
                int t = in.u8(); int n = in.beInt();
                for (int i = 0; i < n; i++) skip(in, t, depth + 1);
                break;
            }
            case 10: {
                while (true) {
                    int t = in.u8();
                    if (t == 0) break;
                    in.bytes(in.beShort());
                    skip(in, t, depth + 1);
                }
                break;
            }
            case 11: in.bytes(in.beInt() * 4); break;
            case 12: in.bytes(in.beInt() * 8); break;
            default: throw new IOException("Unknown NBT tag " + type);
        }
    }
}
