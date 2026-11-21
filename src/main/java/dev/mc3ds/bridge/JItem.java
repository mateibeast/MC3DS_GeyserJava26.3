package dev.mc3ds.bridge;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** A Java 1.12 item stack as the server sees it (raw NBT kept so clicks match the server exactly). */
final class JItem {
    final int id, damage;
    int count;
    final byte[] nbt;      // raw NBT root as sent by the server ({0} = none)
    final int efficiency, aqua;

    JItem(int id, int count, int damage, byte[] nbt, int efficiency, int aqua) {
        this.id = id; this.count = count; this.damage = damage; this.nbt = nbt == null ? new byte[]{0} : nbt;
        this.efficiency = efficiency; this.aqua = aqua;
    }

    JItem withCount(int c) { return new JItem(id, c, damage, nbt, efficiency, aqua); }

    boolean sameType(JItem o) { return o != null && id == o.id && damage == o.damage && Arrays.equals(nbt, o.nbt); }

    int maxStack() {
        if ((id >= 256 && id <= 261) || (id >= 267 && id <= 279) || (id >= 283 && id <= 286) || (id >= 290 && id <= 294)
                || (id >= 298 && id <= 317) || id == 261 || id == 346 || id == 359 || id == 398 || id == 326 || id == 327
                || id == 335 || id == 282 || id == 413 || id == 373 || id == 438 || id == 441 || id == 403 || id == 387
                || id == 333 || (id >= 444 && id <= 448) || id == 329 || id == 328 || id == 342 || id == 343 || id == 407
                || id == 408 || id == 422 || id == 443 || id == 449 || (id >= 2256 && id <= 2267) || id == 26 || id == 355)
            return 1;
        if (id == 332 || id == 344 || id == 368 || id == 323 || id == 379 || id == 390) return 16;
        return 64;
    }

    // Java 1.12 enchantment id -> PE 1.1 enchantment id
    private static final Map<Integer, Integer> ENCH = new java.util.HashMap<>();
    static {
        int[][] m = {{0,0},{1,1},{2,2},{3,3},{4,4},{7,5},{5,6},{8,7},{6,8},{16,9},{17,10},{18,11},{19,12},{20,13},{21,14},
                {32,15},{33,16},{34,17},{35,18},{48,19},{49,20},{50,21},{51,22},{61,23},{62,24},{9,25},{70,26},{10,27},{71,28}};
        for (int[] e : m) ENCH.put(e[0], e[1]);
    }
    private byte[] peNbtCache;

    /** The item's enchantments and custom name as PE (little-endian) NBT, or an empty array. */
    byte[] peNbt() {
        if (peNbtCache != null) return peNbtCache;
        peNbtCache = new byte[0];
        if (nbt.length <= 1) return peNbtCache;
        try {
            Map<String, Object> root = NBT.readRoot(new Buf(nbt, 0, nbt.length));
            Object ench = root.containsKey("ench") ? root.get("ench") : root.get("StoredEnchantments");
            String name = null;
            if (root.get("display") instanceof Map) {
                Object n = ((Map<?, ?>) root.get("display")).get("Name");
                if (n != null) name = Chat.forPE(Chat.toText(String.valueOf(n)));
            }
            java.util.List<int[]> list = new java.util.ArrayList<>();
            if (ench instanceof List) for (Object o : (List<?>) ench) {
                if (!(o instanceof Map)) continue;
                Object id = ((Map<?, ?>) o).get("id"), lvl = ((Map<?, ?>) o).get("lvl");
                if (!(id instanceof Integer) || !(lvl instanceof Integer)) continue;
                Integer pe = ENCH.get((Integer) id);
                if (pe != null) list.add(new int[]{pe, (Integer) lvl});
            }
            if (list.isEmpty() && name == null) return peNbtCache;
            Buf b = new Buf();
            b.put(10).leShort(0);
            if (!list.isEmpty()) {
                b.put(9).leShort(4).put("ench".getBytes(java.nio.charset.StandardCharsets.UTF_8)).put(10).leInt(list.size());
                for (int[] e : list) {
                    b.put(2).leShort(2).put("id".getBytes(java.nio.charset.StandardCharsets.UTF_8)).leShort(e[0]);
                    b.put(2).leShort(3).put("lvl".getBytes(java.nio.charset.StandardCharsets.UTF_8)).leShort(e[1]);
                    b.put(0);
                }
            }
            if (name != null) {
                byte[] nb = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                b.put(10).leShort(7).put("display".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                b.put(8).leShort(4).put("Name".getBytes(java.nio.charset.StandardCharsets.UTF_8)).leShort(nb.length).put(nb);
                b.put(0);
            }
            b.put(0);
            peNbtCache = b.toArray();
        } catch (Exception ignored) { }
        return peNbtCache;
    }

    /** PE id/meta this shows as on the 3DS (null = not shown) */
    int[] pe() { return Mapping.item(id, damage); }

    int[] asBreakArray() { return new int[]{id, count, damage, efficiency, aqua}; }

    static JItem read(Buf in) throws IOException {
        int id = (short) in.beShort();
        if (id < 0) return null;
        int count = in.s8();
        int damage = (short) in.beShort();
        int start = in.pos;
        Map<String, Object> tag = NBT.readRoot(in);
        byte[] raw = Arrays.copyOfRange(in.data, start, in.pos);
        int efficiency = 0, aqua = 0;
        Object ench = tag.get("ench");
        if (ench instanceof List) for (Object e : (List<?>) ench) {
            if (!(e instanceof Map)) continue;
            Object eid = ((Map<?, ?>) e).get("id"), lvl = ((Map<?, ?>) e).get("lvl");
            if (!(eid instanceof Integer) || !(lvl instanceof Integer)) continue;
            if ((Integer) eid == 32) efficiency = (Integer) lvl;
            if ((Integer) eid == 6) aqua = (Integer) lvl;
        }
        if (count <= 0) return null;
        return new JItem(id, count, damage, raw, efficiency, aqua);
    }

    static void write(Buf b, JItem it) {
        if (it == null || it.count <= 0) { b.beShort(-1); return; }
        b.beShort(it.id).put(it.count).beShort(it.damage).put(it.nbt);
    }
}
