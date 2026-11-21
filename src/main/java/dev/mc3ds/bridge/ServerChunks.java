package dev.mc3ds.bridge;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Plugin mode only: read terrain straight from the Paper server (Bukkit API, via reflection so the jar still runs
 * standalone) instead of waiting for it over the Java connection. Used when the server/ViaBackwards doesn't send
 * chunks to the bridge's connection, which leaves the 3DS in the void.
 *
 * Blocks are converted to 1.12 ids with CraftBukkit's own legacy table (CraftMagicNumbers.getMaterial(BlockState)),
 * then through the normal Java 1.12 -> PE mapping.
 */
final class ServerChunks {
    private static volatile boolean broken;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "mc3ds-terrain"); t.setDaemon(true); return t;
    });

    // reflection handles, resolved once
    private static Method getPlayer, getWorld, getChunkAtAsync, isChunkLoaded, getChunkAt, getSnapshot;
    private static Method snapBlockData, snapSky, snapEmitted;
    private static Method bdGetState, bdGetMaterial, matIsSolid, matIsAir, cmnGetMaterial, mdGetItemType, mdGetData, matGetId;
    private static Method schedRunTask, getScheduler;
    private static final Map<Object, Integer> legacyCache = new HashMap<>();

    static boolean available() { return SkinUpload.plugin != null && !broken; }

    private static synchronized void init() throws Exception {
        if (getPlayer != null) return;
        Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
        getPlayer = bukkit.getMethod("getPlayer", UUID.class);
        getScheduler = bukkit.getMethod("getScheduler");
        Class<?> plugin = Class.forName("org.bukkit.plugin.Plugin");
        schedRunTask = Class.forName("org.bukkit.scheduler.BukkitScheduler").getMethod("runTask", plugin, Runnable.class);
        Class<?> entity = Class.forName("org.bukkit.entity.Entity");
        getWorld = entity.getMethod("getWorld");
        Class<?> world = Class.forName("org.bukkit.World");
        try { getChunkAtAsync = world.getMethod("getChunkAtAsync", int.class, int.class); } catch (NoSuchMethodException ignored) { }
        isChunkLoaded = world.getMethod("isChunkLoaded", int.class, int.class);
        getChunkAt = world.getMethod("getChunkAt", int.class, int.class);
        Class<?> chunk = Class.forName("org.bukkit.Chunk");
        getSnapshot = chunk.getMethod("getChunkSnapshot", boolean.class, boolean.class, boolean.class);
        Class<?> snap = Class.forName("org.bukkit.ChunkSnapshot");
        snapBlockData = snap.getMethod("getBlockData", int.class, int.class, int.class);
        snapSky = snap.getMethod("getBlockSkyLight", int.class, int.class, int.class);
        snapEmitted = snap.getMethod("getBlockEmittedLight", int.class, int.class, int.class);
        Class<?> bd = Class.forName("org.bukkit.block.data.BlockData");
        bdGetMaterial = bd.getMethod("getMaterial");
        Class<?> mat = Class.forName("org.bukkit.Material");
        matIsSolid = mat.getMethod("isSolid");
        matIsAir = mat.getMethod("isAir");
        matGetId = mat.getMethod("getId");
        Class<?> md = Class.forName("org.bukkit.material.MaterialData");
        mdGetItemType = md.getMethod("getItemType");
        mdGetData = md.getMethod("getData");
        Object server = bukkit.getMethod("getServer").invoke(null);
        String cb = server.getClass().getPackage().getName();      // org.bukkit.craftbukkit(.vX_Y_RZ)
        Class<?> cmn = Class.forName(cb + ".util.CraftMagicNumbers");
        for (Method m : cmn.getMethods())
            if (m.getName().equals("getMaterial") && m.getParameterCount() == 1 && m.getReturnType() == md
                    && m.getParameterTypes()[0].getSimpleName().equals("BlockState")) cmnGetMaterial = m;
        if (cmnGetMaterial == null) throw new IllegalStateException("CraftMagicNumbers.getMaterial(BlockState) not found");
        Class<?> cbd = Class.forName(cb + ".block.data.CraftBlockData");
        bdGetState = cbd.getMethod("getState");
    }

    /** Fetch a chunk of the given player's world; the callback gets the converted column (or null) on a worker thread. */
    static void fetch(UUID player, int cx, int cz, Consumer<Mapping.PEColumn> done) {
        try {
            init();
        } catch (Throwable e) { fail(e); broken = true; done.accept(null); return; }
        try {
            Object sched = getScheduler.invoke(null);
            schedRunTask.invoke(sched, SkinUpload.plugin, (Runnable) () -> {
                try {
                    Object p = getPlayer.invoke(null, player);
                    if (p == null) { done.accept(null); return; }
                    Object w = getWorld.invoke(p);
                    if ((Boolean) isChunkLoaded.invoke(w, cx, cz) || getChunkAtAsync == null) {
                        snapshotThenConvert(getChunkAt.invoke(w, cx, cz), cx, cz, done);
                    } else {
                        @SuppressWarnings("unchecked")
                        java.util.concurrent.CompletableFuture<Object> f = (java.util.concurrent.CompletableFuture<Object>) getChunkAtAsync.invoke(w, cx, cz);
                        f.thenAccept(c -> snapshotThenConvert(c, cx, cz, done));   // completes on the main thread
                    }
                } catch (Throwable e) { fail(e); done.accept(null); }
            });
        } catch (Throwable e) { fail(e); done.accept(null); }
    }

    private static void snapshotThenConvert(Object chunk, int cx, int cz, Consumer<Mapping.PEColumn> done) {
        try {
            Object snap = getSnapshot.invoke(chunk, false, false, false);
            POOL.execute(() -> {
                try { done.accept(convert(snap, cx, cz)); } catch (Throwable e) { fail(e); done.accept(null); }
            });
        } catch (Throwable e) { fail(e); done.accept(null); }
    }

    private static Mapping.PEColumn convert(Object snap, int cx, int cz) throws Exception {
        Mapping.PEColumn col = new Mapping.PEColumn(cx, cz);
        for (int sy = 0; sy < 16; sy++) {
            Mapping.PESection s = new Mapping.PESection();
            boolean any = false;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int wy = (sy << 4) | y;
                int state = legacy(snapBlockData.invoke(snap, x, wy, z));
                int pi = Mapping.PESection.idx(x, y, z);
                if (state != 0) {
                    int pe = Mapping.block(state);
                    s.set(x, y, z, pe, state);
                    any = true;
                }
                Mapping.PESection.nib(s.sky, pi, (Integer) snapSky.invoke(snap, x, wy, z));
                Mapping.PESection.nib(s.blockLight, pi, (Integer) snapEmitted.invoke(snap, x, wy, z));
            }
            col.sections[sy] = any ? s : null;
        }
        return col;
    }

    /** Modern BlockData -> 1.12 (id << 4 | meta). */
    private static int legacy(Object blockData) throws Exception {
        Integer c;
        synchronized (legacyCache) { c = legacyCache.get(blockData); }
        if (c != null) return c;
        int v;
        Object mat = bdGetMaterial.invoke(blockData);
        if ((Boolean) matIsAir.invoke(mat)) v = 0;
        else {
            Object md = cmnGetMaterial.invoke(null, bdGetState.invoke(blockData));
            int id = (Integer) matGetId.invoke(mdGetItemType.invoke(md));
            int meta = ((Byte) mdGetData.invoke(md)) & 0xF;
            if (id <= 0 || id > 255) v = (Boolean) matIsSolid.invoke(mat) ? (1 << 4) : 0;   // no 1.12 equivalent
            else v = (id << 4) | meta;
        }
        synchronized (legacyCache) { legacyCache.put(blockData, v); }
        return v;
    }

    // ---- server-side player state (plugin mode): what the server really thinks about the 3DS player
    static final class State { boolean sleeping, dead; double x, y, z; int food = -1, level; float exp, saturation; }
    private static Method pFood, pSat, pLevel, pExp;
    private static Method pIsSleeping, pIsDead, pGetLocation, pWakeup, pTeleport, locX, locY, locZ, locSet;

    private static synchronized void initPlayer() throws Exception {
        if (pIsSleeping != null) return;
        init();
        Class<?> player = Class.forName("org.bukkit.entity.Player");
        Class<?> human = Class.forName("org.bukkit.entity.HumanEntity");
        Class<?> entity = Class.forName("org.bukkit.entity.Entity");
        Class<?> loc = Class.forName("org.bukkit.Location");
        pIsSleeping = human.getMethod("isSleeping");
        pWakeup = human.getMethod("wakeup", boolean.class);
        pIsDead = entity.getMethod("isDead");
        pGetLocation = entity.getMethod("getLocation");
        pTeleport = entity.getMethod("teleport", loc);
        locX = loc.getMethod("getX"); locY = loc.getMethod("getY"); locZ = loc.getMethod("getZ");
        locSet = null;
        try { pFood = human.getMethod("getFoodLevel"); pSat = human.getMethod("getSaturation"); pLevel = player.getMethod("getLevel"); pExp = player.getMethod("getExp"); }
        catch (NoSuchMethodException ignored) { }
    }

    /** Read the player's server-side state on the main thread; callback runs there too (keep it short). */
    static void state(UUID uuid, Consumer<State> done) {
        runMain(() -> {
            Object p = getPlayer.invoke(null, uuid);
            if (p == null) return;
            State st = new State();
            st.sleeping = (Boolean) pIsSleeping.invoke(p);
            st.dead = (Boolean) pIsDead.invoke(p);
            Object l = pGetLocation.invoke(p);
            st.x = (Double) locX.invoke(l); st.y = (Double) locY.invoke(l); st.z = (Double) locZ.invoke(l);
            if (pFood != null) {
                st.food = (Integer) pFood.invoke(p); st.saturation = (Float) pSat.invoke(p);
                st.level = (Integer) pLevel.invoke(p); st.exp = (Float) pExp.invoke(p);
            }
            done.accept(st);
        });
    }

    static void wakeUp(UUID uuid) {
        runMain(() -> { Object p = getPlayer.invoke(null, uuid); if (p != null && (Boolean) pIsSleeping.invoke(p)) pWakeup.invoke(p, false); });
    }

    /** Move the server-side player to where the 3DS is (keeps the server's world + current look). */
    static void moveTo(UUID uuid, double x, double y, double z) {
        runMain(() -> {
            Object p = getPlayer.invoke(null, uuid);
            if (p == null) return;
            Object l = pGetLocation.invoke(p);
            Class<?> loc = l.getClass();
            loc.getMethod("setX", double.class).invoke(l, x);
            loc.getMethod("setY", double.class).invoke(l, y);
            loc.getMethod("setZ", double.class).invoke(l, z);
            pTeleport.invoke(p, l);
        });
    }

    interface MainTask { void run() throws Exception; }
    private static void runMain(MainTask t) {
        if (!available()) return;
        try {
            initPlayer();
            schedRunTask.invoke(getScheduler.invoke(null), SkinUpload.plugin, (Runnable) () -> {
                try { t.run(); } catch (Throwable e) { fail(e); }
            });
        } catch (Throwable e) { fail(e); }
    }

    private static volatile long lastFailLog;
    private static void fail(Throwable e) {
        if (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null) e = e.getCause();
        if (System.currentTimeMillis() - lastFailLog > 10000) {
            lastFailLog = System.currentTimeMillis();
            Log.warn("Reading terrain from the server failed: " + e);
        }
    }
}
