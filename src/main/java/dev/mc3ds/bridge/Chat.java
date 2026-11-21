package dev.mc3ds.bridge;

import java.util.*;

/** Tiny JSON parser + Java chat component -> legacy "§" text flattener. */
public final class Chat {

    private static final Map<String, String> TRANSLATIONS = new HashMap<>();
    private static final Map<String, Character> COLORS = new HashMap<>();
    static {
        TRANSLATIONS.put("chat.type.text", "<%s> %s");
        TRANSLATIONS.put("chat.type.announcement", "[%s] %s");
        TRANSLATIONS.put("chat.type.emote", "* %s %s");
        TRANSLATIONS.put("chat.type.admin", "[%s: %s]");
        TRANSLATIONS.put("commands.message.display.incoming", "%s whispers to you: %s");
        TRANSLATIONS.put("commands.message.display.outgoing", "You whisper to %s: %s");
        TRANSLATIONS.put("multiplayer.player.joined", "%s joined the game");
        TRANSLATIONS.put("multiplayer.player.left", "%s left the game");
        TRANSLATIONS.put("multiplayer.disconnect.kicked", "Kicked by an operator");
        TRANSLATIONS.put("multiplayer.disconnect.server_shutdown", "Server closed");
        TRANSLATIONS.put("death.attack.generic", "%s died");
        TRANSLATIONS.put("death.attack.player", "%s was slain by %s");
        TRANSLATIONS.put("death.attack.mob", "%s was slain by %s");
        TRANSLATIONS.put("death.attack.fall", "%s hit the ground too hard");
        TRANSLATIONS.put("death.attack.lava", "%s tried to swim in lava");
        TRANSLATIONS.put("death.attack.drown", "%s drowned");
        TRANSLATIONS.put("death.attack.explosion.player", "%s was blown up by %s");
        TRANSLATIONS.put("chat.type.advancement.task", "%s has made the advancement %s");
        TRANSLATIONS.put("chat.type.achievement", "%s has just earned the achievement %s");
        String[][] more = {
            // sleeping / beds (1.12 keys and the newer ones)
            {"tile.bed.noSleep", "You can only sleep at night"}, {"tile.bed.notSafe", "You may not rest now, there are monsters nearby"},
            {"tile.bed.occupied", "This bed is occupied"}, {"tile.bed.tooFarAway", "You may not rest now, the bed is too far away"},
            {"tile.bed.notValid", "Your home bed was missing or obstructed"},
            {"block.minecraft.bed.no_sleep", "You can sleep only at night or during thunderstorms"},
            {"block.minecraft.bed.not_safe", "You may not rest now; there are monsters nearby"},
            {"block.minecraft.bed.occupied", "This bed is occupied"}, {"block.minecraft.bed.too_far_away", "You may not rest now; the bed is too far away"},
            {"block.minecraft.bed.obstructed", "This bed is obstructed"}, {"block.minecraft.set_spawn", "Respawn point set"},
            {"block.minecraft.spawn.not_valid", "You have no home bed or charged respawn anchor, or it was obstructed"},
            {"sleep.players_sleeping", "%s/%s players sleeping"}, {"sleep.skipping_night", "Sleeping through this night"},
            {"sleep.not_possible", "No amount of rest can pass this night"},
            // joins, commands
            {"multiplayer.player.joined.renamed", "%s (formerly known as %s) joined the game"},
            {"chat.type.team.text", "%s <%s> %s"}, {"chat.type.team.sent", "-> %s <%s> %s"},
            {"commands.gamemode.success.self", "Set own game mode to %s"}, {"commands.gamemode.success.other", "Set %s's game mode to %s"},
            {"gameMode.survival", "Survival Mode"}, {"gameMode.creative", "Creative Mode"}, {"gameMode.adventure", "Adventure Mode"}, {"gameMode.spectator", "Spectator Mode"},
            {"commands.teleport.success.entity.single", "Teleported %s to %s"}, {"commands.teleport.success.location.single", "Teleported %s to %s, %s, %s"},
            {"commands.tp.success", "Teleported %s to %s"}, {"commands.tp.success.coordinates", "Teleported %s to %s, %s, %s"},
            {"commands.time.set", "Set the time to %s"}, {"commands.weather.set.clear", "Set the weather to clear"},
            {"commands.weather.set.rain", "Set the weather to rain"}, {"commands.weather.set.thunder", "Set the weather to rain & thunder"},
            {"commands.give.success.single", "Gave %s %s to %s"}, {"commands.give.success", "Given %s * %s to %s"},
            {"commands.help.header", "--- Showing help page %s of %s ---"}, {"commands.generic.notFound", "Unknown command. Try /help for a list of commands"},
            {"command.unknown.command", "Unknown or incomplete command, see below for error"}, {"commands.generic.permission", "You do not have permission to use this command"},
            {"commands.kill.successful", "Killed %s"}, {"commands.kill.success.single", "Killed %s"},
            {"chat.type.advancement.goal", "%s has reached the goal %s"}, {"chat.type.advancement.challenge", "%s has completed the challenge %s"},
            {"multiplayer.disconnect.idling", "You have been idle for too long!"}, {"multiplayer.disconnect.flying", "Flying is not enabled on this server"},
            {"multiplayer.disconnect.duplicate_login", "You logged in from another location"}, {"disconnect.timeout", "Timed out"},
            {"death.attack.player.item", "%s was slain by %s using %s"}, {"death.attack.mob.item", "%s was slain by %s using %s"},
            {"death.attack.arrow", "%s was shot by %s"}, {"death.attack.arrow.item", "%s was shot by %s using %s"},
            {"death.attack.inFire", "%s went up in flames"}, {"death.attack.onFire", "%s burned to death"},
            {"death.attack.inWall", "%s suffocated in a wall"}, {"death.attack.starve", "%s starved to death"},
            {"death.attack.cactus", "%s was pricked to death"}, {"death.attack.outOfWorld", "%s fell out of the world"},
            {"death.attack.magic", "%s was killed by magic"}, {"death.attack.explosion", "%s blew up"},
            {"death.attack.fallingBlock", "%s was squashed by a falling block"}, {"death.attack.thorns", "%s was killed trying to hurt %s"},
            {"death.attack.lightningBolt", "%s was struck by lightning"}, {"death.attack.hotFloor", "%s discovered the floor was lava"},
            {"death.attack.flyIntoWall", "%s experienced kinetic energy"}, {"death.attack.wither", "%s withered away"},
            {"death.attack.sweetBerryBush", "%s was poked to death by a sweet berry bush"}, {"death.attack.freeze", "%s froze to death"},
            {"death.fell.accident.generic", "%s fell from a high place"}, {"death.fell.accident.ladder", "%s fell off a ladder"},
            {"death.fell.accident.vines", "%s fell off some vines"}, {"death.fell.accident.water", "%s fell out of the water"},
            {"death.attack.fall.player", "%s hit the ground too hard whilst trying to escape %s"},
            {"death.attack.drown.player", "%s drowned whilst trying to escape %s"}, {"death.attack.lava.player", "%s tried to swim in lava to escape %s"},
            {"death.attack.indirectMagic", "%s was killed by %s using magic"}, {"death.attack.fireball", "%s was fireballed by %s"},
            {"death.attack.generic.player", "%s died because of %s"},
            {"key.use", "Use"}, {"key.attack", "Attack"}, {"key.sneak", "Sneak"}, {"key.jump", "Jump"}, {"key.inventory", "Inventory"}
        };
        for (String[] kv : more) TRANSLATIONS.put(kv[0], kv[1]);
        String[] names = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
                "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
        for (int i = 0; i < names.length; i++) COLORS.put(names[i], "0123456789abcdef".charAt(i));
    }

