package dev.mc3ds.bridge;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows the 3DS player's own skin to Java (and, through Geyser, Bedrock) players.
 * Java clients only display Mojang-signed skins, so the skin is signed through MineSkin
 * (like SkinsRestorer does) and applied with Paper's PlayerProfile API.
 */
final class SkinUpload {
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "skin-upload"); t.setDaemon(true); return t; });
    static volatile Path cacheFile;
    /** the Paper plugin instance (null when running standalone) - kept here so standalone mode never loads Bukkit classes */
    static volatile Object plugin;

    /** @param rgba 64x32 or 64x64 RGBA pixels from the 3DS login */
    static void apply(Config cfg, UUID javaUuid, String name, byte[] rgba, boolean slim) {
        if (!cfg.skinUpload || rgba == null || plugin == null) return;
        if (rgba.length != 64 * 32 * 4 && rgba.length != 64 * 64 * 4) { Log.debug("3DS skin has unusual size " + rgba.length + ", not uploading"); return; }
        POOL.execute(() -> {
            try {
                String hash = "v2-" + sha256(rgba) + (slim ? "-slim" : "");
                BufferedImage raw = image(rgba), fixed = cfg.skinLegacy ? legacyToModern(raw) : raw;
                saveDebug(name, raw, fixed);
                String[] tex = cached(hash);
                if (tex == null) {
                    tex = mineskin(cfg, png(fixed), slim, name);
                    if (tex == null) return;
                    store(hash, tex);
                    Log.info("[" + name + "] uploaded the 3DS skin to MineSkin");
                }
                final String[] t = tex;
                for (int attempt = 0; attempt < 20; attempt++) {   // the player object appears a moment after login
                    if (applyOnServer(javaUuid, t[0], t[1])) { Log.info("[" + name + "] 3DS skin applied for Java/Bedrock players"); return; }
                    Thread.sleep(1000);
                }
                Log.warn("[" + name + "] could not apply the 3DS skin (player not found on the server)");
            } catch (Throwable e) {
                Log.warn("[" + name + "] 3DS skin upload failed: " + e);
            }
        });
    }

    static BufferedImage image(byte[] rgba) {
        int h = rgba.length / (64 * 4);
        BufferedImage img = new BufferedImage(64, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < 64; x++) {
            int i = (y * 64 + x) * 4;
            img.setRGB(x, y, ((rgba[i + 3] & 0xFF) << 24) | ((rgba[i] & 0xFF) << 16) | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF));
        }
        return img;
    }

    /**
     * Build a clean 64x64 Java skin from the classic 64x32 part, exactly like Minecraft converts old skins:
     * left arm/leg are mirrored copies of the right ones, base layer is opaque, unused overlay areas are empty.
     * (The 3DS's lower half can contain leftover data that shows up as red/garbage patches on Java.)
     */
    static BufferedImage legacyToModern(BufferedImage src) {
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 64; x++) out.setRGB(x, y, src.getRGB(x, y));
        int[][] copies = { // x, y, dx, dy, w, h  (copied with horizontal mirroring, as in Minecraft)
                {4, 16, 16, 32, 4, 4}, {8, 16, 16, 32, 4, 4}, {0, 20, 24, 32, 4, 12}, {4, 20, 16, 32, 4, 12},
                {8, 20, 8, 32, 4, 12}, {12, 20, 16, 32, 4, 12}, {44, 16, -8, 32, 4, 4}, {48, 16, -8, 32, 4, 4},
                {40, 20, 0, 32, 4, 12}, {44, 20, -8, 32, 4, 12}, {48, 20, -16, 32, 4, 12}, {52, 20, -8, 32, 4, 12}};
        for (int[] c : copies)
            for (int yy = 0; yy < c[5]; yy++) for (int xx = 0; xx < c[4]; xx++)
                out.setRGB(c[0] + c[2] + (c[4] - 1 - xx), c[1] + c[3] + yy, out.getRGB(c[0] + xx, c[1] + yy));
        opaque(out, 0, 0, 32, 16); opaque(out, 0, 16, 64, 32); opaque(out, 16, 48, 48, 64);
        // Minecraft's "hat hack": a hat layer with no transparent pixel at all is treated as empty
        boolean anyClear = false;
        for (int y = 0; y < 16 && !anyClear; y++) for (int x = 32; x < 64; x++) if ((out.getRGB(x, y) >>> 24) < 128) { anyClear = true; break; }
        if (!anyClear) for (int y = 0; y < 16; y++) for (int x = 32; x < 64; x++) out.setRGB(x, y, 0);
        return out;
    }
    private static void opaque(BufferedImage img, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) img.setRGB(x, y, img.getRGB(x, y) | 0xFF000000);
    }

    private static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(img, "png", o);
        return o.toByteArray();
    }

    /** POST the PNG to MineSkin's /v2/generate and return {value, signature}. */
    static String[] mineskin(Config cfg, byte[] png, boolean slim, String name) throws Exception {
        String boundary = "----3dsbridge" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String[][] fields = {{"variant", slim ? "slim" : "classic"}, {"visibility", "unlisted"}, {"name", "3ds-" + name}};
        for (String[] f : fields)
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f[0] + "\"\r\n\r\n" + f[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(png);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpURLConnection c = (HttpURLConnection) new URL(System.getProperty("mc3ds.mineskin.url", "https://api.mineskin.org/v2/generate")).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(10000); c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "MC3DSBridge/" + Main.VERSION);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (!cfg.mineskinKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + cfg.mineskinKey);
        try (OutputStream os = c.getOutputStream()) { os.write(body.toByteArray()); }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String resp = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
        if (code >= 400) {
            Log.warn("MineSkin answered " + code + (code == 429 ? " (rate limited - add a free API key from account.mineskin.org as mineskin-api-key)" : "")
                    + ": " + (resp.length() > 200 ? resp.substring(0, 200) : resp));
            return null;
        }
        Object root = new Chat.Parser(resp).value();
        Map<?, ?> skin = (Map<?, ?>) ((Map<?, ?>) root).get("skin");
        Map<?, ?> data = (Map<?, ?>) ((Map<?, ?>) skin.get("texture")).get("data");
        return new String[]{String.valueOf(data.get("value")), String.valueOf(data.get("signature"))};
    }

    /**
     * Apply the signed skin the way Floodgate does: set the textures on the player's GameProfile and re-show the
     * player to everyone else. (Paper's setPlayerProfile also "respawns" the player for themselves, which for a
     * 1.12 connection means a dimension bounce - the 3DS ended up in the void.) Reflection: no Paper compile dependency.
     */
    static boolean applyOnServer(UUID uuid, String value, String signature) throws Exception {
        Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
        Object player = bukkit.getMethod("getPlayer", UUID.class).invoke(null, uuid);
        if (player == null) return false;
        Runnable task = () -> {
            try {
                Object gameProfile = player.getClass().getMethod("getProfile").invoke(player);
                Object props = gameProfile.getClass().getMethod("getProperties").invoke(gameProfile);
                Class<?> propCls = Class.forName("com.mojang.authlib.properties.Property");
                Object prop = propCls.getConstructor(String.class, String.class, String.class).newInstance("textures", value, signature);
                Class<?> multimap = Class.forName("com.google.common.collect.Multimap");
                multimap.getMethod("removeAll", Object.class).invoke(props, "textures");
                multimap.getMethod("put", Object.class, Object.class).invoke(props, "textures", prop);
                Class<?> pluginCls = Class.forName("org.bukkit.plugin.Plugin");
                Class<?> playerCls = Class.forName("org.bukkit.entity.Player");
                java.util.Collection<?> online = (java.util.Collection<?>) bukkit.getMethod("getOnlinePlayers").invoke(null);
                for (Object other : online) {
                    if (other == player || uuid.equals(other.getClass().getMethod("getUniqueId").invoke(other))) continue;
                    playerCls.getMethod("hidePlayer", pluginCls, playerCls).invoke(other, plugin, player);
                    playerCls.getMethod("showPlayer", pluginCls, playerCls).invoke(other, plugin, player);
                }
            } catch (Throwable e) {
                Log.warn("Applying the 3DS skin failed: " + e);
            }
        };
        Object scheduler = bukkit.getMethod("getScheduler").invoke(null);
        Class<?> pluginCls = Class.forName("org.bukkit.plugin.Plugin");
        scheduler.getClass().getMethod("runTask", pluginCls, Runnable.class).invoke(scheduler, plugin, task);
        return true;
    }

    /** keep the received + cleaned skin as PNGs so problems can be checked (plugins/MC3DSBridge/skins/) */
    private static void saveDebug(String name, BufferedImage raw, BufferedImage fixed) {
        try {
            if (cacheFile == null) return;
            Path dir = cacheFile.getParent().resolve("skins");
            Files.createDirectories(dir);
            ImageIO.write(raw, "png", dir.resolve(name + "-from-3ds.png").toFile());
            ImageIO.write(fixed, "png", dir.resolve(name + "-sent-to-java.png").toFile());
        } catch (Exception ignored) { }
    }

    // ---- small cache so the same skin isn't uploaded again
    private static synchronized String[] cached(String hash) {
        try {
            if (cacheFile == null || !Files.exists(cacheFile)) return null;
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(cacheFile)) { p.load(in); }
            String v = p.getProperty(hash);
            if (v == null) return null;
            int i = v.indexOf('|');
            return i < 0 ? null : new String[]{v.substring(0, i), v.substring(i + 1)};
        } catch (Exception e) { return null; }
    }
    private static synchronized void store(String hash, String[] tex) {
        try {
            if (cacheFile == null) return;
            Properties p = new Properties();
            if (Files.exists(cacheFile)) try (InputStream in = Files.newInputStream(cacheFile)) { p.load(in); }
            p.setProperty(hash, tex[0] + "|" + tex[1]);
            try (OutputStream o = Files.newOutputStream(cacheFile)) { p.store(o, "MineSkin-signed 3DS skins"); }
        } catch (Exception ignored) { }
    }
    private static String sha256(byte[] b) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) sb.append(String.format("%02x", x));
        return sb.toString();
    }
    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] t = new byte[8192]; int n;
        while ((n = in.read(t)) > 0) o.write(t, 0, n);
        return o.toByteArray();
    }
}
