package dev.mc3ds.bridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class Config {
    public String bindHost = "0.0.0.0";
    public int bindPort = 19133;
    public String motd = "3DS Bridge";
    public String javaHost = "127.0.0.1";
    public int javaPort = 25565;
    /** Hostname written in the Java handshake (some proxies/plugins care). Empty = javaHost. */
    public String javaHandshakeHost = "";
    /** Path to Floodgate's key.pem. Empty = connect as an offline-mode player. */
    public String floodgateKey = "key.pem";
    public int chunkRadius = 4;
    public int maxPlayers = 20;
    public boolean debug = false;
    public float speedMultiplier = 1.0f;
    public boolean skinUpload = true;
    public String mineskinKey = "";
    public boolean skinLegacy = true;

    static final String TEMPLATE =
            "# ---------------------------------------------------------------\n" +
            "#  MC3DS Bridge - lets Minecraft 3DS (PE protocol 113) players join\n" +
            "#  a Java server. The Java server needs ViaVersion + ViaBackwards.\n" +
            "# ---------------------------------------------------------------\n" +
            "\n" +
            "# UDP address/port that 3DS players connect to\n" +
            "bind-host=0.0.0.0\n" +
            "bind-port=19133\n" +
            "\n" +
            "# Name shown in the 3DS server list\n" +
            "motd=3DS Bridge\n" +
            "\n" +
            "# Your Java server (on the same PebbleHost node use 127.0.0.1 and the server's port)\n" +
            "java-host=127.0.0.1\n" +
            "java-port=25565\n" +
            "# Hostname sent in the handshake; leave empty to use java-host\n" +
            "java-handshake-host=\n" +
            "\n" +
            "# Floodgate key, relative to this file (plugin: ../floodgate/key.pem;\n" +
            "# standalone: copy plugins/floodgate/key.pem next to the jar).\n" +
            "# Needed if your server is online-mode. Leave empty for offline-mode servers.\n" +
            "floodgate-key=key.pem\n" +
            "\n" +
            "# Chunk radius sent to the 3DS (keep small - the 3DS has little memory)\n" +
            "chunk-radius=4\n" +
            "max-players=20\n" +
            "\n" +
            "# Walking speed on the 3DS (1.0 = normal). Lower it if the 3DS feels too fast or the\n" +
            "# server keeps pulling you back. You can test values in-game with /3ds speed 0.8\n" +
            "speed-multiplier=1.0\n" +
            "\n" +
            "# Show 3DS players' own skins to Java/Bedrock players (signed through mineskin.org, plugin mode only).\n" +
            "# Works without a key; a free key from account.mineskin.org avoids rate limits.\n" +
            "skin-upload=true\n" +
            "mineskin-api-key=\n" +
            "# Rebuild the skin from its classic 64x32 part (fixes garbage/red patches). false = use the 3DS skin as-is\n" +
            "skin-classic-fix=true\n" +
            "\n" +
            "# Print every packet - turn on if something breaks and send the log\n" +
            "debug=false\n";

    public static Config load(Path file, int defaultJavaPort, String defaultKey) throws IOException {
        if (!Files.exists(file)) {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            String t = TEMPLATE.replace("java-port=25565", "java-port=" + defaultJavaPort)
                    .replace("floodgate-key=key.pem", "floodgate-key=" + defaultKey);
            Files.write(file, t.getBytes(StandardCharsets.UTF_8));
            Log.info("Created default config " + file.toAbsolutePath());
        }
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) { p.load(r); }
        Config c = new Config();
        c.bindHost = p.getProperty("bind-host", c.bindHost).trim();
        c.bindPort = Integer.parseInt(p.getProperty("bind-port", "" + c.bindPort).trim());
        c.motd = p.getProperty("motd", c.motd).trim();
        c.javaHost = p.getProperty("java-host", c.javaHost).trim();
        c.javaPort = Integer.parseInt(p.getProperty("java-port", "" + defaultJavaPort).trim());
        c.javaHandshakeHost = p.getProperty("java-handshake-host", "").trim();
        c.floodgateKey = p.getProperty("floodgate-key", c.floodgateKey).trim();
        c.chunkRadius = Math.max(2, Math.min(12, Integer.parseInt(p.getProperty("chunk-radius", "" + c.chunkRadius).trim())));
        c.maxPlayers = Integer.parseInt(p.getProperty("max-players", "" + c.maxPlayers).trim());
        c.debug = Boolean.parseBoolean(p.getProperty("debug", "false").trim());
        c.skinUpload = Boolean.parseBoolean(p.getProperty("skin-upload", "true").trim());
        c.mineskinKey = p.getProperty("mineskin-api-key", "").trim();
        c.skinLegacy = Boolean.parseBoolean(p.getProperty("skin-classic-fix", "true").trim());
        c.speedMultiplier = Math.max(0.3f, Math.min(1.5f, Float.parseFloat(p.getProperty("speed-multiplier", "1.0").trim())));
        // allow overriding the port with the PebbleHost-style SERVER_PORT env var
        String envPort = System.getenv("SERVER_PORT");
        if (envPort != null && p.getProperty("bind-port-from-env", "false").trim().equalsIgnoreCase("true")) {
            c.bindPort = Integer.parseInt(envPort.trim());
        }
        return c;
    }

    public static Path dir() {
        return Paths.get(".").toAbsolutePath().normalize();
    }
}
