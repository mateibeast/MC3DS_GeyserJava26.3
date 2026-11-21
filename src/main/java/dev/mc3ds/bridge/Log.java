package dev.mc3ds.bridge;

import java.text.SimpleDateFormat;
import java.util.Date;

public final class Log {
    public static volatile boolean debug = false;
    /** When running as a plugin, log through the server's logger instead of stdout. */
    public static volatile java.util.logging.Logger sink;
    private static final SimpleDateFormat F = new SimpleDateFormat("HH:mm:ss");

    private static synchronized void out(String level, String msg) {
        java.util.logging.Logger l = sink;
        if (l != null) {
            if (level.equals("ERROR")) l.severe(msg);
            else if (level.equals("WARN")) l.warning(msg);
            else l.info(level.equals("DEBUG") ? "[debug] " + msg : msg);
            return;
        }
        System.out.println("[" + F.format(new Date()) + " " + level + "] " + msg);
    }
    public static void info(String m) { out("INFO", m); }
    public static void warn(String m) { out("WARN", m); }
    public static void debug(String m) { if (debug) out("DEBUG", m); }
    public static void err(String m, Throwable t) {
        out("ERROR", m + (t == null ? "" : ": " + t));
        if (t != null && debug) t.printStackTrace(System.out);
    }
}