    public static String toText(String json) {
        try {
            Object o = new Parser(json).value();
            StringBuilder sb = new StringBuilder();
            flatten(o, sb);
            return sb.toString();
        } catch (Exception e) {
            return json;
        }
    }

    @SuppressWarnings("unchecked")
    private static void flatten(Object o, StringBuilder sb) { flatten(o, sb, ""); }

    /** @param parent formatting codes inherited from the parent component (e.g. "§6§l") */
    @SuppressWarnings("unchecked")
    private static void flatten(Object o, StringBuilder sb, String parent) {
        if (o == null) return;
        if (o instanceof String) { sb.append(o); return; }
        if (o instanceof List) { for (Object x : (List<Object>) o) flatten(x, sb, parent); return; }
        if (!(o instanceof Map)) { sb.append(o); return; }
        Map<String, Object> m = (Map<String, Object>) o;
        String style = parent;
        Object color = m.get("color");
        if (color instanceof String && COLORS.containsKey(color)) style = "§" + COLORS.get(color) + stripColor(style);
        else if (color instanceof String && ((String) color).startsWith("#")) style = "§" + nearestColor((String) color) + stripColor(style);
        if (Boolean.TRUE.equals(m.get("bold")) && !style.contains("§l")) style += "§l";
        if (Boolean.TRUE.equals(m.get("italic")) && !style.contains("§o")) style += "§o";
        if (Boolean.TRUE.equals(m.get("underlined")) && !style.contains("§n")) style += "§n";
        boolean changed = !style.equals(parent);
        if (changed) sb.append(style);
        if (m.get("text") != null) sb.append(m.get("text"));
        if (m.get("keybind") != null) { String k = String.valueOf(m.get("keybind")); sb.append(TRANSLATIONS.getOrDefault(k, k)); }
        if (m.get("selector") != null) sb.append(m.get("selector"));
        if (m.get("score") instanceof Map) { Object v = ((Map<String, Object>) m.get("score")).get("value"); if (v != null) sb.append(v); }
        if (m.get("translate") != null) {
            String key = String.valueOf(m.get("translate"));
            List<Object> with = m.get("with") instanceof List ? (List<Object>) m.get("with") : Collections.emptyList();
            String fmt = TRANSLATIONS.get(key);
            List<String> args = new ArrayList<>();
            for (Object w : with) { StringBuilder a = new StringBuilder(); flatten(w, a, style); args.add(a.toString() + (a.indexOf("§") >= 0 ? "§r" + style : "")); }
            if (fmt == null && isNameKey(key)) fmt = humanize(key);
            if (fmt == null) {
                sb.append(args.isEmpty() ? key : String.join(" ", args));
            } else {
                StringBuilder r = new StringBuilder();
                int ai = 0;
                for (int i = 0; i < fmt.length(); i++) {
                    char ch = fmt.charAt(i);
                    if (ch == '%' && i + 1 < fmt.length() && fmt.charAt(i + 1) == 's') {
                        r.append(ai < args.size() ? args.get(ai++) : ""); i++;
                    } else if (ch == '%' && i + 3 < fmt.length() && Character.isDigit(fmt.charAt(i + 1)) && fmt.charAt(i + 2) == '$' && fmt.charAt(i + 3) == 's') {
                        int n = fmt.charAt(i + 1) - '1';
                        r.append(n >= 0 && n < args.size() ? args.get(n) : ""); i += 3;
                    } else if (ch == '%' && i + 1 < fmt.length() && fmt.charAt(i + 1) == '%') {
                        r.append('%'); i++;
                    } else r.append(ch);
                }
                sb.append(r);
            }
        }
        if (m.get("extra") instanceof List) for (Object x : (List<Object>) m.get("extra")) flatten(x, sb, style);
        if (changed) sb.append("§r").append(parent);
    }

