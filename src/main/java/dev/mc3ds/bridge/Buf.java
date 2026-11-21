package dev.mc3ds.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Growable byte buffer with readers/writers for both the Pocket Edition (little-endian,
 * zigzag varints) and Java Edition (big-endian, plain varints) wire formats.
 */
public final class Buf {
    public byte[] data;
    public int pos;      // read position
    public int len;      // write position / valid length

    public Buf() { this(64); }
    public Buf(int cap) { data = new byte[Math.max(cap, 16)]; }
    public Buf(byte[] src) { data = src; len = src.length; }
    public Buf(byte[] src, int off, int length) { data = src; pos = off; len = off + length; }

    // ------------------------------------------------------------------ util
    private void ensure(int extra) {
        if (len + extra > data.length) data = Arrays.copyOf(data, Math.max(data.length * 2, len + extra));
    }
    public int remaining() { return len - pos; }
    public boolean eof() { return pos >= len; }
    public byte[] toArray() { return Arrays.copyOf(data, len); }
    private void need(int n) throws IOException {
        if (pos + n > len) throw new IOException("Buffer underflow (need " + n + ", have " + (len - pos) + ")");
    }

    // ------------------------------------------------------------------ raw
    public Buf put(int b) { ensure(1); data[len++] = (byte) b; return this; }
    public Buf put(byte[] b) { return put(b, 0, b.length); }
    public Buf put(byte[] b, int off, int n) { ensure(n); System.arraycopy(b, off, data, len, n); len += n; return this; }
    public int u8() throws IOException { need(1); return data[pos++] & 0xFF; }
    public byte s8() throws IOException { need(1); return data[pos++]; }
    public byte[] bytes(int n) throws IOException { need(n); byte[] r = Arrays.copyOfRange(data, pos, pos + n); pos += n; return r; }
    public byte[] rest() { byte[] r = Arrays.copyOfRange(data, pos, len); pos = len; return r; }
    public boolean bool() throws IOException { return u8() != 0; }
    public Buf bool(boolean v) { return put(v ? 1 : 0); }

