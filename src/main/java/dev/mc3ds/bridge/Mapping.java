package dev.mc3ds.bridge;

import java.util.HashMap;
import java.util.Map;

/** Java 1.12 <-> Pocket Edition 1.1 id mappings (blocks, items, mobs). */
public final class Mapping {

    /** javaState (id<<4|meta) -> peState (id<<4|meta) */
    private static final int[] BLOCK = new int[4096];
    /** peState -> javaState (best effort, for items the 3DS holds) */
    private static final int[] BLOCK_REVERSE = new int[4096];

    static {
        for (int s = 0; s < 4096; s++) BLOCK[s] = s;
        // id-only remaps (meta kept)
        remapId(95, 241);   // stained glass
        remapId(125, 157);  // double wooden slab
        remapId(126, 158);  // wooden slab
        remapId(157, 126);  // activator rail
        remapId(158, 125);  // dropper
        remapId(198, 208);  // end rod
        remapId(199, 240);  // chorus plant
        remapId(207, 244);  // beetroot
        remapId(208, 198);  // grass path
        remapId(210, 188);  // repeating command block
        remapId(211, 189);  // chain command block
        remapId(212, 207);  // frosted ice
        remapId(218, 251);  // observer
        remapId(251, 236);  // concrete
        remapId(252, 237);  // concrete powder
        remapId(255, 1);    // structure block (PE 1.1 has none)
        remapId(84, 25);    // jukebox -> note block (PE 1.1 has no jukebox)
        remapId(176, 0); remapId(177, 0); // banners (not in PE 1.1)
        remapId(217, 0);    // structure void
        remapId(166, 95);   // barrier -> invisible bedrock
        remapId(36, 0);     // piston extension -> air
        remapId(253, 0); remapId(254, 0);
        // fences: spruce..dark oak fence -> fence:1..5
        int[] fenceMeta = {1, 2, 3, 5, 4}; // Java 188 spruce,189 birch,190 jungle,191 dark oak,192 acacia
        for (int i = 0; i < 5; i++) for (int m = 0; m < 16; m++) BLOCK[((188 + i) << 4) | m] = (85 << 4) | fenceMeta[i];
        // trapdoors: Java bit2=open bit3=top, dir N,S,W,E ; PE bit2=top bit3=open, dir 3,2,1,0
        int[] trapDir = {3, 2, 1, 0};
        for (int id : new int[]{96, 167}) for (int m = 0; m < 16; m++)
            BLOCK[(id << 4) | m] = (id << 4) | trapDir[m & 3] | ((m & 4) != 0 ? 8 : 0) | ((m & 8) != 0 ? 4 : 0);
        // buttons: Java 0 down,1 east,2 west,3 south,4 north,5 up ; PE uses face index 0 down,1 up,2 north,3 south,4 west,5 east
        int[] btn = {0, 5, 4, 3, 2, 1, 0, 0};
        for (int id : new int[]{77, 143}) for (int m = 0; m < 16; m++) BLOCK[(id << 4) | m] = (id << 4) | btn[m & 7] | (m & 8);
        // purpur
        for (int m = 0; m < 16; m++) {
            BLOCK[(202 << 4) | m] = (201 << 4) | 2 | (m & 0xC);          // purpur pillar (keep axis bits)
            BLOCK[(204 << 4) | m] = (181 << 4) | 1;                        // purpur double slab
            BLOCK[(205 << 4) | m] = (182 << 4) | 1 | (m & 8);              // purpur slab (top bit)
            BLOCK[(3 << 4) | 2] = (243 << 4);                              // podzol
        }
        // shulker boxes 219..234 -> 218:color
        for (int i = 0; i < 16; i++) for (int m = 0; m < 16; m++) BLOCK[((219 + i) << 4) | m] = (218 << 4) | i;
        // glazed terracotta 235..250 (Java order white..black) -> PE ids
        int[] glazed = {220, 221, 222, 223, 224, 225, 226, 227, 228, 229, 219, 231, 232, 233, 234, 235};
        int[] glazedFace = {3, 4, 2, 5}; // Java S,W,N,E -> PE facing
        for (int i = 0; i < 16; i++) for (int m = 0; m < 16; m++) BLOCK[((235 + i) << 4) | m] = (glazed[i] << 4) | glazedFace[m & 3];
        // stone slabs: Java 6=nether brick 7=quartz, PE 6=quartz 7=nether brick
        for (int id : new int[]{43, 44}) {
            for (int top : new int[]{0, 8}) {
                BLOCK[(id << 4) | 6 | top] = (id << 4) | 7 | top;
                BLOCK[(id << 4) | 7 | top] = (id << 4) | 6 | top;
            }
        }
        java.util.Set<Integer> peBlocks = loadIds("/pe_blocks.txt");
        if (!peBlocks.isEmpty()) for (int st = 0; st < 4096; st++) if (!peBlocks.contains(BLOCK[st] >> 4)) BLOCK[st] = 0;
        // reverse
        for (int s = 0; s < 4096; s++) BLOCK_REVERSE[s] = s;
        for (int s = 4095; s >= 0; s--) if (BLOCK[s] != s) BLOCK_REVERSE[BLOCK[s]] = s;
    }

