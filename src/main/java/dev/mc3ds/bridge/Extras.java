package dev.mc3ds.bridge;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Tables taken from Minecraft 1.12 data (PrismarineJS minecraft-data): crafting recipes and sound ids. */
final class Extras {
    // ------------------------------------------------------------------ recipes
    static final class Recipe {
        boolean shaped; int w, h;
        int[][] in;          // {javaId, meta (-1 = any)}; javaId 0 = empty cell
        int outId, outMeta, outCount;
        UUID uuid;
    }
    static final List<Recipe> RECIPES = new ArrayList<>();
    static final Map<UUID, Recipe> RECIPE_BY_ID = new HashMap<>();
    private static byte[] craftingData;

    // ------------------------------------------------------------------ sounds: java 1.12 sound id -> {peEvent, kind, extra}
    static final Map<Integer, int[]> SOUNDS = new HashMap<>();

    static {
        for (String line : lines("/recipes_112.txt")) {
            try {
                String[] p = line.split(" ");
                Recipe r = new Recipe();
                String cells, out;
                if (p[0].equals("S")) { r.shaped = true; r.w = Integer.parseInt(p[1]); r.h = Integer.parseInt(p[2]); cells = p[3]; out = p[4]; }
                else { cells = p[1]; out = p[2]; }
                String[] c = cells.split(",");
                r.in = new int[c.length][];
                for (int i = 0; i < c.length; i++) { String[] q = c[i].split(":"); r.in[i] = new int[]{Integer.parseInt(q[0]), Integer.parseInt(q[1])}; }
                String[] o = out.split(":");
                r.outId = Integer.parseInt(o[0]); r.outMeta = Integer.parseInt(o[1]); r.outCount = Integer.parseInt(o[2]);
                r.uuid = UUID.nameUUIDFromBytes(line.getBytes(StandardCharsets.UTF_8));
                RECIPES.add(r); RECIPE_BY_ID.put(r.uuid, r);
            } catch (Exception ignored) { }
        }
        for (String line : lines("/sounds_112.txt")) {
            String[] p = line.split(" ");
            if (p.length < 4) continue;
            SOUNDS.put(Integer.parseInt(p[0]), new int[]{Integer.parseInt(p[1]), p[2].charAt(0), Integer.parseInt(p[3])});
        }
    }

    private static List<String> lines(String res) {
        List<String> out = new ArrayList<>();
        try (InputStream in = Extras.class.getResourceAsStream(res)) {
            if (in == null) return out;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String l;
            while ((l = r.readLine()) != null) { l = l.trim(); if (!l.isEmpty() && !l.startsWith("#")) out.add(l); }
        } catch (Exception ignored) { }
        return out;
    }

    /** PE 1.1 CraftingData (0x35): every 1.12 recipe the 3DS can show. */
    static synchronized byte[] craftingData() {
        if (craftingData != null) return craftingData;
        Buf body = new Buf();
        int n = 0;
        for (Recipe r : RECIPES) {
            int[] out = Mapping.item(r.outId, r.outMeta);
            if (out == null) continue;
            Buf b = new Buf();
            boolean ok = true;
            if (r.shaped) {
                b.varint(1).varint(r.w).varint(r.h);
                for (int[] c : r.in) ok &= ingredient(b, c);
            } else {
                b.varint(0).uvarint(r.in.length);
                for (int[] c : r.in) ok &= ingredient(b, c);
            }
            if (!ok) continue;
            b.uvarint(1);
            b.varint(out[0]).varint(((out[1] & 0x7FFF) << 8) | Math.min(255, r.outCount)).leShort(0).varint(0).varint(0);
            b.peUUID(r.uuid);
            body.put(b.data, 0, b.len);
            n++;
        }
        Buf pk = new Buf().put(0x35).uvarint(n);
        pk.put(body.data, 0, body.len);
        pk.bool(true);   // clean recipes
        craftingData = pk.toArray();
        Log.info("Prepared " + n + " crafting recipes for 3DS players");
        return craftingData;
    }

    private static boolean ingredient(Buf b, int[] c) {
        if (c[0] == 0) { b.varint(0); return true; }
        int[] pe = Mapping.item(c[0], Math.max(0, c[1]));
        if (pe == null) return false;
        int meta = c[1] < 0 ? 0x7FFF : pe[1];
        b.varint(pe[0]).varint(((meta & 0x7FFF) << 8) | 1).leShort(0).varint(0).varint(0);
        return true;
    }
}