    // ------------------------------------------------------------------ big endian
    public Buf beShort(int v) { ensure(2); data[len++] = (byte) (v >> 8); data[len++] = (byte) v; return this; }
    public int beShort() throws IOException { need(2); int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF); pos += 2; return v; }
    public Buf beInt(int v) { ensure(4); for (int i = 3; i >= 0; i--) data[len++] = (byte) (v >> (i * 8)); return this; }
    public int beInt() throws IOException { need(4); int v = 0; for (int i = 0; i < 4; i++) v = (v << 8) | (data[pos++] & 0xFF); return v; }
    public Buf beLong(long v) { ensure(8); for (int i = 7; i >= 0; i--) data[len++] = (byte) (v >> (i * 8)); return this; }
    public long beLong() throws IOException { need(8); long v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | (data[pos++] & 0xFF); return v; }
    public Buf beFloat(float f) { return beInt(Float.floatToIntBits(f)); }
    public float beFloat() throws IOException { return Float.intBitsToFloat(beInt()); }
    public Buf beDouble(double d) { return beLong(Double.doubleToLongBits(d)); }
    public double beDouble() throws IOException { return Double.longBitsToDouble(beLong()); }

    // ------------------------------------------------------------------ little endian
    public Buf leShort(int v) { ensure(2); data[len++] = (byte) v; data[len++] = (byte) (v >> 8); return this; }
    public int leShort() throws IOException { need(2); int v = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8); pos += 2; return v; }
    public Buf leTriad(int v) { ensure(3); data[len++] = (byte) v; data[len++] = (byte) (v >> 8); data[len++] = (byte) (v >> 16); return this; }
    public int leTriad() throws IOException { need(3); int v = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8) | ((data[pos + 2] & 0xFF) << 16); pos += 3; return v; }
    public Buf leInt(int v) { ensure(4); for (int i = 0; i < 4; i++) data[len++] = (byte) (v >> (i * 8)); return this; }
    public int leInt() throws IOException { need(4); int v = 0; for (int i = 0; i < 4; i++) v |= (data[pos++] & 0xFF) << (i * 8); return v; }
    public Buf leLong(long v) { ensure(8); for (int i = 0; i < 8; i++) data[len++] = (byte) (v >> (i * 8)); return this; }
    public long leLong() throws IOException { need(8); long v = 0; for (int i = 0; i < 8; i++) v |= (long) (data[pos++] & 0xFF) << (i * 8); return v; }
    public Buf leFloat(float f) { return leInt(Float.floatToIntBits(f)); }
    public float leFloat() throws IOException { return Float.intBitsToFloat(leInt()); }

    // ------------------------------------------------------------------ varints
    public Buf uvarint(long v) {
        ensure(10);
        while ((v & ~0x7FL) != 0) { data[len++] = (byte) ((v & 0x7F) | 0x80); v >>>= 7; }
        data[len++] = (byte) v;
        return this;
    }
    public long uvarlong() throws IOException {
        long v = 0; int shift = 0;
        while (true) {
            int b = u8();
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
            if (shift > 63) throw new IOException("VarLong too big");
        }
    }
    public int uvarint() throws IOException { return (int) uvarlong(); }
    /** Java Edition VarInt (two's complement, 5 bytes max). */
    public Buf jvarint(int v) { return uvarint(v & 0xFFFFFFFFL); }
    public int jvarint() throws IOException { return (int) uvarlong(); }
    /** PE zigzag VarInt. */
    public Buf varint(int v) { return uvarint(((long) ((v << 1) ^ (v >> 31))) & 0xFFFFFFFFL); }
    public int varint() throws IOException { long r = uvarlong(); int u = (int) r; return (u >>> 1) ^ -(u & 1); }
    public Buf varlong(long v) { return uvarint((v << 1) ^ (v >> 63)); }
    public long varlong() throws IOException { long r = uvarlong(); return (r >>> 1) ^ -(r & 1); }

    // ------------------------------------------------------------------ strings
    /** PE string (unsigned varint length) — identical layout to Java string with varint length. */
    public Buf str(String s) { byte[] b = s.getBytes(StandardCharsets.UTF_8); uvarint(b.length); return put(b); }
    public Buf strBytes(byte[] b) { uvarint(b.length); return put(b); }
    public String str() throws IOException { int n = uvarint(); if (n < 0 || n > 4 * 1024 * 1024) throw new IOException("Bad string length " + n); return new String(bytes(n), StandardCharsets.UTF_8); }
    public byte[] strRaw() throws IOException { int n = uvarint(); if (n < 0 || n > 16 * 1024 * 1024) throw new IOException("Bad length " + n); return bytes(n); }

    // ------------------------------------------------------------------ PE helpers
    public Buf peUUID(UUID u) {
        long msb = u.getMostSignificantBits(), lsb = u.getLeastSignificantBits();
        leInt((int) (msb >>> 32)); leInt((int) msb); leInt((int) (lsb >>> 32)); leInt((int) lsb);
        return this;
    }
    public Buf peBlockPos(int x, int y, int z) { varint(x); uvarint(y & 0xFFFFFFFFL); return varint(z); }
    public Buf peVec3(float x, float y, float z) { leFloat(x); leFloat(y); return leFloat(z); }

    // ------------------------------------------------------------------ Java helpers
    public Buf jUUID(UUID u) { beLong(u.getMostSignificantBits()); return beLong(u.getLeastSignificantBits()); }
    public UUID jUUID() throws IOException { return new UUID(beLong(), beLong()); }
    public static long jPos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }
    public static int[] jPos(long v) {
        int x = (int) (v >> 38), y = (int) ((v >> 26) & 0xFFF), z = (int) (v << 38 >> 38);
        if (y >= 2048) y -= 4096;
        return new int[]{x, y, z};
    }

    public static String hex(byte[] b, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(b.length, max); i++) sb.append(String.format("%02x", b[i]));
        if (b.length > max) sb.append("...");
        return sb.toString();
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }
}
