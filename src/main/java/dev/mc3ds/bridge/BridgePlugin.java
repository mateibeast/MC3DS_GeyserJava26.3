package dev.mc3ds.bridge;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Paper/Spigot plugin entry point: runs the bridge inside the server, so on hosts like
 * PebbleHost you only need to drop the jar into /plugins and open one extra UDP port.
 */
public final class BridgePlugin extends org.bukkit.plugin.java.JavaPlugin {
    private Main bridge;

    @Override
    public void onEnable() {
        SkinUpload.plugin = this;
        java.util.logging.Logger logger = getLogger();
        Log.sink = logger;
        try {
            Path dir = getDataFolder().toPath();
            Files.createDirectories(dir);
            Path floodgateKey = dir.resolveSibling("floodgate").resolve("key.pem");
            String defKey = Files.exists(floodgateKey) ? "../floodgate/key.pem" : "";
            Config cfg = Config.load(dir.resolve("bridge.properties"), org.bukkit.Bukkit.getPort(), defKey);
            if (cfg.javaHost.isEmpty()) cfg.javaHost = "127.0.0.1";
            SkinUpload.cacheFile = dir.resolve("skin-cache.properties");
            bridge = Main.launch(cfg, dir);
        } catch (Throwable t) {
            logger.severe("MC3DS Bridge failed to start: " + t);
            if (t instanceof java.net.BindException || String.valueOf(t.getMessage()).contains("Address already in use"))
                logger.severe("The UDP port is already used (Geyser uses 19132). Set a different bind-port in plugins/MC3DSBridge/bridge.properties " +
                        "and allocate that port in your host's panel.");
            t.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        if (bridge != null) bridge.stop();
        Log.sink = null;
        SkinUpload.plugin = null;
    }
}
