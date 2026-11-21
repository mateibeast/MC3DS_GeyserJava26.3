package dev.mc3ds.bridge;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Downloads Java player skins (from the "textures" profile property) and converts them to PE RGBA data. */
final class Skins {
    static final class Skin { final byte[] rgba; final boolean slim; Skin(byte[] r, boolean s) { rgba = r; slim = s; } }

    private static final Map<String, Skin> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> FAILED = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "skin-download"); t.setDaemon(true); return t;
    });

    /** Parsed info from a textures property: {url, slim?} or null. */
    static String[] parse(String texturesBase64) {
        try {
            String json = new String(Base64.getDecoder().decode(texturesBase64), StandardCharsets.UTF_8);
            Object root = new Chat.Parser(json).value();
            @SuppressWarnings("unchecked") Map<String, Object> tex = (Map<String, Object>) ((Map<String, Object>) root).get("textures");
            if (tex == null) return null;
            @SuppressWarnings("unchecked") Map<String, Object> skin = (Map<String, Object>) tex.get("SKIN");
            if (skin == null || skin.get("url") == null) return null;
            boolean slim = false;
            Object meta = skin.get("metadata");
            if (meta instanceof Map) slim = "slim".equals(((Map<?, ?>) meta).get("model"));
            return new String[]{String.valueOf(skin.get("url")).replace("http://textures.minecraft.net", "https://textures.minecraft.net"), slim ? "slim" : ""};
        } catch (Exception e) {
            Log.debug("Bad textures property: " + e);
            return null;
        }
    }

    static Skin cached(String url) { return url == null ? null : CACHE.get(url); }

    /** Fetch asynchronously; callback runs on a download thread when the skin becomes available. */
    static void fetch(String url, boolean slim, Consumer<Skin> done) {
        if (url == null || FAILED.containsKey(url)) return;
        Skin c = CACHE.get(url);
        if (c != null) { done.accept(c); return; }
        POOL.execute(() -> {
            try {
                Skin s = CACHE.get(url);
                if (s == null) {
                    HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                    con.setConnectTimeout(5000); con.setReadTimeout(5000);
                    con.setRequestProperty("User-Agent", "MC3DSBridge");
                    BufferedImage img;
                    try (InputStream in = con.getInputStream()) { img = ImageIO.read(in); }
                    if (img == null || img.getWidth() != 64 || (img.getHeight() != 64 && img.getHeight() != 32))
                        throw new IllegalStateException("unexpected skin size " + (img == null ? "?" : img.getWidth() + "x" + img.getHeight()));
                    s = new Skin(toRgba(img), slim);
                    CACHE.put(url, s);
                }
                done.accept(s);
            } catch (Throwable t) {
                FAILED.put(url, true);
                Log.debug("Skin download failed for " + url + ": " + t);
            }
        });
    }

    static byte[] toRgba(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        byte[] out = new byte[w * h * 4];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int argb = img.getRGB(x, y), i = (y * w + x) * 4;
            out[i] = (byte) (argb >> 16); out[i + 1] = (byte) (argb >> 8); out[i + 2] = (byte) argb; out[i + 3] = (byte) (argb >>> 24);
        }
        return out;
    }
}
