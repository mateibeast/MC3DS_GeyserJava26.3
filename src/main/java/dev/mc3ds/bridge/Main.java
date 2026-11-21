package dev.mc3ds.bridge;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MC3DS Bridge: lets Minecraft: New Nintendo 3DS Edition (fan update 1.10, PE protocol 113)
 * join a modern Java server. Runs as a standalone jar next to the Java server.
 */
public final class Main implements RakNet.Listener {
    public static final String VERSION = "0.1.26";

    final Config cfg;
    Floodgate floodgate;
    final Map<RakNet.Session, PESession> players = new ConcurrentHashMap<>();

    Main(Config cfg) { this.cfg = cfg; }

    RakNet rak;
    Thread ticker;
    volatile long lastTick = System.currentTimeMillis(), lastTickWarn;

    /** Start the bridge (used by both the standalone jar and the Paper plugin). */
    public static Main launch(Config cfg, java.nio.file.Path dir) throws Exception {
        Log.debug = cfg.debug;
        Log.info("MC3DS Bridge " + VERSION + " - PE 1.1 (protocol 113) -> Java 1.12.2 (needs ViaVersion + ViaBackwards on the server)");
        Main m = new Main(cfg);
        if (!cfg.floodgateKey.isEmpty()) {
            Path key = dir.resolve(cfg.floodgateKey).normalize();
            if (Files.exists(key)) {
                m.floodgate = Floodgate.load(key);
                Log.info("Floodgate key loaded (" + key + ") - 3DS players join as Floodgate players, no Java account needed.");
            } else {
                Log.warn("Floodgate key '" + key + "' not found. Players will join as offline-mode players " +
                        "(only works if the Java server has online-mode=false).");
            }
        }
        m.rak = new RakNet(new InetSocketAddress(cfg.bindHost, cfg.bindPort), m);
        m.rak.start();
        Log.info("Listening for 3DS players on UDP " + cfg.bindHost + ":" + cfg.bindPort + " -> Java server " + cfg.javaHost + ":" + cfg.javaPort);
        m.ticker = new Thread(() -> {
            long next = System.currentTimeMillis();
            while (m.rak.running) {
                m.lastTick = System.currentTimeMillis();
                for (PESession s : m.players.values()) {
                    try { s.tick(); } catch (Throwable t) { Log.err("tick error", t); }
                }
                // fixed 20 ticks per second (never faster - anti-cheats check the movement packet rate)
                next += 50;
                long wait = next - System.currentTimeMillis();
                if (wait < -500) { next = System.currentTimeMillis(); wait = 0; }
                if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
            }
        }, "session-tick");
        m.ticker.setDaemon(true);
        m.ticker.start();
        Thread dog = new Thread(() -> {
            while (m.rak.running) {
                for (PESession s : m.players.values()) {
                    try { s.watchdog(); } catch (Throwable t) { Log.err("watchdog error", t); }
                }
                long lag = System.currentTimeMillis() - m.lastTick;
                if (lag > 8000 && System.currentTimeMillis() - m.lastTickWarn > 10000) {
                    m.lastTickWarn = System.currentTimeMillis();
                    StringBuilder sb = new StringBuilder("WATCHDOG: session tick thread stuck for " + lag / 1000 + "s");
                    for (StackTraceElement e : m.ticker.getStackTrace()) sb.append("\n    at ").append(e);
                    Log.warn(sb.toString());
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "bridge-watchdog");
        dog.setDaemon(true);
        dog.start();
        return m;
    }

    public void stop() {
        for (PESession p : players.values()) p.disconnect("Bridge stopped");
        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        if (rak != null) rak.stop();
        if (ticker != null) ticker.interrupt();
    }

    public static void main(String[] args) throws Exception {
        Path dir = Config.dir();
        Path cfgFile = dir.resolve("bridge.properties");
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals("--config")) cfgFile = java.nio.file.Paths.get(args[i + 1]);
        Config cfg = Config.load(cfgFile, 25565, "key.pem");
        Main m = launch(cfg, cfgFile.toAbsolutePath().getParent());

        // simple console: "stop" / "list" / "debug"
        java.io.BufferedReader console = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        String line;
        while ((line = console.readLine()) != null) {
            line = line.trim().toLowerCase();
            if (line.equals("stop") || line.equals("end") || line.equals("exit")) { Log.info("Stopping."); m.stop(); System.exit(0); }
            else if (line.equals("list")) Log.info(m.players.size() + " player(s): " + m.players.values().stream().map(PESession::name).reduce((a, b) -> a + ", " + b).orElse(""));
            else if (line.equals("debug")) { Log.debug = !Log.debug; Log.info("Debug " + (Log.debug ? "on" : "off")); }
            else if (!line.isEmpty()) Log.info("Commands: list, debug, stop");
        }
        // stdin closed (some panels): keep running
        Thread.currentThread().join();
    }

    @Override public void onConnect(RakNet.Session s) {
        if (players.size() >= cfg.maxPlayers) { s.close("server full", true); return; }
        PESession p = new PESession(this, s);
        s.attachment = p;
        players.put(s, p);
        Log.info("3DS connected from " + s.address);
    }

    @Override public void onGamePacket(RakNet.Session s, byte[] payload) {
        PESession p = players.get(s);
        if (p != null) p.onGamePacket(payload);
    }

    @Override public void onDisconnect(RakNet.Session s, String reason) {
        PESession p = players.remove(s);
        if (p != null) p.onClosed(reason);
    }

    @Override public String motd() {
        String m = cfg.motd.replace(";", ",");
        return "MCPE;" + m + ";113;1.1.5;" + players.size() + ";" + cfg.maxPlayers + ";" + (cfg.bindPort & 0xFFFF) + ";Bridge;Survival;";
    }
}