    private static String stripColor(String style) { return style.replaceAll("§[0-9a-f]", ""); }

    /** "entity.minecraft.zombie" -> "Zombie", "entity.Zombie.name" -> "Zombie", "item.minecraft.diamond_sword" -> "Diamond Sword" */
    private static boolean isNameKey(String k) {
        return k.startsWith("entity.") || k.startsWith("item.") || k.startsWith("block.") || k.startsWith("tile.") || k.startsWith("enchantment.") || k.startsWith("effect.") || k.startsWith("biome.");
    }
    private static String humanize(String k) {
        String[] parts = k.split("\\.");
        String last = parts[parts.length - 1];
        if ((last.equals("name") || last.equals("desc")) && parts.length >= 2) last = parts[parts.length - 2];
        last = last.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
        StringBuilder b = new StringBuilder();
        for (String w : last.split(" ")) if (!w.isEmpty()) b.append(b.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return b.toString();
    }

    private static final int[][] RGB = {{0,0,0},{0,0,170},{0,170,0},{0,170,170},{170,0,0},{170,0,170},{255,170,0},{170,170,170},
            {85,85,85},{85,85,255},{85,255,85},{85,255,255},{255,85,85},{255,85,255},{255,255,85},{255,255,255}};
    private static char nearestColor(String hex) {
        try {
            int v = Integer.parseInt(hex.substring(1), 16), r = v >> 16 & 255, g = v >> 8 & 255, b = v & 255;
            int best = 15; long bd = Long.MAX_VALUE;
            for (int i = 0; i < 16; i++) { long d = (long) (r - RGB[i][0]) * (r - RGB[i][0]) + (long) (g - RGB[i][1]) * (g - RGB[i][1]) + (long) (b - RGB[i][2]) * (b - RGB[i][2]); if (d < bd) { bd = d; best = i; } }
            return "0123456789abcdef".charAt(best);
        } catch (Exception e) { return 'f'; }
    }

    /** Make text safe for the 3DS font: no emoji/surrogates, no stray formatting characters, no reset codes it lacks. */
    public static String forPE(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) { b.append('?'); if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) i++; continue; }
            if (Character.isLowSurrogate(c)) continue;
            if (c == '§' && i + 1 < s.length() && "0123456789abcdefklmnor".indexOf(Character.toLowerCase(s.charAt(i + 1))) < 0) { i++; continue; } // hex colours etc.
            if (c < 0x20 && c != '\n') continue;
            b.append(c);
        }
        return b.toString();
    }

    /** Escape a plain string as a JSON chat component. */
    public static String json(String s) {
        StringBuilder sb = new StringBuilder("{\"text\":\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append("\"}").toString();
    }

    // ------------------------------------------------------------------ JSON parser
    static final class Parser {
        final String s; int i;
        Parser(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            ws();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) { // bare string (old servers sometimes send plain text)
                String r = s.substring(i); i = s.length(); return r;
            }
            return s.substring(st, i);
        }
        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws(); String k = string(); ws(); i++; // ':'
                m.put(k, value()); ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
            }
        }
        List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value()); ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
            }
        }
        String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': break;
                        case 'b': case 'f': break;
                        case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
        }
    }
}