    static java.util.Set<Integer> loadIds(String res) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        try (java.io.InputStream in = Mapping.class.getResourceAsStream(res)) {
            if (in == null) return out;
            java.util.Scanner sc = new java.util.Scanner(in);
            while (sc.hasNextInt()) out.add(sc.nextInt());
        } catch (Exception ignored) { }
        return out;
    }

    private static void remapId(int javaId, int peId) {
        for (int m = 0; m < 16; m++) BLOCK[(javaId << 4) | m] = (peId << 4) | m;
    }

    public static int block(int javaState) { return javaState >= 0 && javaState < 4096 ? BLOCK[javaState] : 0; }
    public static int blockToJava(int peState) { return peState >= 0 && peState < 4096 ? BLOCK_REVERSE[peState] : 0; }

    // ------------------------------------------------------------------ items
    private static final Map<Integer, int[]> ITEM = new HashMap<>();
    private static final java.util.Set<Integer> PE_ITEMS = loadIds("/pe_items.txt");
    static {
        itemMap(434, 457); itemMap(435, 458); itemMap(436, 459); itemMap(443, 444);
        itemMap(449, 450); itemMap(450, 445); itemMap(422, 443);
        for (int i = 0; i < 5; i++) ITEM.put(444 + i, new int[]{333, i + 1}); // wood boats
        ITEM.put(326, new int[]{325, 8});   // water bucket = bucket:8 in PE
        ITEM.put(327, new int[]{325, 10});  // lava bucket = bucket:10
        ITEM.put(335, new int[]{325, 1});   // milk bucket = bucket:1
        ITEM.put(439, new int[]{262, 0});   // spectral arrow -> arrow
        ITEM.put(440, new int[]{262, 0});   // tipped arrow -> arrow
        ITEM.put(386, new int[]{340, 0});   // book & quill -> book
        ITEM.put(387, new int[]{340, 0});   // written book -> book
    }
    private static void itemMap(int j, int p) { ITEM.put(j, new int[]{p, -1}); }

    /** @return {peId, peMeta} or null if it shouldn't be shown */
    public static int[] item(int javaId, int damage) {
        if (javaId <= 0) return null;
        if (javaId < 256) {
            int st = block((javaId << 4) | (damage & 0xF));
            if (st >> 4 == 0) return null;
            return new int[]{st >> 4, st & 0xF};
        }
        int[] m = ITEM.get(javaId);
        int[] r = m != null ? new int[]{m[0], m[1] < 0 ? damage : m[1]} : new int[]{javaId, damage};
        if (r[0] > 511 || (!PE_ITEMS.isEmpty() && r[0] >= 256 && !PE_ITEMS.contains(r[0]))) return null; // PE 1.1 doesn't have it
        return r;
    }

    /** PE item -> Java {id, damage} (best effort; the server's own items are preferred where possible) */
    public static int[] itemToJava(int peId, int meta) {
        if (peId < 256) { int js = blockToJava((peId << 4) | (meta & 0xF)); return new int[]{js >> 4, js & 0xF}; }
        for (Map.Entry<Integer, int[]> e : ITEM.entrySet()) {
            int[] m = e.getValue();
            if (m[0] == peId && (m[1] < 0 || m[1] == meta)) return new int[]{e.getKey(), m[1] < 0 ? meta : 0};
        }
        return new int[]{peId, meta};
    }

    // ------------------------------------------------------------------ mobs (Java 1.12 type -> PE network id)
    private static final Map<Integer, Integer> MOB = new HashMap<>();
    static {
        int[][] m = {
                {50, 33}, {51, 34}, {52, 35}, {53, 32}, {54, 32}, {55, 37}, {56, 41}, {57, 36}, {58, 38}, {59, 40},
                {60, 39}, {61, 43}, {62, 42}, {63, 53}, {64, 52}, {65, 19}, {66, 45}, {67, 55}, {68, 49}, {69, 54},
                {90, 12}, {91, 13}, {92, 11}, {93, 10}, {94, 17}, {95, 14}, {96, 16}, {97, 21}, {98, 22}, {99, 20},
                {100, 23}, {101, 18}, {102, 28}, {103, 29}, {105, 10}, {120, 15},
                {4, 50}, {5, 48}, {6, 46}, {23, 47}, {27, 44}, {28, 26}, {29, 27}, {31, 24}, {32, 25},
                {34, 104}, {35, 105}, {36, 57}, {37, 104}
        };
        for (int[] e : m) MOB.put(e[0], e[1]);
    }
    public static int mob(int javaType) { return MOB.getOrDefault(javaType, -1); }

    // ------------------------------------------------------------------ chunks
    /** One 16x16x16 section in PE layout (XZY index). */
    public static final class PESection {
        public final byte[] ids = new byte[4096];
        public final byte[] data = new byte[2048];
        public final byte[] sky = new byte[2048];
        public final byte[] blockLight = new byte[2048];
        public final short[] javaState = new short[4096];
        boolean empty = true;

        static int idx(int x, int y, int z) { return (x << 8) | (z << 4) | y; }

        public void set(int x, int y, int z, int peState, int jState) {
            int i = idx(x, y, z);
            javaState[i] = (short) jState;
            ids[i] = (byte) (peState >> 4);
            int meta = peState & 0xF;
            int b = data[i >> 1] & 0xFF;
            data[i >> 1] = (byte) ((i & 1) == 0 ? (b & 0xF0) | meta : (b & 0x0F) | (meta << 4));
            if (peState >> 4 != 0) empty = false;
        }
        public int getId(int x, int y, int z) { return ids[idx(x, y, z)] & 0xFF; }
        static void nib(byte[] arr, int i, int v) {
            int b = arr[i >> 1] & 0xFF;
            arr[i >> 1] = (byte) ((i & 1) == 0 ? (b & 0xF0) | v : (b & 0x0F) | (v << 4));
        }
    }

    public static final class PEColumn {
        public final int x, z;
        public final PESection[] sections = new PESection[16];
        public byte[] biomes = new byte[256];
        /** Java block entity data by world position key */
        public final java.util.Map<Long, java.util.Map<String, Object>> javaTiles = new java.util.HashMap<>();
        public PEColumn(int x, int z) { this.x = x; this.z = z; java.util.Arrays.fill(biomes, (byte) 1); }

        public int getBlockId(int bx, int by, int bz) {
            if (by < 0 || by > 255) return 0;
            PESection s = sections[by >> 4];
            return s == null ? 0 : s.getId(bx & 15, by & 15, bz & 15);
        }

        public boolean isEmpty() {
            for (PESection sec : sections) if (sec != null && !sec.empty) return false;
            return true;
        }

        public void setBlock(int bx, int by, int bz, int peState, int jState) {
            if (by < 0 || by > 255) return;
            int sy = by >> 4;
            if (sections[sy] == null) {
                if (peState >> 4 == 0) return;
                sections[sy] = new PESection();
                java.util.Arrays.fill(sections[sy].sky, (byte) 0xFF);
            }
            sections[sy].set(bx & 15, by & 15, bz & 15, peState, jState);
        }

        public int getJavaState(int bx, int by, int bz) {
            if (by < 0 || by > 255) return 0;
            PESection s = sections[by >> 4];
            return s == null ? 0 : s.javaState[PESection.idx(bx & 15, by & 15, bz & 15)] & 0xFFFF;
        }

        /** Serialize to the FullChunkData payload format of PE 1.1. */
        public byte[] serialize() {
            int count = 0;
            for (int i = 15; i >= 0; i--) if (sections[i] != null && !sections[i].empty) { count = i + 1; break; }
            Buf b = new Buf(count * 10241 + 1024);
            b.put(count);
            for (int i = 0; i < count; i++) {
                PESection s = sections[i];
                if (s == null) {
                    b.put(0); b.put(new byte[4096]); b.put(new byte[2048]);
                    byte[] sky = new byte[2048]; java.util.Arrays.fill(sky, (byte) 0xFF);
                    b.put(sky); b.put(new byte[2048]);
                } else {
                    b.put(0).put(s.ids).put(s.data).put(s.sky).put(s.blockLight);
                }
            }
            // height map (z<<4|x)
            for (int zz = 0; zz < 16; zz++) for (int xx = 0; xx < 16; xx++) {
                int h = 0;
                outer:
                for (int sy = count - 1; sy >= 0; sy--) {
                    PESection s = sections[sy];
                    if (s == null) continue;
                    for (int yy = 15; yy >= 0; yy--) if (s.getId(xx, yy, zz) != 0) { h = sy * 16 + yy + 1; break outer; }
                }
                b.leShort(h);
            }
            b.put(biomes);
            b.put(0);       // border blocks
            b.varint(0);    // extra data count
            // block entities (beds, chests, signs... PE needs these to draw the blocks)
            for (int sy = 0; sy < count; sy++) {
                PESection s = sections[sy];
                if (s == null) continue;
                for (int i = 0; i < 4096; i++) {
                    int id = s.ids[i] & 0xFF;
                    if (!hasTile(id)) continue;
                    int lx = i >> 8, lz = (i >> 4) & 15, ly = i & 15;
                    int wx = x * 16 + lx, wy = sy * 16 + ly, wz = z * 16 + lz;
                    byte[] t = tileNBT(id, wx, wy, wz, javaTiles.get(posKey(wx, wy, wz)));
                    if (t != null) b.put(t);
                }
            }
            return b.toArray();
        }
    }

    public static long posKey(int x, int y, int z) { return Buf.jPos(x, y, z); }

    public static boolean hasTile(int peId) {
        return peId == 26 || peId == 54 || peId == 146 || peId == 130 || peId == 63 || peId == 68
                || peId == 61 || peId == 62 || peId == 116 || peId == 144 || peId == 140 || peId == 117 || peId == 154
                || peId == 23 || peId == 125 || peId == 118 || peId == 138 || peId == 52 || peId == 25;
    }

    /** PE network-NBT block entity for a block, using Java block entity data when available. */
    public static byte[] tileNBT(int peId, int x, int y, int z, java.util.Map<String, Object> jt) {
        String id;
        switch (peId) {
            case 26: id = "Bed"; break;
            case 54: case 146: id = "Chest"; break;
            case 130: id = "EnderChest"; break;
            case 63: case 68: id = "Sign"; break;
            case 61: case 62: id = "Furnace"; break;
            case 116: id = "EnchantTable"; break;
            case 144: id = "Skull"; break;
            case 140: id = "FlowerPot"; break;
            case 117: id = "BrewingStand"; break;
            case 154: id = "Hopper"; break;
            case 23: id = "Dispenser"; break;
            case 125: id = "Dropper"; break;
            case 118: id = "Cauldron"; break;
            case 138: id = "Beacon"; break;
            case 52: id = "MobSpawner"; break;
            case 25: id = "Music"; break;
            default: return null;
        }
        NBT.PEWriter w = new NBT.PEWriter().string("id", id).integer("x", x).integer("y", y).integer("z", z);
        if (peId == 26) {
            int color = 14;
            if (jt != null && jt.get("color") instanceof Integer) color = (Integer) jt.get("color");
            w.byteTag("color", color & 15);
        } else if (peId == 63 || peId == 68) {
            for (int i = 1; i <= 4; i++) {
                Object t = jt == null ? null : jt.get("Text" + i);
                w.string("Text" + i, t == null ? "" : Chat.toText(String.valueOf(t)));
            }
        } else if (peId == 61 || peId == 62) {
            w.shortTag("BurnTime", 0).shortTag("CookTime", 0);
        } else if (peId == 144) {   // mob heads: type (skeleton, wither, zombie, player, creeper, dragon) + rotation
            int type = jt != null && jt.get("SkullType") instanceof Integer ? (Integer) jt.get("SkullType") : 0;
            int rot = jt != null && jt.get("Rot") instanceof Integer ? (Integer) jt.get("Rot") : 0;
            w.byteTag("SkullType", type).byteTag("Rot", rot);
        } else if (peId == 140) {
            w.shortTag("item", 0).integer("mData", 0);
        } else if (peId == 52) {
            w.integer("EntityId", 0);
        }
        return w.end();
    }

    /**
     * Read a Java 1.12 chunk data packet body (after x, z, full) into a PE column.
     * @param existing column to merge into for non-full updates (may be null)
     */
    public static PEColumn readJavaChunk(Buf in, int cx, int cz, boolean full, boolean hasSky, PEColumn existing) throws java.io.IOException {
        int mask = in.jvarint();
        int size = in.jvarint();
        Buf d = new Buf(in.data, in.pos, size);
        in.pos += size;
        PEColumn col = (existing != null && !full) ? existing : new PEColumn(cx, cz);
        for (int sy = 0; sy < 16; sy++) {
            if ((mask & (1 << sy)) == 0) {
                if (full) col.sections[sy] = null;
                continue;
            }
            int bits = d.u8();
            int paletteLen = d.jvarint();
            int[] palette = new int[paletteLen];
            for (int i = 0; i < paletteLen; i++) palette[i] = d.jvarint();
            int longCount = d.jvarint();
            long[] longs = new long[longCount];
            for (int i = 0; i < longCount; i++) longs[i] = d.beLong();
            byte[] jBlockLight = d.bytes(2048);
            byte[] jSky = hasSky ? d.bytes(2048) : null;

            PESection s = new PESection();
            long maxVal = (1L << bits) - 1;
            for (int i = 0; i < 4096; i++) {
                int v;
                if (bits == 0 || longCount == 0) v = 0;
                else {
                    int bitIndex = i * bits;
                    int start = bitIndex / 64, end = ((i + 1) * bits - 1) / 64, sub = bitIndex % 64;
                    if (start == end) v = (int) (longs[start] >>> sub & maxVal);
                    else v = (int) ((longs[start] >>> sub | longs[end] << (64 - sub)) & maxVal);
                }
                int state = paletteLen > 0 ? (v < paletteLen ? palette[v] : 0) : v;
                // Java index: y<<8 | z<<4 | x
                int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
                int pe = block(state);
                if (pe != 0 || state != 0) s.set(x, y, z, pe, state);
                int pi = PESection.idx(x, y, z);
                int bl = (jBlockLight[i >> 1] >> ((i & 1) << 2)) & 0xF;
                PESection.nib(s.blockLight, pi, bl);
                int sl = jSky == null ? 0 : (jSky[i >> 1] >> ((i & 1) << 2)) & 0xF;
                PESection.nib(s.sky, pi, jSky == null ? 0 : sl);
            }
            col.sections[sy] = s;
        }
        if (full && d.remaining() >= 256) col.biomes = d.bytes(256);
        // block entities follow the data
        try {
            int n = in.jvarint();
            for (int i = 0; i < n && i < 4096; i++) {
                java.util.Map<String, Object> t = NBT.readRoot(in);
                if (t.get("x") instanceof Integer && t.get("y") instanceof Integer && t.get("z") instanceof Integer)
                    col.javaTiles.put(posKey((Integer) t.get("x"), (Integer) t.get("y"), (Integer) t.get("z")), t);
            }
        } catch (Exception e) {
            Log.debug("Could not read block entities in chunk " + cx + "," + cz + ": " + e);
        }
        return col;
    }
}
