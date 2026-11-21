package dev.mc3ds.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * One 3DS player: translates Pocket Edition 1.1 (protocol 113) packets to a Java 1.12.2
 * connection and back.
 */
public final class PESession implements JavaConn.Handler {

    // ---- PE packet ids (protocol 113)
    static final int LOGIN = 0x01, PLAY_STATUS = 0x02, DISCONNECT = 0x05, RP_INFO = 0x06, RP_STACK = 0x07,
            RP_RESPONSE = 0x08, TEXT = 0x09, SET_TIME = 0x0a, START_GAME = 0x0b, ADD_PLAYER = 0x0c, ADD_ENTITY = 0x0d,
            REMOVE_ENTITY = 0x0e, MOVE_ENTITY = 0x12, MOVE_PLAYER = 0x13, REMOVE_BLOCK = 0x15, UPDATE_BLOCK = 0x16,
            ENTITY_EVENT = 0x1c, UPDATE_ATTRIBUTES = 0x1e, MOB_EQUIPMENT = 0x1f, MOB_ARMOR = 0x20, INTERACT = 0x21,
            USE_ITEM = 0x23, PLAYER_ACTION = 0x24, SET_HEALTH = 0x2a, SET_SPAWN = 0x2b, ANIMATE = 0x2c, RESPAWN = 0x2d,
            CONTAINER_SET_CONTENT = 0x34, ADVENTURE_SETTINGS = 0x37, BLOCK_ENTITY_DATA = 0x38, SET_ENTITY_DATA = 0x27, FULL_CHUNK = 0x3a, CHANGE_DIMENSION = 0x3d,
            SET_GAMETYPE = 0x3e, PLAYER_LIST = 0x3f, REQUEST_CHUNK_RADIUS = 0x45, CHUNK_RADIUS_UPDATED = 0x46,
            AVAILABLE_COMMANDS = 0x4e, COMMAND_STEP = 0x4f,
            ADD_ITEM_ENTITY = 0x0f, SET_ENTITY_MOTION = 0x28, TAKE_ITEM_ENTITY = 0x11, DROP_ITEM = 0x2e, CONTAINER_OPEN = 0x30, CONTAINER_CLOSE = 0x31, CONTAINER_SET_SLOT = 0x32, CONTAINER_SET_DATA = 0x33,
            CRAFTING_DATA = 0x35, CRAFTING_EVENT = 0x36, LEVEL_SOUND = 0x19, LEVEL_EVENT = 0x1a, BOSS_EVENT = 0x4c, SET_TITLE = 0x59, UPDATE_TRADE = 0x51;
    static final String[] DEFAULT_COMMANDS = {"help", "list", "msg", "tell", "w", "r", "me", "spawn", "home", "sethome", "delhome",
            "homes", "warp", "warps", "tpa", "tpahere", "tpaccept", "tpdeny", "back", "kill", "suicide", "gamemode", "gm", "tp",
            "give", "time", "weather", "say", "plugins", "pl", "seed", "difficulty", "kit", "kits", "balance", "bal", "pay",
            "sell", "shop", "ignore", "mail", "nick", "afk", "rules", "discord", "vote", "claim", "trust", "untrust"};

    static final float EYE = 1.62f;
    static final UUID SPRINT_MODIFIER = UUID.fromString("662a6b8d-da3e-4c1c-8813-96ea6097278d");

    final Main main;
    final RakNet.Session rak;
    final Config cfg;

    String username;
    JavaConn java;
    boolean rpDone, javaJoined, havePos, startGameSent, spawnSent, closed, awaitingRespawn;
    long startGameTime;

    // own player
    int eid, gamemode, dimension, difficulty;
    double x, y, z;
    float yaw, pitch;
    boolean onGround = true;
    long lastJavaMove;
    boolean moveDirty, lookDirty;           // position changes waiting for the next 20 tps movement packet
    long tpPendingUntil; double tpX, tpY, tpZ;
    int worldSpawnX, worldSpawnY = 64, worldSpawnZ;   // from the Java spawn position packet
    boolean dead, serverRespawned, respawnSentToPE;
    double respawnX, respawnY, respawnZ;
    int haste = -1, fatigue = -1;           // active effect amplifiers on our player
    boolean sleeping;
    int pendingDim = Integer.MIN_VALUE;
    final java.util.TreeSet<String> commands = new java.util.TreeSet<>();
    // ---- open container (Java window) and the invisible Java cursor
    int winId = -1, winSize, winPeType; JItem[] win = new JItem[0];
    JItem cursor;
    int lastUseX, lastUseY = -1, lastUseZ;
    short clickAction = 1;
    final List<int[]> peChanges = new ArrayList<>();      // {peWindow, peSlot}
    final List<int[]> peChangeItems = new ArrayList<>();  // {peId, meta, count}
    final List<byte[]> peChangeNbt = new ArrayList<>();   // item NBT the 3DS sent (custom names...)
    int lastInteractTarget = -1;
    final List<JItem[]> trades = new ArrayList<>();       // villager offers {buyA, buyB, sell}
    int tradeSelected = -1;
    long lastPeChange; boolean dropPending;
    boolean commandsSent;
    // block breaking, timed like the Java server
    long digKey = Long.MIN_VALUE, digStart;
    long finishKey = Long.MIN_VALUE, finishAt; int finishX, finishY, finishZ;
    long lastDigLog = Long.MIN_VALUE;
    int radius;
    float health = 20; int food = 20; float saturation = 5;
    int xpLevel = 0; float xpProgress = 0;
    float moveSpeed = 0.1f;   // PE "minecraft:movement" (Java generic.movementSpeed, without sprint)
    float speedMultiplier;    // tweak for how fast the 3DS actually moves

    // world
    final Map<Long, Mapping.PEColumn> chunks = new HashMap<>();
    final Set<Long> sentChunks = new HashSet<>();
    int lastChunkX = Integer.MIN_VALUE, lastChunkZ = Integer.MIN_VALUE;

    // entities
    static final class Ent { boolean player, skinApplied, shown, isItem; int peType; UUID uuid; double x, y, z; float yaw, pitch, headYaw;
        float vx, vy, vz; JItem item, hand; final JItem[] armor = new JItem[4]; } // armor: helmet, chest, legs, boots
    final Map<Integer, Ent> entities = new HashMap<>();
    final Map<UUID, String> playerNames = new HashMap<>();
    final Map<UUID, String[]> playerSkins = new HashMap<>();   // uuid -> {url, "slim"|""}

    // inventory (Java window 0 slots)
    final JItem[] inv = new JItem[46];    // Java window 0 slots
    int heldSlot;

    // outgoing PE packets waiting to be batched
    private final List<byte[]> pending = new ArrayList<>();
    private int pendingBytes;

    PESession(Main main, RakNet.Session rak) {
        this.main = main; this.rak = rak; this.cfg = main.cfg; this.radius = cfg.chunkRadius;
        this.speedMultiplier = cfg.speedMultiplier;
    }

    // =====================================================================================
    //  PE side - receive
    // =====================================================================================
    synchronized void onGamePacket(byte[] payload) {
        if (closed) return;
        byte[] raw;
        try {
            raw = inflate(payload);
        } catch (Exception e) {
            // not a zlib batch - maybe a single bare packet
            raw = null;
        }
        try {
            if (raw == null) { handlePacket(new Buf(payload)); return; }
            Buf b = new Buf(raw);
            while (!b.eof()) {
                byte[] pk = b.strRaw();
                if (pk.length == 0) continue;
                try {
                    handlePacket(new Buf(pk));
                } catch (Throwable t) {
                    Log.err("[" + name() + "] Failed handling PE packet 0x" + Integer.toHexString(pk[0] & 0xFF) + " " + Buf.hex(pk, 48), t);
                }
            }
        } catch (Throwable t) {
            Log.err("[" + name() + "] Bad batch", t);
        } finally {
            flush();
        }
    }

    private void handlePacket(Buf in) throws IOException {
        int id = in.u8();
        if (Log.debug && id != MOVE_PLAYER && id != INTERACT) Log.debug("[" + name() + "] PE -> 0x" + Integer.toHexString(id) + " (" + in.remaining() + " bytes)");
        switch (id) {
            case LOGIN: handleLogin(in); break;
            case RP_RESPONSE: {
                int status = in.u8();
                if (status == 3) { // have all packs
                    send(new Buf().put(RP_STACK).bool(false).uvarint(0).uvarint(0));
                } else { // completed (4) / refused / send packs - we have none, so just carry on
                    rpDone = true;
                    tryStartGame();
                }
                break;
            }
            case REQUEST_CHUNK_RADIUS: {
                int r = in.varint();
                radius = Math.max(2, Math.min(r, cfg.chunkRadius));
                send(new Buf().put(CHUNK_RADIUS_UPDATED).varint(radius));
                if (java != null && java.isPlay() && javaJoined) sendClientSettings(radius);
                sendNearbyChunks(true);
                break;
            }
            case MOVE_PLAYER: {
                in.uvarlong();
                float px = in.leFloat(), py = in.leFloat(), pz = in.leFloat();
                float ppitch = in.leFloat(), pyaw = in.leFloat();
                in.leFloat();          // head yaw
                in.u8();               // mode
                boolean ground = in.bool();
                if (!spawnSent || awaitingRespawn || dead) break;
                // after a server teleport, ignore stale positions until the 3DS has actually moved there
                if (tpPendingUntil > System.currentTimeMillis()) {
                    double d = Math.abs(px - tpX) + Math.abs(py - EYE - tpY) + Math.abs(pz - tpZ);
                    if (d > 2.5) break;
                    tpPendingUntil = 0;
                }
                // The 3DS sends 32-bit floats at eye height. Converting back can leave the feet ~0.000005 blocks
                // *inside* the block below, and Paper silently pulls you back when a move enters a block
                // (stairs, jumping onto a block edge). Snap to 1/10000 of a block so we never sink into surfaces.
                double nx = snap(px), ny = snap((double) py - EYE), nz = snap(pz);
                if (gamemode != 3) {   // push out of blocks the 3DS clips into (PE collision is a bit looser than Java's)
                    double[] fixed = Collision.correct(this::javaBlockAt, nx, ny, nz);
                    if (Log.debug && (fixed[0] != nx || fixed[1] != ny || fixed[2] != nz))
                        Log.debug("[" + name() + "] collision fix " + String.format("%.4f %.4f %.4f -> %.4f %.4f %.4f", nx, ny, nz, fixed[0], fixed[1], fixed[2]));
                    nx = fixed[0]; ny = fixed[1]; nz = fixed[2];
                }
                if (nx != x || ny != y || nz != z) moveDirty = true;
                if (pyaw != yaw || ppitch != pitch) lookDirty = true;
                x = nx; y = ny; z = nz; yaw = pyaw; pitch = ppitch; onGround = ground;
                if (takeMoveToken()) flushMovement();
                sendNearbyChunks(false);
                break;
            }
            case TEXT: {
                int type = in.u8();
                String msg;
                if (type == 1 || type == 3 || type == 6 || type == 7) { in.str(); msg = in.str(); } // has a source name
                else if (type == 0 || type == 4 || type == 5 || type == 2) msg = in.str();
                else break;
                Log.debug("[" + name() + "] 3DS chat (type " + type + "): " + msg);
                msg = msg.replaceAll("§.", "").replace("\n", " ").trim();       // Java rejects colour codes in chat
                msg = msg.replaceAll("[\\p{Cntrl}]", "");
                if (msg.isEmpty()) break;
                if (msg.toLowerCase().startsWith("/3ds")) { bridgeCommand(msg); break; }
                if (msg.length() > 256) msg = msg.substring(0, 256);
                if (java != null && java.isPlay()) java.send(new Buf().jvarint(0x02).str(msg));
                break;
            }
            case PLAYER_ACTION: {
                in.uvarlong();
                int action = in.varint();
                int bx = in.varint(), by = in.uvarint(), bz = in.varint();
                int face = in.varint();
                if (Log.debug && action <= 2) Log.debug("[" + name() + "] 3DS action " + action + " at " + bx + "," + by + "," + bz + " face " + face);
                switch (action) {
                    case 0: startBreak(bx, by, bz, face); break;                // start break
                    case 1: abortBreak(bx, by, bz, face); break;                // abort break
                    case 7: // respawn button
                        if (!dead) break;
                        if (!serverRespawned) { java.send(new Buf().jvarint(0x03).jvarint(0)); break; } // server hasn't yet
                        dead = false;
                        if (sleeping) send(new Buf().put(ANIMATE).varint(3).uvarint(eid));
                        sleeping = false;
                        sendOwnEntityData();
                        sendAttributes();
                        sendAdventureSettings(gamemode == 1 || gamemode == 3, false);
                        sendInventory();
                        sendHeldItem();
                        // a plugin may have moved us after the respawn: then also move the 3DS
                        if (Math.abs(x - respawnX) + Math.abs(y - respawnY) + Math.abs(z - respawnZ) > 0.5) sendMovePlayer(1);
                        tpX = x; tpY = y; tpZ = z; tpPendingUntil = System.currentTimeMillis() + 1500;
                        Log.info("[" + username + "] respawned");
                        break;
                    case 9: javaEntityAction(3); break;   // start sprint
                    case 10: javaEntityAction(4); break;  // stop sprint
                    case 11: javaEntityAction(0); break;  // start sneak
                    case 12: javaEntityAction(1); break;  // stop sneak
                    case 6: javaEntityAction(2); break;   // leave bed
                    default: break;
                }
                break;
            }
            case REMOVE_BLOCK: {
                int bx = in.varint(), by = in.uvarint(), bz = in.varint();
                finishBreak(bx, by, bz);
                break;
            }
            case USE_ITEM: {
                int bx = in.varint(), by = in.uvarint(), bz = in.varint();
                in.uvarint();                         // block id
                int face = in.varint();
                float fx = in.leFloat(), fy = in.leFloat(), fz = in.leFloat();
                if (java == null || !java.isPlay()) break;
                if (face >= 0 && face <= 5) {
                    lastUseX = bx; lastUseY = by; lastUseZ = bz;
                    java.send(new Buf().jvarint(0x1F).beLong(Buf.jPos(bx, by, bz)).jvarint(face).jvarint(0)
                            .beFloat(clamp01(fx)).beFloat(clamp01(fy)).beFloat(clamp01(fz)));
                    javaSwing();
                } else {
                    java.send(new Buf().jvarint(0x20).jvarint(0));
                }
                break;
            }
            case MOB_EQUIPMENT: {
                in.uvarlong();
                skipSlot(in);
                in.u8();                       // inventory slot
                int hotbar = in.u8();
                if (hotbar >= 0 && hotbar < 9 && java != null && java.isPlay()) {
                    heldSlot = hotbar;
                    java.send(new Buf().jvarint(0x1A).beShort(hotbar));
                }
                break;
            }
            case INTERACT: {
                int action = in.u8();
                long target = in.uvarlong();
                if (java == null || !java.isPlay()) break;
                if ((action != 1 && action != 2) || !entities.containsKey((int) target) || target == eid) break;
                lastInteractTarget = (int) target;
                if (action == 2) { // left click = attack
                    java.send(new Buf().jvarint(0x0A).jvarint((int) target).jvarint(1));
                    javaSwing();
                } else if (action == 1) { // right click = interact
                    java.send(new Buf().jvarint(0x0A).jvarint((int) target).jvarint(0).jvarint(0));
                }
                break;
            }
            case CONTAINER_SET_SLOT: {
                int window = in.u8();
                int slot = in.varint();
                in.varint();                          // hotbar slot
                int itemId = in.varint();
                int meta = 0, count = 0;
                byte[] nbt = null;
                if (itemId > 0) {
                    int aux = in.varint();
                    meta = aux >> 8; if (meta == 0x7FFF) meta = -1; count = aux & 0xFF;
                    nbt = in.bytes(in.leShort());
                    int a = in.varint(); for (int i = 0; i < a; i++) in.str();
                    int c = in.varint(); for (int i = 0; i < c; i++) in.str();
                }
                if (Log.debug) Log.debug("[" + name() + "] 3DS slot window=" + window + " slot=" + slot + " -> " + (itemId > 0 ? itemId + ":" + meta + " x" + count : "empty") + " (open window " + winId + ", PE type " + winPeType + ")");
                if (window == 122) break;             // hotbar link change: nothing on Java
                peChanges.add(new int[]{window, slot});
                peChangeItems.add(new int[]{Math.max(0, itemId), meta, itemId > 0 ? count : 0});
                peChangeNbt.add(nbt);
                lastPeChange = System.currentTimeMillis();
                break;
            }
            case DROP_ITEM: dropPending = true; lastPeChange = System.currentTimeMillis(); break;
            case 0x2f: { // inventory action: the 3DS enchanted the item in its enchanting table
                int action = in.varint();
                Log.debug("[" + name() + "] 3DS inventory action " + action);
                if (action == 2 && winPeType == 3 && enchantDoneFor != winId) { pendingEnchant = true; lastPeChange = System.currentTimeMillis(); }
                break;
            }
            case CRAFTING_EVENT: {
                int window = in.u8();
                in.varint();                                   // recipe type
                long a = in.leInt() & 0xFFFFFFFFL, b2 = in.leInt() & 0xFFFFFFFFL, c = in.leInt() & 0xFFFFFFFFL, d = in.leInt() & 0xFFFFFFFFL;
                UUID rid = new UUID((a << 32) | b2, (c << 32) | d);
                Extras.Recipe r = Extras.RECIPE_BY_ID.get(rid);
                Log.debug("[" + name() + "] 3DS crafted " + (r == null ? "unknown recipe " + rid : r.outId + ":" + r.outMeta + " x" + r.outCount) + " (window " + window + ")");
                if (r != null) craft(r);
                lastPeChange = System.currentTimeMillis();     // let the slot changes that follow wait for this
                break;
            }
            case CONTAINER_CLOSE: {
                int window = in.u8();
                if (window == winId && winId > 0) {
                    processInventoryChanges();
                    if (pendingEnchant && winPeType == 3 && java != null && java.isPlay()) { java.send(new Buf().jvarint(0x06).put(winId).put(0)); pendingEnchant = false; }
                    if (java != null && java.isPlay()) java.send(new Buf().jvarint(0x08).put(winId));
                    closeContainerState();
                }
                break;
            }
            case COMMAND_STEP: {
                String cmd = in.str();
                in.str();                       // overload
                in.uvarint(); in.uvarint();     // step info
                in.bool();                      // done
                in.uvarlong();                  // client id
                String input = in.str();
                StringBuilder line = new StringBuilder("/").append(cmd);
                try {
                    Object o = new Chat.Parser(input).value();
                    if (o instanceof Map) for (Object v : ((Map<?, ?>) o).values()) if (v != null && !String.valueOf(v).isEmpty()) line.append(' ').append(v);
                } catch (Exception ignored) { }
                String full = line.toString().trim();
                Log.debug("[" + name() + "] 3DS command: " + full);
                if (cmd.equalsIgnoreCase("3ds")) { bridgeCommand(full); break; }
                if (full.length() > 256) full = full.substring(0, 256);
                if (java != null && java.isPlay()) java.send(new Buf().jvarint(0x02).str(full));
                break;
            }
            case ANIMATE: {
                int action = in.varint();
                if (action == 1) javaSwing();
                break;
            }
            default:
                // inventory moves, crafting etc. are not supported yet
                break;
        }
    }

    // =====================================================================================
    //  containers & inventory: the 3DS reports "slot X now holds Y"; Java wants clicks with a cursor.
    //  We keep a copy of the server's inventory, work out clicks that produce what the 3DS shows, and send them.
    //  If anything doesn't match, the server corrects us and we resend the real contents to the 3DS.
    // =====================================================================================
    static int peWindowType(String type) {
        switch (type) {
            case "minecraft:chest": case "minecraft:container": case "minecraft:shulker_box": case "minecraft:ender_chest": return 0;
            case "minecraft:furnace": return 2;
            case "minecraft:dispenser": return 6;
            case "minecraft:dropper": return 7;
            case "minecraft:hopper": return 8;
            case "minecraft:crafting_table": return 1;
            case "minecraft:enchanting_table": return 3;
            case "minecraft:brewing_stand": return 4;
            case "minecraft:anvil": return 5;
            case "minecraft:villager": return 15;
            default: return -1;
        }
    }

    private void closeContainerState() { winId = -1; winSize = 0; win = new JItem[0]; trades.clear(); tradeSelected = -1; pendingEnchant = false; enchantDoneFor = -1; }

    // PE and Java number some container slots differently
    private static final int[] BREW_PE_TO_JAVA = {3, 0, 1, 2, 4};   // PE: ingredient, 3 bottles, fuel | Java: 3 bottles, ingredient, fuel
    private int peSlotToJava(int s) {
        if (winPeType == 4) return s >= 0 && s < 5 ? BREW_PE_TO_JAVA[s] : -1;
        return s;
    }
    private int javaSlotToPe(int s) {
        if (winPeType == 4) { for (int i = 0; i < 5; i++) if (BREW_PE_TO_JAVA[i] == s) return i; return -1; }
        return s;
    }

    /** index in the active Java window for a Java window-0 slot (-1 if not part of it) */
    private int activeFromWin0(int s0) {
        if (winId <= 0) return s0;
        if (s0 >= 9 && s0 <= 35) return winSize + (s0 - 9);
        if (s0 >= 36 && s0 <= 44) return winSize + 27 + (s0 - 36);
        return -1;
    }
    private JItem getActive(int i) {
        if (winId <= 0) return i >= 0 && i < inv.length ? inv[i] : null;
        if (i < winSize) return win[i];
        int r = i - winSize;
        return r < 27 ? inv[9 + r] : r < 36 ? inv[36 + r - 27] : null;
    }
    private void setActive(int i, JItem it) {
        if (winId <= 0) { if (i >= 0 && i < inv.length) inv[i] = it; return; }
        if (i < winSize) { win[i] = it; return; }
        int r = i - winSize;
        if (r < 27) inv[9 + r] = it; else if (r < 36) inv[36 + r - 27] = it;
    }
    /** PE (window, slot) -> active Java window index */
    private int activeFromPE(int window, int slot) {
        if (window == 0) return slot < 0 ? -1 : activeFromWin0(slot < 9 ? 36 + slot : slot);
        if (window == 120) return winId > 0 ? -1 : (slot >= 0 && slot < 4 ? 5 + slot : -1);
        // some 3DS menus report their slots under another window id than the one we opened: treat any
        // non-inventory window as the open container
        if (winId > 0 && (window == winId || (window > 0 && window < 119))) { int j = peSlotToJava(slot); return j >= 0 && j < winSize ? j : -1; }
        return -1;
    }

    private void sendContainerContents() {
        if (winId <= 0 || winPeType == 1) return;   // the PE workbench has no grid slots to show
        Buf b = new Buf().put(CONTAINER_SET_CONTENT).uvarint(winId).varlong(eid).uvarint(winSize);
        for (int i = 0; i < winSize; i++) { int j = peSlotToJava(i); writePESlot(b, j >= 0 && j < winSize ? win[j] : null); }
        b.uvarint(0);
        send(b);
    }
    private void sendContainerSlot(int slot) {
        if (winPeType == 1) return;
        int peSlot = javaSlotToPe(slot);
        if (peSlot < 0) return;
        Buf b = new Buf().put(CONTAINER_SET_SLOT).put(winId).varint(peSlot).varint(0);
        writePESlot(b, win[slot]);
        b.put(0);
        send(b);
    }

    /** PE needs both halves of a double chest to point at each other. */
    private void pairChest(int x0, int y0, int z0) {
        int id = blockIdAt(x0, y0, z0);
        int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int[] d : dirs) {
            int x1 = x0 + d[0], z1 = z0 + d[1];
            if (blockIdAt(x1, y0, z1) != id) continue;
            for (int[] pr : new int[][]{{x0, z0, x1, z1}, {x1, z1, x0, z0}}) {
                byte[] t = new NBT.PEWriter().string("id", "Chest").integer("x", pr[0]).integer("y", y0).integer("z", pr[1])
                        .integer("pairx", pr[2]).integer("pairz", pr[3]).end();
                send(new Buf().put(BLOCK_ENTITY_DATA).peBlockPos(pr[0], y0, pr[1]).put(t));
            }
            return;
        }
    }
    private int blockIdAt(int bx, int by, int bz) {
        Mapping.PEColumn col = chunks.get(key(bx >> 4, bz >> 4));
        return col == null ? 0 : col.getBlockId(bx, by, bz);
    }

    private static boolean pePeSame(JItem it, int[] pe) {
        if (it == null) return pe[0] == 0 || pe[2] == 0;
        int[] p = it.pe();
        return p != null && pe[0] == p[0] && (pe[1] == p[1] || pe[1] == -1);
    }

    // ---- crafting: PE 1.1 crafts straight from the inventory; Java needs the items put into the grid
    private void craft(Extras.Recipe r) {
        if (java == null || !java.isPlay()) return;
        boolean table = winId > 0 && winPeType == 1;
        if (winId > 0 && !table) return;
        int window = table ? winId : 0, gw = table ? 3 : 2;
        if (r.shaped ? (r.w > gw || r.h > gw) : r.in.length > gw * gw) {
            send(text(0, "§e[3DS Bridge] That recipe needs a crafting table."));
            return;
        }
        int invStart = table ? 10 : 9, invEnd = table ? 46 : 45;
        for (int i = 0; i < r.in.length; i++) {
            int[] need = r.in[i];
            if (need[0] == 0) continue;
            int grid = r.shaped ? 1 + (i / r.w) * gw + (i % r.w) : 1 + i;
            int src = -1;
            for (int sl = invStart; sl < invEnd && src < 0; sl++) {
                JItem it = getActive(sl);
                if (it != null && it.count > 0 && it.id == need[0] && (need[1] < 0 || it.damage == need[1])) src = sl;
            }
            if (src < 0) { StringBuilder sb = new StringBuilder(); for (int sl = invStart; sl < invEnd; sl++) { JItem it = getActive(sl); if (it != null) sb.append(" ").append(sl).append("=").append(it.id).append(":").append(it.damage).append("x").append(it.count); } Log.debug("[" + name() + "] missing ingredient " + need[0] + ":" + need[1] + " in" + sb); return; }
            click(window, src, 0);       // pick up the stack
            click(window, grid, 1);      // put one in the grid
            if (cursor != null) click(window, src, 0);   // put the rest back
        }
        JItem result = new JItem(r.outId, r.outCount, Math.max(0, r.outMeta), null, 0, 0);
        setActive(0, result);
        click(window, 0, 0);             // take the result
        if (cursor != null) {
            int dst = -1;
            for (int sl = invStart; sl < invEnd && dst < 0; sl++) {
                JItem it = getActive(sl);
                if (it != null && it.sameType(cursor) && it.count + cursor.count <= it.maxStack()) dst = sl;
            }
            for (int sl = invStart; sl < invEnd && dst < 0; sl++) if (getActive(sl) == null) dst = sl;
            click(window, dst < 0 ? -999 : dst, 0);
        }
    }

    /** Enchanting, anvils and trading don't work as plain "slot = item" moves. Returns true if it handled something. */
    private boolean specialContainerChanges(List<int[]> slots, List<int[]> items, List<byte[]> nbts) {
        boolean handled = false;
        if (winPeType == 3 && win.length >= 2 && win[0] != null && win[1] != null) {
            // the 3DS enchanted the item: lapis went down by 1-3 = the option it picked
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i)[0] != winId || slots.get(i)[1] != 1) continue;
                int used = win[1].count - items.get(i)[2];
                if (used < 1 || used > 3) break;
                java.send(new Buf().jvarint(0x06).put(winId).put(used - 1));
                win[1] = win[1].count - used > 0 ? win[1].withCount(win[1].count - used) : null;
                removeChanges(slots, items, nbts, winId, 0); removeChanges(slots, items, nbts, winId, 1);
                Log.debug("[" + name() + "] enchant option " + (used - 1));
                return true;
            }
        }
        if (winPeType == 5 && win.length >= 3 && win[0] != null) {
            // the 3DS took the repaired/renamed item: the left slot was emptied and the item appeared in the inventory
            int leftIdx = -1, dstIdx = -1;
            for (int i = 0; i < slots.size(); i++) {
                int[] sl = slots.get(i), it = items.get(i);
                if (sl[0] == winId && sl[1] == 0 && it[2] == 0) leftIdx = i;
                else if (sl[0] == 0 && it[2] > 0 && pePeSame(win[0], it)) dstIdx = i;
            }
            if (leftIdx >= 0 && dstIdx >= 0) {
                String newName = nameFromPeNbt(nbts.get(dstIdx));
                if (newName != null) java.send(new Buf().jvarint(0x09).str("MC|ItemName").str(newName));
                if (win[2] == null) win[2] = win[0];
                int dst = activeFromPE(0, slots.get(dstIdx)[1]);
                click(winId, 2, 0);
                if (cursor != null) click(winId, dst >= 0 ? dst : -999, 0);
                win[0] = null; win[1] = null;
                removeChanges(slots, items, nbts, winId, 0); removeChanges(slots, items, nbts, winId, 1); removeChanges(slots, items, nbts, winId, 2);
                removeChanges(slots, items, nbts, 0, slots.get(dstIdx)[1]);
                handled = true;
            }
        }
        if (winPeType == 15 && !trades.isEmpty()) {
            // pick the offer whose price matches what the 3DS put in the first trade slot
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i)[0] != winId || slots.get(i)[1] != 0 || items.get(i)[2] == 0) continue;
                for (int t = 0; t < trades.size(); t++) {
                    if (!pePeSame(trades.get(t)[0], items.get(i))) continue;
                    if (t != tradeSelected) {
                        tradeSelected = t;
                        java.send(new Buf().jvarint(0x09).str("MC|TrSel").beInt(t));
                        Log.debug("[" + name() + "] selected trade " + t);
                    }
                    break;
                }
            }
        }
        return handled;
    }

    private static void removeChanges(List<int[]> slots, List<int[]> items, List<byte[]> nbts, int window, int slot) {
        for (int i = slots.size() - 1; i >= 0; i--)
            if (slots.get(i)[0] == window && slots.get(i)[1] == slot) { slots.remove(i); items.remove(i); nbts.remove(i); }
    }

    /** custom name from a PE item's (little-endian) NBT: display { Name: "..." } */
    static String nameFromPeNbt(byte[] nbt) {
        if (nbt == null || nbt.length < 8) return null;
        byte[] key = {8, 4, 0, 'N', 'a', 'm', 'e'};
        outer:
        for (int i = 0; i + key.length + 2 <= nbt.length; i++) {
            for (int k = 0; k < key.length; k++) if (nbt[i + k] != key[k]) continue outer;
            int p = i + key.length;
            int len = (nbt[p] & 0xFF) | ((nbt[p + 1] & 0xFF) << 8);
            if (p + 2 + len > nbt.length) return null;
            return new String(nbt, p + 2, len, StandardCharsets.UTF_8);
        }
        return null;
    }

    String tradeTitle = "Villager";

    /** Java MC|TrList -> PE UpdateTrade (opens the 3DS trade screen) */
    private void handleTradeList(Buf in) throws IOException {
        int window = in.beInt();
        int n = in.u8();
        trades.clear();
        for (int i = 0; i < n; i++) {
            JItem a = JItem.read(in), out = JItem.read(in);
            JItem b = in.bool() ? JItem.read(in) : null;
            boolean disabled = in.bool();
            int uses = in.beInt(), max = in.beInt();
            trades.add(new JItem[]{a, b, out, disabled ? out : null});
            tradeUses.put(i, new int[]{uses, max});
        }
        if (window != winId || winPeType != 15) return;
        NBT.PEWriter w = new NBT.PEWriter().compoundList("Recipes", trades.size());
        for (int i = 0; i < trades.size(); i++) {
            JItem[] t = trades.get(i);
            tradeItem(w, "buyA", t[0]);
            if (t[1] != null) tradeItem(w, "buyB", t[1]);
            tradeItem(w, "sell", t[2]);
            int[] u = tradeUses.getOrDefault(i, new int[]{0, 7});
            w.integer("uses", t[3] != null ? u[1] : u[0]).integer("maxUses", u[1]).byteTag("rewardExp", 1);
            w.endCompound();
        }
        long trader = lastInteractTarget >= 0 ? lastInteractTarget : eid;
        send(new Buf().put(UPDATE_TRADE).put(winId).put(15).varint(0).varint(0).bool(true)
                .varlong(trader).varlong(eid).str(tradeTitle).put(w.end()));
        Log.debug("[" + name() + "] villager offers: " + trades.size());
    }
    private final Map<Integer, int[]> tradeUses = new HashMap<>();
    private static void tradeItem(NBT.PEWriter w, String key, JItem it) {
        int[] pe = it == null ? null : it.pe();
        w.compound(key).shortTag("id", pe == null ? 0 : pe[0]).shortTag("Damage", pe == null ? 0 : pe[1]).byteTag("Count", it == null ? 0 : it.count).endCompound();
    }

    // ---- boss bars: PE 1.1 shows the name + health of an entity, so each bar gets an invisible stand-in entity
    private final Map<UUID, long[]> bossBars = new LinkedHashMap<>();   // uuid -> {fake eid}
    private final Map<UUID, String> bossTitles = new HashMap<>();
    private final Map<UUID, Float> bossHealth = new HashMap<>();
    private int nextBossId = 0x7FF00000;
    private long lastBossMove;

    private void handleBossBar(Buf in) throws IOException {
        UUID id = in.jUUID();
        int action = in.jvarint();
        if (action == 0) {
            String title = Chat.forPE(Chat.toText(in.str()));
            float hp = in.beFloat();
            long fake = nextBossId++;
            bossBars.put(id, new long[]{fake}); bossTitles.put(id, title); bossHealth.put(id, hp);
            if (spawnSent) showBoss(id);
        } else if (action == 1) {
            long[] f = bossBars.remove(id); bossTitles.remove(id); bossHealth.remove(id);
            if (f != null && spawnSent) { send(new Buf().put(BOSS_EVENT).varlong(f[0]).uvarint(2)); send(new Buf().put(REMOVE_ENTITY).varlong(f[0])); }
        } else if (action == 2) {
            float hp = in.beFloat(); bossHealth.put(id, hp);
            long[] f = bossBars.get(id);
            if (f != null && spawnSent) sendBossHealth(f[0], hp);
        } else if (action == 3) {
            String title = Chat.forPE(Chat.toText(in.str())); bossTitles.put(id, title);
            long[] f = bossBars.get(id);
            if (f != null && spawnSent) sendBossName(f[0], title);
        }
    }
    private void showBoss(UUID id) {
        long fake = bossBars.get(id)[0];
        Buf ae = new Buf().put(ADD_ENTITY).varlong(fake).uvarint(fake).uvarint(37)   // slime, made invisible
                .peVec3((float) x, (float) y - 10, (float) z).peVec3(0, 0, 0).leFloat(0).leFloat(0).uvarint(0);
        long flags = (1L << 5) | (1L << 16);      // invisible, immobile
        ae.uvarint(3);
        ae.uvarint(0).uvarint(7).varlong(flags);
        ae.uvarint(4).uvarint(4).str(bossTitles.getOrDefault(id, ""));
        ae.uvarint(39).uvarint(3).leFloat(0f);    // scale 0
        ae.uvarint(0);
        send(ae);
        sendBossHealth(fake, bossHealth.getOrDefault(id, 1f));
        send(new Buf().put(BOSS_EVENT).varlong(fake).uvarint(0));
    }
    private void sendBossHealth(long fake, float hp) {
        Buf b = new Buf().put(UPDATE_ATTRIBUTES).uvarint(fake).uvarint(1);
        attr(b, 0, 100, Math.max(0.5f, hp * 100), 100, "minecraft:health");
        send(b);
    }
    private void sendBossName(long fake, String title) {
        send(new Buf().put(SET_ENTITY_DATA).uvarint(fake).uvarint(1).uvarint(4).uvarint(4).str(title));
    }
    private void bossTick() {
        if (bossBars.isEmpty() || !spawnSent) return;
        long now = System.currentTimeMillis();
        if (now - lastBossMove < 1000) return;
        lastBossMove = now;
        for (long[] f : bossBars.values())   // keep the stand-ins with the player so the bar doesn't disappear
            send(new Buf().put(MOVE_ENTITY).uvarint(f[0]).peVec3((float) x, (float) y - 10, (float) z).put(0).put(0).put(0).bool(false).bool(false));
    }

    // ---- sounds
    private void handleSound(Buf in) throws IOException {
        int sound = in.jvarint();
        in.jvarint();                                   // category
        float sx = in.beInt() / 8f, sy = in.beInt() / 8f, sz = in.beInt() / 8f;
        int[] m = Extras.SOUNDS.get(sound);
        if (m == null) return;
        playSound(m[0], (char) m[1], m[2], sx, sy, sz);
    }
    private void playSound(int event, char kind, int extra, float sx, float sy, float sz) {
        if (!spawnSent) return;
        if (event < 0) {   // a PE level event
            send(new Buf().put(LEVEL_EVENT).varint(-event).peVec3(sx, sy, sz).varint(0));
            return;
        }
        int data = 0;
        if (kind == 'm') { data = Mapping.mob(extra); if (data < 0) return; }
        else if (kind == 'p') data = extra;
        else if (kind == 'b') data = blockIdAt((int) Math.floor(sx), (int) Math.floor(sy), (int) Math.floor(sz));
        send(new Buf().put(LEVEL_SOUND).put(event).peVec3(sx, sy, sz).uvarint(data).varint(-1).bool(false));
    }

    /**
     * The 3DS sometimes reports the two halves of a move (item appears in the container / item leaves the hotbar)
     * in separate packets a little apart. If the changes so far only ADD items, wait up to 0.6 s for the other half.
     */
    private boolean waitForMatchingChange() {
        if (dropPending) return false;
        long now = System.currentTimeMillis();
        if (firstPeChange == 0) firstPeChange = now;
        if (winId > 0 && winPeType == 3 && win.length >= 2 && win[0] != null && now - firstPeChange <= 1500) {
            // taking things back out of the enchanting table: wait until both the item and the lapis are back,
            // so we know how much lapis the enchant used before taking them out on the server
            boolean itemBack = false, lapisBack = false;
            for (int i = 0; i < peChanges.size(); i++) {
                if (peChanges.get(i)[0] != 0) continue;
                int[] w = peChangeItems.get(i);
                int a = activeFromPE(0, peChanges.get(i)[1]); JItem cur = a >= 0 ? getActive(a) : null;
                int have = cur == null ? 0 : cur.count;
                if (w[0] == 351 && w[1] == 4 && w[2] > have) lapisBack = true;
                else if (w[2] > 0 && win[0].pe() != null && w[0] == win[0].pe()[0] && (cur == null || have < w[2])) itemBack = true;
            }
            if ((itemBack || pendingEnchant) && !lapisBack && win[1] != null) return true;
            if (lapisBack && !itemBack) return true;
        }
        if (now - firstPeChange > 600) return false;
        int gained = 0, lost = 0;
        for (int i = 0; i < peChanges.size(); i++) {
            int a = activeFromPE(peChanges.get(i)[0], peChanges.get(i)[1]);
            if (a < 0) continue;
            JItem cur = getActive(a); int[] w = peChangeItems.get(i);
            int have = cur == null ? 0 : cur.count;
            int want = w[2];
            if (cur != null && w[2] > 0 && !pePeSame(cur, w)) { gained += want; lost += have; continue; }
            if (want > have) gained += want - have; else lost += have - want;
        }
        return gained > 0 && lost == 0;
    }
    long firstPeChange;

    /**
     * The 3DS never reports the slots of its enchanting table (and similar menus): it only says "this left my
     * inventory" / "this came back". Work out which menu slot the item went to (or came from) ourselves.
     */
    private void resolveHiddenSlots(Map<Integer, int[]> want) {
        for (Integer k : want.keySet()) if (k < winSize) return;   // the 3DS did report menu slots: nothing to guess
        Map<Integer, int[]> extra = new LinkedHashMap<>();
        Map<Integer, JItem> planned = new HashMap<>();
        // what's coming back first: an enchant the 3DS did needs to happen on the server before we take things out
        int lapisBack = -1;
        for (Map.Entry<Integer, int[]> e : want.entrySet()) {
            JItem cur = getActive(e.getKey()); int[] w = e.getValue();
            int have = cur != null && pePeSame(cur, w) ? cur.count : 0;
            if (w[2] > have && winPeType == 3 && w[0] == 351 && w[1] == 4) lapisBack = w[2] - have;
        }
        if (pendingEnchant && winPeType == 3 && win.length >= 2 && win[0] != null && win[1] != null) {
            int used = lapisBack >= 0 ? win[1].count - lapisBack : 1;
            if (used < 1 || used > 3) used = 1;
            java.send(new Buf().jvarint(0x06).put(winId).put(used - 1));
            win[1] = win[1].count - used > 0 ? win[1].withCount(win[1].count - used) : null;
            pendingEnchant = false; enchantDoneFor = winId;
            Log.debug("[" + name() + "] enchant option " + (used - 1) + " (" + used + " lapis used)");
        }
        for (Map.Entry<Integer, int[]> e : want.entrySet()) {
            int sl = e.getKey(); int[] w = e.getValue();
            JItem cur = getActive(sl);
            boolean same = cur != null && w[2] > 0 && cur.pe() != null && cur.pe()[0] == w[0];
            int have = cur == null ? 0 : cur.count;
            if (cur != null && (!same || have > w[2])) {
                // left the inventory: into the menu
                int lost = same ? have - w[2] : have;
                int dst = hiddenSlotFor(cur, planned);
                if (dst < 0) continue;
                if (winPeType == 3 && dst == 0) enchantDoneFor = -1;   // a new item to enchant
                JItem there = planned.containsKey(dst) ? planned.get(dst) : win[dst];
                int total = (there != null && there.id == cur.id && there.damage == cur.damage ? there.count : 0) + lost;
                int[] pe = cur.pe();
                extra.put(dst, new int[]{pe[0], pe[1], total});
                planned.put(dst, cur.withCount(total));
            } else if (w[2] > (same ? have : 0)) {
                // came back from the menu
                int gained = w[2] - (same ? have : 0);
                for (int m = 0; m < winSize; m++) {
                    JItem it = planned.containsKey(m) ? planned.get(m) : win[m];
                    int[] pe = it == null ? null : it.pe();
                    if (pe == null || pe[0] != w[0] || (w[1] >= 0 && pe[1] != w[1] && it.id != 351)) continue;
                    int left = Math.max(0, it.count - gained);
                    extra.put(m, new int[]{pe[0], pe[1], left});
                    planned.put(m, left > 0 ? it.withCount(left) : null);
                    break;
                }
            }
        }
        if (!extra.isEmpty()) Log.debug("[" + name() + "] menu slots worked out: " + extra.keySet());
        want.putAll(extra);
    }
    boolean pendingEnchant;
    int enchantDoneFor = -1;   // window id we already enchanted in (one enchant per item placed)

    static boolean hasEnch(byte[] nbt) {
        if (nbt == null) return false;
        for (int i = 0; i + 4 <= nbt.length; i++) if (nbt[i] == 'e' && nbt[i + 1] == 'n' && nbt[i + 2] == 'c' && nbt[i + 3] == 'h') return true;
        return false;
    }

    /** which menu slot an item goes into (enchanting: item / lapis; brewing: bottles / ingredient / blaze powder) */
    private int hiddenSlotFor(JItem it, Map<Integer, JItem> planned) {
        if (winPeType == 3) return it.id == 351 && it.damage == 4 ? 1 : 0;
        if (winPeType == 5) { JItem a = planned.containsKey(0) ? planned.get(0) : win[0]; return a == null || (a.id == it.id && a.damage == it.damage) ? 0 : 1; }
        if (winPeType == 4) {
            if (it.id == 377) return 4;
            if (it.id == 373 || it.id == 374 || it.id == 438 || it.id == 441) {
                for (int i = 0; i < 3; i++) { JItem b = planned.containsKey(i) ? planned.get(i) : win[i]; if (b == null) return i; }
                return -1;
            }
            return 3;
        }
        return -1;
    }

    /** Turn the 3DS's reported slot contents into Java clicks. */
    private void processInventoryChanges() {
        List<int[]> slots = new ArrayList<>(peChanges);
        List<int[]> items = new ArrayList<>(peChangeItems);
        List<byte[]> nbts = new ArrayList<>(peChangeNbt);
        peChanges.clear(); peChangeItems.clear(); peChangeNbt.clear();
        firstPeChange = 0;
        boolean drop = dropPending; dropPending = false;
        if (java == null || !java.isPlay()) return;
        int window = winId > 0 ? winId : 0;
        if (winId > 0 && specialContainerChanges(slots, items, nbts)) { sendContainerContents(); sendInventory(); }
        // desired state per Java slot
        Map<Integer, int[]> want = new LinkedHashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            int a = activeFromPE(slots.get(i)[0], slots.get(i)[1]);
            if (a >= 0) want.put(a, items.get(i));
        }
        if (winId > 0 && winPeType == 3) {
            // the item comes back from the 3DS's table already enchanted: the enchant happened (even if its
            // "enchanted" message hasn't arrived yet)
            for (int i = 0; i < slots.size(); i++) if (slots.get(i)[0] == 0 && hasEnch(nbts.get(i)) && enchantDoneFor != winId) pendingEnchant = true;
        }
        if (winId > 0 && (winPeType == 3 || winPeType == 4 || winPeType == 5)) resolveHiddenSlots(want);
        if (gamemode == 1 && winId <= 0) { // creative: just set the slots
            for (Map.Entry<Integer, int[]> e : want.entrySet()) {
                JItem it = identify(e.getValue(), e.getKey());
                inv[e.getKey()] = it;
                Buf b = new Buf().jvarint(0x1B).beShort(e.getKey());
                JItem.write(b, it);
                java.send(b);
            }
            return;
        }
        Integer origin = null;
        for (int step = 0; step < 200; step++) {
            boolean progress = false;
            if (cursor == null) {
                // pick up from a slot that has more than the 3DS wants there
                for (Map.Entry<Integer, int[]> e : want.entrySet()) {
                    int s0 = e.getKey(); int[] w = e.getValue(); JItem cur = getActive(s0);
                    if (cur == null) continue;
                    boolean same = pePeSame(cur, w) && w[2] > 0;
                    int keep = same ? w[2] : 0;
                    if (cur.count <= keep) continue;
                    click(window, s0, 0);                               // pick up the whole stack
                    for (int r = 0; r < keep; r++) click(window, s0, 1); // put back what should stay
                    if (origin == null) origin = s0;
                    progress = true;
                    break;
                }
            } else {
                // put the cursor down where the 3DS wants this item
                for (Map.Entry<Integer, int[]> e : want.entrySet()) {
                    int s0 = e.getKey(); int[] w = e.getValue(); JItem cur = getActive(s0);
                    if (w[2] <= 0 || !pePeSame(cursor, w)) continue;
                    int have = cur != null && cur.sameType(cursor) ? cur.count : 0;
                    if (cur != null && !cur.sameType(cursor)) { click(window, s0, 0); progress = true; break; } // swap
                    int need = w[2] - have;
                    if (need <= 0) continue;
                    if (cursor.count <= need) click(window, s0, 0);
                    else for (int r = 0; r < need; r++) click(window, s0, 1);
                    progress = true;
                    break;
                }
            }
            if (!progress) break;
        }
        if (cursor != null) {
            if (drop) click(window, -999, 0);                 // the 3DS threw the items away
            else if (origin != null) click(window, origin, 0); // put leftovers back
        }
        // make sure the 3DS shows exactly what the server has (the server will also correct us if needed)
        if (winId > 0) sendContainerContents();
        sendInventory();
    }

    /** Find the Java item (with its real NBT) that the 3DS item corresponds to. */
    private JItem identify(int[] pe, int slot) {
        if (pe[0] == 0 || pe[2] == 0) return null;
        List<JItem> candidates = new ArrayList<>();
        candidates.add(getActive(slot)); candidates.add(cursor);
        for (JItem it : inv) candidates.add(it);
        for (JItem it : win) candidates.add(it);
        for (JItem it : candidates) if (it != null && pePeSame(it, pe)) return it.withCount(pe[2]);
        int[] j = Mapping.itemToJava(pe[0], Math.max(0, pe[1]));
        return new JItem(j[0], pe[2], j[1], null, 0, 0);
    }

    /** Send one Java click and apply what it does to our copy. button 0 = left, 1 = right. */
    private void click(int window, int slot, int button) {
        JItem before = slot == -999 ? null : getActive(slot);
        if (Log.debug) Log.debug("[" + name() + "] click window=" + window + " slot=" + slot + (button == 0 ? " left" : " right") + " (slot had " + (before == null ? "nothing" : before.id + ":" + before.damage + " x" + before.count) + ")");
        Buf b = new Buf().jvarint(0x07).put(window).beShort(slot).put(button).beShort(clickAction++).jvarint(0);
        JItem.write(b, before);
        java.send(b);
        if (slot == -999) { if (button == 0) cursor = null; else if (cursor != null && --cursor.count <= 0) cursor = null; return; }
        JItem s0 = before;
        if (button == 0) {
            if (cursor == null) { cursor = s0; setActive(slot, null); }
            else if (s0 == null) { setActive(slot, cursor); cursor = null; }
            else if (s0.sameType(cursor)) {
                int move = Math.min(cursor.count, s0.maxStack() - s0.count);
                if (move > 0) { setActive(slot, s0.withCount(s0.count + move)); cursor = cursor.count - move > 0 ? cursor.withCount(cursor.count - move) : null; }
            } else { setActive(slot, cursor); cursor = s0; }
        } else {
            if (cursor == null) {
                if (s0 != null) { int take = (s0.count + 1) / 2; cursor = s0.withCount(take); setActive(slot, s0.count - take > 0 ? s0.withCount(s0.count - take) : null); }
            } else if (s0 == null || (s0.sameType(cursor) && s0.count < s0.maxStack())) {
                setActive(slot, s0 == null ? cursor.withCount(1) : s0.withCount(s0.count + 1));
                cursor = cursor.count - 1 > 0 ? cursor.withCount(cursor.count - 1) : null;
            } else if (!s0.sameType(cursor)) { setActive(slot, cursor); cursor = s0; }
        }
    }

    /** PE 1.1 only lets you run commands it knows about: send it the server's commands, each taking free text. */
    private void sendCommandList() {
        java.util.TreeSet<String> all = new java.util.TreeSet<>(commands);
        if (all.isEmpty()) all.addAll(Arrays.asList(DEFAULT_COMMANDS)); // server didn't answer (yet)
        all.add("3ds");
        StringBuilder j = new StringBuilder("{");
        boolean first = true;
        for (String c : all) {
            if (!c.matches("[a-z0-9_\\-]+")) continue;
            if (!first) j.append(',');
            first = false;
            j.append('"').append(c).append("\":{\"versions\":[{\"aliases\":[],\"description\":\"")
             .append(c.equals("3ds") ? "3DS bridge settings" : "Server command").append("\",")
             .append("\"overloads\":{\"default\":{\"input\":{\"parameters\":[{\"name\":\"args\",\"type\":\"rawtext\",\"optional\":true}]},\"output\":{}}},")
             .append("\"permission\":\"any\"}]}");
        }
        j.append('}');
        send(new Buf().put(AVAILABLE_COMMANDS).str(j.toString()).str(""));
        commandsSent = true;
    }

    /** Commands handled by the bridge itself (not sent to the server). */
    private void bridgeCommand(String msg) {
        String[] a = msg.trim().split("\\s+");
        if (a.length >= 3 && a[1].equalsIgnoreCase("speed")) {
            try {
                float m = Float.parseFloat(a[2]);
                if (m < 0.3f || m > 1.5f) throw new NumberFormatException();
                speedMultiplier = m;
                sendAttributes();
                send(text(0, "§a[3DS Bridge] Walking speed set to " + m + "x. Put speed-multiplier=" + m + " in bridge.properties to keep it."));
                Log.info("[" + username + "] set speed multiplier to " + m);
            } catch (NumberFormatException e) {
                send(text(0, "§c[3DS Bridge] Use a number between 0.3 and 1.5, e.g. /3ds speed 0.8"));
            }
        } else {
            send(text(0, "§e[3DS Bridge] " + Main.VERSION + " - speed " + speedMultiplier + "x. Commands: /3ds speed <0.3-1.5>"));
        }
    }

    // ---------------------------------------------------------------- block breaking
    // The 3DS decides when a block breaks using its own (PE) rules. The Java server only accepts a break
    // after the real Java breaking time, so: the 3DS keeps showing the block until the server says it's
    // gone, and we tell the server "finished" exactly when a Java client would.

    private int javaBlockAt(int bx, int by, int bz) {
        Mapping.PEColumn col = chunks.get(key(bx >> 4, bz >> 4));
        return col == null ? 0 : col.getJavaState(bx, by, bz);
    }

    long breakMillis(int bx, int by, int bz) {
        int[] held = heldSlot >= 0 && heldSlot < 9 && inv[36 + heldSlot] != null ? inv[36 + heldSlot].asBreakArray() : null;
        int eye = javaBlockAt((int) Math.floor(x), (int) Math.floor(y + EYE), (int) Math.floor(z)) >> 4;
        boolean aqua = inv[5] != null && inv[5].aqua > 0;
        boolean water = (eye == 8 || eye == 9) && !aqua;
        return BreakTime.millis(javaBlockAt(bx, by, bz), held, haste, fatigue, water, onGround);
    }

    private void startBreak(int bx, int by, int bz, int face) {
        long k = Buf.jPos(bx, by, bz);
        if (k == finishKey) return;                 // still waiting for the server to break this one
        if (finishKey != Long.MIN_VALUE) sendFinish(); // started another block: let the server finish the old one
        digKey = k; digStart = System.currentTimeMillis(); lastDigLog = k;
        javaDig(0, bx, by, bz, face);
        javaSwing();
        if (Log.debug) Log.debug("[" + name() + "] dig start " + bx + "," + by + "," + bz + " block " + (javaBlockAt(bx, by, bz) >> 4)
                + " java time " + breakMillis(bx, by, bz) + "ms");
    }

    private void abortBreak(int bx, int by, int bz, int face) {
        long k = Buf.jPos(bx, by, bz);
        if (k == finishKey) return;
        if (k == digKey) digKey = Long.MIN_VALUE;
        javaDig(1, bx, by, bz, face);
    }

    private void finishBreak(int bx, int by, int bz) {
        long k = Buf.jPos(bx, by, bz);
        lastDigLog = k;
        if (Log.debug) Log.debug("[" + name() + "] 3DS says block " + bx + "," + by + "," + bz + " is broken (gamemode " + gamemode + ")");
        if (k == finishKey) return;
        if (gamemode == 1) { javaDig(0, bx, by, bz, 1); javaSwing(); return; } // creative: instant
        long now = System.currentTimeMillis();
        if (k != digKey) { digKey = k; digStart = now; javaDig(0, bx, by, bz, 1); }
        long need = breakMillis(bx, by, bz);
        if (need < 0) { restoreBlock(bx, by, bz); digKey = Long.MIN_VALUE; return; } // unbreakable
        finishKey = k; finishX = bx; finishY = by; finishZ = bz;
        finishAt = digStart + need;
        if (now + 25 >= finishAt) { sendFinish(); return; }
        // the 3DS was faster than Java: keep the block visible until the server breaks it
        restoreBlock(bx, by, bz);
        if (Log.debug) Log.debug("[" + name() + "] 3DS broke block early, finishing in " + (finishAt - now) + "ms");
    }

    private void sendFinish() {
        if (finishKey == Long.MIN_VALUE) return;
        if (Log.debug) Log.debug("[" + name() + "] dig finish sent " + finishX + "," + finishY + "," + finishZ);
        javaDig(2, finishX, finishY, finishZ, 1);
        javaSwing();
        finishKey = Long.MIN_VALUE;
        digKey = Long.MIN_VALUE;
    }

    private void restoreBlock(int bx, int by, int bz) {
        Mapping.PEColumn col = chunks.get(key(bx >> 4, bz >> 4));
        if (col == null) return;
        int js = col.getJavaState(bx, by, bz);
        int pe = Mapping.block(js);
        send(new Buf().put(UPDATE_BLOCK).peBlockPos(bx, by, bz).uvarint(pe >> 4).uvarint((0b1011 << 4) | (pe & 0xF)));
        if (Mapping.hasTile(pe >> 4)) sendBlockEntity(col, bx, by, bz);
    }

    private void handleLogin(Buf in) throws IOException {
        if (username != null) return;
        int protocol = in.beInt();
        int edition = in.u8();
        byte[] body = in.strRaw();
        Buf b = new Buf(body);
        String chain = new String(b.bytes(b.leInt()), StandardCharsets.UTF_8);
        String clientData = "";
        try { clientData = new String(b.bytes(b.leInt()), StandardCharsets.UTF_8); } catch (IOException ignored) { }
        String name = parseName(chain);
        if (name == null) name = "3DS_" + Integer.toHexString(rak.address.hashCode() & 0xFFFF);
        name = name.replaceAll("[^A-Za-z0-9_]", "_");
        if (name.length() > 15) name = name.substring(0, 15);
        if (name.isEmpty()) name = "3DSPlayer";
        username = name;
        Log.info("[" + name + "] Login from " + rak.address + " (PE protocol " + protocol + ", edition " + edition + ")");
        Log.debug("[" + name + "] client data: " + (clientData.length() > 200 ? clientData.substring(0, 200) + "..." : clientData));
        parseSkin(clientData);
        if (protocol != 113) Log.warn("[" + name + "] Expected protocol 113 but client sent " + protocol + " - things may break");

        send(new Buf().put(PLAY_STATUS).beInt(0));
        send(new Buf().put(RP_INFO).bool(false).leShort(0).leShort(0));
        flush();
        connectJava();
    }

    private void connectJava() {
        String host = cfg.javaHandshakeHost.isEmpty() ? cfg.javaHost : cfg.javaHandshakeHost;
        String loginName = username;
        if (main.floodgate != null) {
            try {
                long xuid = 2535400000000000L + (Math.abs((long) username.toLowerCase().hashCode()) % 100000000000L);
                host = host + "\0" + main.floodgate.encode(username, xuid, rak.address.getAddress().getHostAddress());
            } catch (Exception e) {
                Log.err("Floodgate encryption failed", e);
            }
        }
        java = new JavaConn(this);
        java.connect(cfg.javaHost, cfg.javaPort, host, loginName);
    }

    byte[] ownSkin; boolean ownSkinSlim;

    /** The 3DS sends its skin in the login's client-data token (SkinData = base64 RGBA, SkinId tells the model). */
    private void parseSkin(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return;
            String payload = new String(Base64.getUrlDecoder().decode(parts[1].replace('+', '-').replace('/', '_').replace("=", "")), StandardCharsets.UTF_8);
            Map<?, ?> m = (Map<?, ?>) new Chat.Parser(payload).value();
            Object data = m.get("SkinData"), id = m.get("SkinId");
            if (data == null) return;
            ownSkin = Base64.getDecoder().decode(String.valueOf(data).replaceAll("\\s", ""));
            ownSkinSlim = id != null && String.valueOf(id).toLowerCase().contains("slim");
            Log.debug("[" + name() + "] 3DS skin: " + ownSkin.length + " bytes, id " + id);
        } catch (Exception e) {
            Log.debug("Could not read the 3DS skin: " + e);
        }
    }

    static String parseName(String chainJson) {
        try {
            Object root = new Chat.Parser(chainJson).value();
            @SuppressWarnings("unchecked") List<Object> chain = (List<Object>) ((Map<String, Object>) root).get("chain");
            for (Object t : chain) {
                String[] parts = String.valueOf(t).split("\\.");
                if (parts.length < 2) continue;
                String payload = new String(Base64.getUrlDecoder().decode(pad(parts[1].replace('+', '-').replace('/', '_'))), StandardCharsets.UTF_8);
                Object p = new Chat.Parser(payload).value();
                @SuppressWarnings("unchecked") Map<String, Object> extra = (Map<String, Object>) ((Map<String, Object>) p).get("extraData");
                if (extra != null && extra.get("displayName") != null) return String.valueOf(extra.get("displayName"));
            }
        } catch (Exception e) {
            Log.debug("Could not parse login chain: " + e);
        }
        return null;
    }
    private static String pad(String s) { return s.replace("=", ""); }

    // =====================================================================================
    //  Java side - receive
    // =====================================================================================
    @Override
    public synchronized void onJavaLoginSuccess(String uuid, String name) {
        Log.info("[" + username + "] Joined the Java server as " + name + " (" + uuid + ")");
        try { javaUuid = UUID.fromString(uuid); } catch (Exception ignored) { }
        javaJoinTime = System.currentTimeMillis();
        try { SkinUpload.apply(cfg, UUID.fromString(uuid), username, ownSkin, ownSkinSlim); } catch (Exception ignored) { }
    }

    @Override
    public synchronized void onJavaDisconnect(String reason) {
        if (closed) return;
        Log.info("[" + username + "] Java connection closed: " + reason);
        disconnect(reason);
    }

    /** A real dimension change: tell the 3DS, then wait for terrain and "spawn" it again like at login. */
    private void commitDimension() {
        int dim = pendingDim;
        pendingDim = Integer.MIN_VALUE;
        dimension = dim;
        chunks.clear(); sentChunks.clear(); serverFetched.clear(); serverPending.clear(); javaReal.clear();
        clearEntities();
        send(new Buf().put(CHANGE_DIMENSION).varint(peDim(dim)).peVec3((float) x, (float) y + EYE, (float) z).bool(false));
        spawnSent = false;                    // the tick will send PLAYER_SPAWN once terrain is there
        startGameTime = System.currentTimeMillis();
        Log.info("[" + name() + "] changed dimension to " + dim);
    }

    @Override
    public synchronized void onJavaPacket(int id, Buf in) throws IOException {
        if (closed) return;
        if (!spawnSent) preSpawnPackets.merge(id, 1, Integer::sum);
        if (pendingDim != Integer.MIN_VALUE && (id == 0x2F || id == 0x20)) commitDimension();
        if (Log.debug && id != 0x26 && id != 0x27 && id != 0x28 && id != 0x36 && id != 0x3C && id != 0x3E)
            Log.debug("[" + name() + "] Java -> 0x" + Integer.toHexString(id) + " (" + in.remaining() + " bytes)");
        switch (id) {
            case 0x23: { // join game
                eid = in.beInt();
                gamemode = in.u8() & 0x7;
                dimension = in.beInt();
                difficulty = in.u8();
                javaJoined = true;
                sendClientSettings(radius);
                java.send(new Buf().jvarint(0x09).str("MC|Brand").str("vanilla"));
                java.send(new Buf().jvarint(0x01).str("/").bool(false).bool(false)); // tab-complete "/" = list my commands
                break;
            }
            case 0x2F: { // player position & look
                double px = in.beDouble(), py = in.beDouble(), pz = in.beDouble();
                float pyaw = in.beFloat(), ppitch = in.beFloat();
                int flags = in.u8();
                int tp = in.jvarint();
                x = (flags & 1) != 0 ? x + px : px;
                y = (flags & 2) != 0 ? y + py : py;
                z = (flags & 4) != 0 ? z + pz : pz;
                yaw = (flags & 8) != 0 ? yaw + pyaw : pyaw;
                pitch = (flags & 16) != 0 ? pitch + ppitch : ppitch;
                if (spawnSent && Log.debug && !awaitingRespawn)
                    Log.debug("[" + name() + "] server moved us to " + String.format("%.4f %.4f %.4f", x, y, z) + " (flags " + flags + ")");
                java.send(new Buf().jvarint(0x00).jvarint(tp));
                sendJavaPosition();
                moveDirty = lookDirty = false;
                tpX = x; tpY = y; tpZ = z; tpPendingUntil = spawnSent ? System.currentTimeMillis() + 1500 : 0;
                havePos = true;
                if (!startGameSent) tryStartGame();
                else {
                    if (dead) {
                        // the server respawned us: tell the 3DS where (this enables Respawn / Main menu),
                        // load the terrain there, but keep it on the death screen until the button is pressed
                        serverRespawned = true;
                        lastChunkX = Integer.MIN_VALUE;
                        sendNearbyChunks(true);
                        if (!respawnSentToPE) {
                            respawnSentToPE = true;
                            respawnX = x; respawnY = y; respawnZ = z;
                            send(new Buf().put(RESPAWN).peVec3((float) x, (float) y + EYE, (float) z));
                            Log.debug("[" + name() + "] respawn point " + String.format("%.1f %.1f %.1f", x, y, z));
                        }
                    } else {
                        sendNearbyChunks(false);   // terrain first, so the 3DS isn't moved into an empty area
                        sendMovePlayer(1);
                    }
                    if (spawnSent) checkHeightLimit();
                }
                break;
            }
            case 0x1F: java.send(new Buf().jvarint(0x0B).beLong(in.beLong())); break; // keep alive
            case 0x11: { // confirm transaction (modern servers' "ping"); a real 1.12 client always answers these
                int window = in.s8();
                int action = (short) in.beShort();
                boolean accepted = in.bool();
                if (!accepted) java.send(new Buf().jvarint(0x05).put(window).beShort(action).bool(true));
                break;
            }
            case 0x0F: { // chat
                String raw = in.str();
                if (Log.debug) Log.debug("[" + name() + "] chat json: " + (raw.length() > 300 ? raw.substring(0, 300) + "..." : raw));
                String text = Chat.forPE(Chat.toText(raw));
                int pos = in.eof() ? 0 : in.u8();
                if (pos == 2) send(text(4, text)); // action bar -> tip
                else for (String line : text.split("\n")) send(text(0, line));
                break;
            }
            case 0x1A: disconnect(Chat.forPE(Chat.toText(in.str()))); break;
            case 0x20: { // chunk data
                int cx = in.beInt(), cz = in.beInt();
                boolean full = in.bool();
                if (full) javaFullChunks++; else javaPartialChunks++;
                long key = key(cx, cz);
                Mapping.PEColumn col = Mapping.readJavaChunk(in, cx, cz, full, dimension == 0, chunks.get(key));
                chunks.put(key, col);
                if (full && !col.isEmpty()) javaReal.add(key);
                // A chunk can arrive again (e.g. an empty placeholder first, real terrain later): always resend it.
                sentChunks.remove(key);
                if (startGameSent && inRadius(cx, cz)) sendChunk(key);
                break;
            }
            case 0x1D: { long k = key(in.beInt(), in.beInt()); chunks.remove(k); sentChunks.remove(k); javaReal.remove(k); javaUnloads++; break; }
            case 0x0B: { // block change
                int[] p = Buf.jPos(in.beLong());
                int state = in.jvarint();
                setBlock(p[0], p[1], p[2], state);
                break;
            }
            case 0x10: { // multi block change
                int cx = in.beInt(), cz = in.beInt();
                int n = in.jvarint();
                for (int i = 0; i < n; i++) {
                    int xz = in.u8(), by = in.u8(), state = in.jvarint();
                    setBlock(cx * 16 + (xz >> 4), by, cz * 16 + (xz & 15), state);
                }
                break;
            }
            case 0x35: { // respawn
                int dim = in.beInt();
                difficulty = in.u8();
                int gm = in.u8() & 0x7;
                if (gm != gamemode) { gamemode = gm; sendGamemode(); }
                if (pendingDim != Integer.MIN_VALUE && dim == dimension) {
                    // went to another dimension and straight back (how a 1.12 connection gets "refreshed",
                    // e.g. by plugins): nothing changes for the 3DS
                    pendingDim = Integer.MIN_VALUE;
                    clearEntities();      // the server re-sends everything around us
                    Log.debug("[" + name() + "] ignored dimension bounce");
                    break;
                }
                if (dim != dimension) {
                    pendingDim = dim;     // decide once we see whether it's real (terrain/position) or a bounce
                    break;
                }
                clearEntities();          // the server re-sends everything around us
                break;
            }
            case 0x1E: { // change game state
                int reason = in.u8();
                float value = in.beFloat();
                if (reason == 3) { gamemode = (int) value; sendGamemode(); }
                break;
            }
            case 0x2C: { // abilities
                int flags = in.u8();
                sendAdventureSettings((flags & 0x04) != 0, (flags & 0x02) != 0);
                break;
            }
            case 0x09: { // block entity update (bed colour, sign text, ...)
                int[] p = Buf.jPos(in.beLong());
                in.u8(); // action
                java.util.Map<String, Object> t = NBT.readRoot(in);
                long k = key(p[0] >> 4, p[2] >> 4);
                Mapping.PEColumn col = chunks.get(k);
                if (col != null) {
                    col.javaTiles.put(Mapping.posKey(p[0], p[1], p[2]), t);
                    if (sentChunks.contains(k)) sendBlockEntity(col, p[0], p[1], p[2]);
                }
                break;
            }
            case 0x4F: { // entity effect
                int e = in.jvarint(); int effect = in.u8(); int amp = in.s8();
                if (e == eid) { if (effect == 3) haste = amp; else if (effect == 4) fatigue = amp; }
                break;
            }
            case 0x33: { // remove effect
                int e = in.jvarint(); int effect = in.u8();
                if (e == eid) { if (effect == 3) haste = -1; else if (effect == 4) fatigue = -1; }
                break;
            }
            case 0x13: { // open window
                int wid = in.u8();
                String type = in.str();
                String title = Chat.forPE(Chat.toText(in.str()));
                int slots = in.u8();
                int peType = peWindowType(type);
                if (peType == 15) {   // villager: the 3DS opens its trade screen when the offers (MC|TrList) arrive
                    winId = wid; winSize = Math.max(3, slots); winPeType = 15; win = new JItem[winSize]; trades.clear(); tradeSelected = -1;
                    tradeTitle = title.isEmpty() ? "Villager" : title;
                    break;
                }
                if (peType < 0 || lastUseY < 0) {
                    java.send(new Buf().jvarint(0x08).put(wid));   // close it on the server too
                    send(text(0, "§e[3DS Bridge] This kind of menu (" + type.replace("minecraft:", "") + ") isn't supported on the 3DS yet."));
                    break;
                }
                // 1.12 reports 0 slots for menus without storage: use the real layout
                if (peType == 1) slots = 10; else if (peType == 3) slots = Math.max(2, slots); else if (peType == 5) slots = Math.max(3, slots); else if (peType == 4) slots = Math.max(5, slots);
                winId = wid; winSize = slots; winPeType = peType; win = new JItem[slots];
                if (peType == 0 && slots >= 54) pairChest(lastUseX, lastUseY, lastUseZ);
                send(new Buf().put(CONTAINER_OPEN).put(wid).put(peType).peBlockPos(lastUseX, lastUseY, lastUseZ).varlong(-1));
                Log.debug("[" + name() + "] opened " + type + " (" + slots + " slots) '" + title + "'");
                break;
            }
            case 0x18: { // plugin message
                String ch = in.str();
                if (ch.equals("MC|TrList")) handleTradeList(in);
                break;
            }
            case 0x48: { // title
                int action = in.jvarint();
                if (!spawnSent) break;
                switch (action) {
                    case 0: send(new Buf().put(SET_TITLE).varint(2).str(Chat.forPE(Chat.toText(in.str()))).varint(0).varint(0).varint(0)); break;
                    case 1: send(new Buf().put(SET_TITLE).varint(3).str(Chat.forPE(Chat.toText(in.str()))).varint(0).varint(0).varint(0)); break;
                    case 2: send(text(4, Chat.forPE(Chat.toText(in.str())))); break;
                    case 3: { int fi = in.beInt(), st = in.beInt(), fo = in.beInt(); send(new Buf().put(SET_TITLE).varint(5).str("").varint(fi).varint(st).varint(fo)); break; }
                    case 4: send(new Buf().put(SET_TITLE).varint(0).str("").varint(0).varint(0).varint(0)); break;
                    case 5: send(new Buf().put(SET_TITLE).varint(1).str("").varint(0).varint(0).varint(0)); break;
                    default: break;
                }
                break;
            }
            case 0x0C: handleBossBar(in); break; // boss bar
            case 0x49: handleSound(in); break;   // sound effect
            case 0x21: { // effect: block break particles + sound
                int effect = in.beInt();
                long pos = in.beLong();
                int data = in.beInt();
                if (effect == 2001 && spawnSent) {
                    int bx = (int) (pos >> 38), by = (int) ((pos >> 26) & 0xFFF), bz = (int) (pos << 38 >> 38);
                    int javaState = ((data & 0xFFF) << 4) | ((data >> 12) & 0xF);
                    int pe = Mapping.block(javaState);
                    send(new Buf().put(LEVEL_EVENT).varint(2001).peVec3(bx + 0.5f, by + 0.5f, bz + 0.5f).varint((pe >> 4) | ((pe & 0xF) << 8)));
                    send(new Buf().put(LEVEL_SOUND).put(4).peVec3(bx + 0.5f, by + 0.5f, bz + 0.5f).uvarint(pe >> 4).varint(-1).bool(false));
                }
                break;
            }
            case 0x12: { // close window (server side)
                int wid = in.u8();
                if (wid == winId && winId > 0) { send(new Buf().put(CONTAINER_CLOSE).put(wid)); closeContainerState(); }
                break;
            }
            case 0x15: { // window property (furnace progress)
                int wid = in.u8(); int prop = (short) in.beShort(); int val = (short) in.beShort();
                if (wid == winId && winPeType == 2) {
                    int peProp = prop == 2 ? 0 : prop == 0 ? 1 : prop == 1 ? 2 : -1;
                    if (peProp >= 0) send(new Buf().put(CONTAINER_SET_DATA).put(wid).varint(peProp).varint(val));
                } else if (wid == winId && winPeType == 4) {     // brewing: 0 = brew time, 1 = fuel
                    send(new Buf().put(CONTAINER_SET_DATA).put(wid).varint(prop).varint(val));
                }
                break;
            }
            case 0x0E: { // tab-complete answer (our "/" request -> the commands this player may use)
                int n = in.jvarint();
                for (int i = 0; i < n; i++) {
                    String c = in.str().trim();
                    if (c.startsWith("/")) c = c.substring(1);
                    if (c.isEmpty() || c.contains(":") || c.contains(" ")) continue;
                    commands.add(c.toLowerCase());
                }
                Log.debug("[" + name() + "] server has " + commands.size() + " commands for this player");
                if (spawnSent) sendCommandList();
                break;
            }
            case 0x46: { // world spawn position (also where you respawn unless you have a bed)
                int[] p = Buf.jPos(in.beLong());
                worldSpawnX = p[0]; worldSpawnY = p[1]; worldSpawnZ = p[2];
                break;
            }
            case 0x41: { // health
                health = in.beFloat(); food = in.jvarint(); saturation = in.beFloat();
                if (startGameSent && !(dead && health > 0)) {
                    sendAttributes();
                    if (health <= 0 && !dead) {
                        // Like PocketMine: the 3DS must be told the REAL respawn point (it moves itself there when you
                        // press Respawn, and the buttons stay disabled until then). We only learn it by respawning, so
                        // respawn on the server right away and keep the 3DS on its death screen meanwhile.
                        dead = true; serverRespawned = false; respawnSentToPE = false;
                        sleeping = false;
                        java.send(new Buf().jvarint(0x03).jvarint(0));
                        Log.info("[" + username + "] died");
                    }
                }
                break;
            }
            case 0x40: { // experience
                xpProgress = in.beFloat(); xpLevel = in.jvarint(); in.jvarint();
                if (startGameSent && !dead) sendAttributes();
                break;
            }
            case 0x47: { // time
                in.beLong();
                long time = in.beLong();
                send(new Buf().put(SET_TIME).varint((int) (Math.abs(time) % 24000)));
                break;
            }
            case 0x2E: handlePlayerList(in); break;
            case 0x4E: { // entity properties -> movement speed for our own player
                int e = in.jvarint();
                int count = in.beInt();
                for (int i = 0; i < count; i++) {
                    String k = in.str();
                    double base = in.beDouble();
                    int mods = in.jvarint();
                    double add = 0, mulBase = 0, mulTotal = 1;
                    for (int m = 0; m < mods; m++) {
                        UUID mu = in.jUUID();
                        double amount = in.beDouble();
                        int op = in.u8();
                        if (SPRINT_MODIFIER.equals(mu)) continue; // the 3DS applies sprinting itself
                        if (op == 0) add += amount; else if (op == 1) mulBase += amount; else mulTotal *= 1 + amount;
                    }
                    if (e == eid && k.equals("generic.movementSpeed")) {
                        double v = (base + add) * (1 + mulBase) * mulTotal;
                        float nv = (float) Math.max(0, Math.min(1, v));
                        if (Math.abs(nv - moveSpeed) > 1e-4) { moveSpeed = nv; if (startGameSent) sendAttributes(); }
                    }
                }
                break;
            }
            case 0x05: { // spawn player
                int e = in.jvarint();
                UUID u = in.jUUID();
                double ex = in.beDouble(), ey = in.beDouble(), ez = in.beDouble();
                float eyaw = in.s8() * 360f / 256f, epitch = in.s8() * 360f / 256f;
                Ent ent = new Ent(); ent.player = true; ent.uuid = u; ent.x = ex; ent.y = ey; ent.z = ez; ent.yaw = eyaw; ent.headYaw = eyaw; ent.pitch = epitch;
                entities.put(e, ent);
                showEntity(e, ent);
                break;
            }
            case 0x03: { // spawn mob
                int e = in.jvarint();
                UUID u = in.jUUID();
                int type = in.jvarint();
                double ex = in.beDouble(), ey = in.beDouble(), ez = in.beDouble();
                float eyaw = in.s8() * 360f / 256f, epitch = in.s8() * 360f / 256f, ehead = in.s8() * 360f / 256f;
                int peType = Mapping.mob(type);
                if (peType < 0) break;
                Ent ent = new Ent(); ent.peType = peType; ent.x = ex; ent.y = ey; ent.z = ez; ent.yaw = eyaw; ent.pitch = epitch; ent.headYaw = ehead;
                entities.put(e, ent);
                showEntity(e, ent);
                break;
            }
            case 0x00: { // spawn object - dropped items are type 2
                int e = in.jvarint();
                in.jUUID();
                int type = in.u8();
                double ex = in.beDouble(), ey = in.beDouble(), ez = in.beDouble();
                float opitch = in.s8() * 360f / 256f, oyaw = in.s8() * 360f / 256f;
                in.beInt();                                             // object data
                float vx = (short) in.beShort() / 8000f, vy = (short) in.beShort() / 8000f, vz = (short) in.beShort() / 8000f;
                if (type == 2) {
                    Ent ent = new Ent(); ent.isItem = true; ent.x = ex; ent.y = ey; ent.z = ez;
                    entities.put(e, ent);   // shown once the server tells us which item it is (metadata)
                    break;
                }
                int pe = projectileType(type);
                if (pe < 0) break;
                Ent ent = new Ent(); ent.peType = pe; ent.x = ex; ent.y = ey; ent.z = ez; ent.yaw = oyaw; ent.pitch = opitch; ent.headYaw = oyaw;
                ent.vx = vx; ent.vy = vy; ent.vz = vz;
                entities.put(e, ent);
                showEntity(e, ent);
                break;
            }
            case 0x3C: { // entity metadata - for dropped items: index 6 = the item stack
                int e = in.jvarint();
                Ent ent = entities.get(e);
                if (ent == null || !ent.isItem) break;
                JItem it = readMetadataItem(in);
                if (it != null) { ent.item = it; showEntity(e, ent); }
                break;
            }
            case 0x3F: { // entity equipment
                int e = in.jvarint(); int slot = in.jvarint(); JItem it = JItem.read(in);
                Ent ent = entities.get(e);
                if (ent == null) break;
                if (slot == 0) ent.hand = it;
                else if (slot >= 2 && slot <= 5) ent.armor[5 - slot] = it;   // 5 head .. 2 feet -> 0 helmet .. 3 boots
                else break;
                if (ent.shown) sendEquipment(e, ent, slot == 0, slot != 0);
                break;
            }
            case 0x3E: { // entity velocity
                int e = in.jvarint();
                Ent ent = entities.get(e);
                float vx = (short) in.beShort() / 8000f, vy = (short) in.beShort() / 8000f, vz = (short) in.beShort() / 8000f;
                if (ent != null && ent.shown && !ent.player) send(new Buf().put(SET_ENTITY_MOTION).uvarint(e).peVec3(vx, vy, vz));
                break;
            }
            case 0x4B: { // collect item (pickup animation)
                int item = in.jvarint(), collector = in.jvarint();
                Ent ent = entities.get(item);
                if (ent != null && ent.shown) send(new Buf().put(TAKE_ITEM_ENTITY).uvarint(item).uvarint(collector));
                break;
            }
            case 0x32: { // destroy entities
                int n = in.jvarint();
                for (int i = 0; i < n; i++) removeEntity(in.jvarint());
                break;
            }
            case 0x25: break; // entity (no movement)
            case 0x26: case 0x27: { // relative move (+look)
                int e = in.jvarint();
                Ent ent = entities.get(e);
                double dx = in.beShort() << 16 >> 16, dy = in.beShort() << 16 >> 16, dz = in.beShort() << 16 >> 16;
                if (ent == null) break;
                ent.x += dx / 4096.0; ent.y += dy / 4096.0; ent.z += dz / 4096.0;
                if (id == 0x27) { ent.yaw = in.s8() * 360f / 256f; ent.pitch = in.s8() * 360f / 256f; }
                sendMoveEntity(e, ent);
                break;
            }
            case 0x28: { // rotation
                int e = in.jvarint();
                Ent ent = entities.get(e);
                if (ent == null) break;
                ent.yaw = in.s8() * 360f / 256f; ent.pitch = in.s8() * 360f / 256f;
                sendMoveEntity(e, ent);
                break;
            }
            case 0x36: { // head look
                int e = in.jvarint();
                Ent ent = entities.get(e);
                if (ent == null) break;
                ent.headYaw = in.s8() * 360f / 256f;
                sendMoveEntity(e, ent);
                break;
            }
            case 0x4C: { // teleport
                int e = in.jvarint();
                Ent ent = entities.get(e);
                if (ent == null) break;
                ent.x = in.beDouble(); ent.y = in.beDouble(); ent.z = in.beDouble();
                ent.yaw = in.s8() * 360f / 256f; ent.pitch = in.s8() * 360f / 256f;
                sendMoveEntity(e, ent);
                break;
            }
            case 0x06: { // animation
                int e = in.jvarint();
                int anim = in.u8();
                if (anim == 0) send(new Buf().put(ANIMATE).varint(1).uvarint(e));
                else if (anim == 2) setSleeping(e, false, 0, 0, 0);   // leave bed
                break;
            }
            case 0x30: { // use bed (a player went to sleep)
                int e = in.jvarint();
                int[] p = Buf.jPos(in.beLong());
                setSleeping(e, true, p[0], p[1], p[2]);
                break;
            }
            case 0x1B: { // entity status
                int e = in.beInt();
                int status = in.s8();
                if (status == 2) send(new Buf().put(ENTITY_EVENT).uvarint(e).put(2).varint(0));
                // death animation only for OTHER entities - PocketMine never sends it to the dying player itself,
                // and doing so leaves the 3DS stuck in a "dying" state after respawning
                else if (status == 3 && e != eid) send(new Buf().put(ENTITY_EVENT).uvarint(e).put(3).varint(0));
                break;
            }
            case 0x14: { // window items
                int window = in.u8();
                int count = in.beShort();
                JItem[] items = new JItem[count];
                for (int i = 0; i < count; i++) items[i] = JItem.read(in);
                if (window == 0) {
                    for (int i = 0; i < count && i < inv.length; i++) inv[i] = items[i];
                    if (spawnSent) sendInventory();
                } else if (window == winId) {
                    for (int i = 0; i < count; i++) setActive(i, items[i]);
                    sendContainerContents();
                    sendInventory();
                }
                break;
            }
            case 0x16: { // set slot
                int window = in.s8();
                int slot = (short) in.beShort();
                JItem item = JItem.read(in);
                if (window == -1 && slot == -1) { cursor = item; break; }
                if (window == 0 && slot >= 0 && slot < inv.length) {
                    inv[slot] = item;
                    if (spawnSent) sendInventory();
                } else if (window == winId && winId > 0 && slot >= 0) {
                    setActive(slot, item);
                    if (slot < winSize) sendContainerSlot(slot); else sendInventory();
                }
                break;
            }
            case 0x3A: { // held item change
                heldSlot = in.s8();
                if (spawnSent) sendHeldItem();
                break;
            }
            default: break;
        }
        flush();
    }

    /** Send an entity to the 3DS - only once it has spawned (earlier packets would be thrown away by the 3DS). */
    private void showEntity(int e, Ent ent) {
        if (!spawnSent) return;
        if (ent.isItem) {
            if (ent.item == null || ent.shown) return;
            Buf ai = new Buf().put(ADD_ITEM_ENTITY).varlong(e).uvarint(e);
            writePESlot(ai, ent.item);
            ai.peVec3((float) ent.x, (float) ent.y, (float) ent.z).peVec3(0, 0, 0).uvarint(0);
            send(ai);
            ent.shown = true;
            return;
        }
        ent.shown = true;
        if (ent.player) { spawnPlayer(e, ent); return; }
        Buf ae = new Buf().put(ADD_ENTITY).varlong(e).uvarint(e).uvarint(ent.peType)
                .peVec3((float) ent.x, (float) ent.y, (float) ent.z).peVec3(ent.vx, ent.vy, ent.vz).leFloat(ent.pitch).leFloat(ent.yaw)
                .uvarint(0);   // attributes
        writeMeta(ae, null);
        ae.uvarint(0);         // links
        send(ae);
        sendEquipment(e, ent, true, true);
    }

    /** What a mob/player holds and wears (skeletons with bows, zombies with armor...) */
    private void sendEquipment(int e, Ent ent, boolean hand, boolean armor) {
        if (hand && ent.hand != null) {
            Buf b = new Buf().put(MOB_EQUIPMENT).uvarint(e);
            writePESlot(b, ent.hand);
            b.put(0).put(0).put(0);
            send(b);
        }
        boolean any = false;
        for (JItem a : ent.armor) if (a != null) any = true;
        if (armor && any) {
            Buf b = new Buf().put(MOB_ARMOR).uvarint(e);
            for (JItem a : ent.armor) writePESlot(b, a);
            send(b);
        }
    }

    /** Java 1.12 object type -> PE entity id for projectiles (-1 = not shown) */
    static int projectileType(int t) {
        switch (t) {
            case 60: case 91: return 80;  // arrow, spectral arrow
            case 61: return 81;           // snowball
            case 62: return 82;           // egg
            case 63: return 85;           // fireball
            case 64: return 94;           // small fireball (blaze)
            case 65: return 87;           // ender pearl
            case 66: return 89;           // wither skull
            case 73: return 86;           // splash potion
            case 75: return 68;           // bottle o' enchanting
            default: return -1;
        }
    }

    private void spawnPlayer(int e, Ent ent) {
        String pname = playerNames.getOrDefault(ent.uuid, "Player");
        String[] info = playerSkins.get(ent.uuid);
        Skins.Skin sk = info == null ? null : Skins.cached(info[0]);
        byte[] skinData = sk != null ? sk.rgba : Skin.DEFAULT;
        String skinId = sk != null && sk.slim ? "Standard_CustomSlim" : "Standard_Custom";
        ent.skinApplied = sk != null;
        send(new Buf().put(PLAYER_LIST).put(0).uvarint(1).peUUID(ent.uuid).varlong(e).str(pname).str(skinId).strBytes(skinData));
        Buf ap = new Buf().put(ADD_PLAYER).peUUID(ent.uuid).str(pname).varlong(e).uvarint(e)
                .peVec3((float) ent.x, (float) ent.y, (float) ent.z).peVec3(0, 0, 0)
                .leFloat(ent.pitch).leFloat(ent.headYaw).leFloat(ent.yaw).varint(0);
        writeMeta(ap, pname);
        send(ap);
        sendEquipment(e, ent, true, true);
    }

    /** A skin finished downloading: re-spawn that player on the 3DS so the new skin shows. */
    synchronized void onSkinReady(UUID u) {
        if (closed) return;
        for (Map.Entry<Integer, Ent> en : entities.entrySet()) {
            Ent ent = en.getValue();
            if (ent.player && ent.shown && u.equals(ent.uuid) && !ent.skinApplied) {
                send(new Buf().put(REMOVE_ENTITY).varlong(en.getKey()));
                send(new Buf().put(PLAYER_LIST).put(1).uvarint(1).peUUID(u));
                spawnPlayer(en.getKey(), ent);
            }
        }
        flush();
    }

    /** Read 1.12 entity metadata and return the item stack at index 6 (dropped items), or null. */
    static JItem readMetadataItem(Buf in) throws IOException {
        JItem found = null;
        while (!in.eof()) {
            int index = in.u8();
            if (index == 255) break;
            int type = in.jvarint();
            switch (type) {
                case 0: in.u8(); break;
                case 1: in.jvarint(); break;
                case 2: in.beFloat(); break;
                case 3: case 4: in.str(); break;
                case 5: { JItem it = JItem.read(in); if (index == 6) found = it; break; }
                case 6: in.bool(); break;
                case 7: in.beFloat(); in.beFloat(); in.beFloat(); break;
                case 8: in.beLong(); break;
                case 9: if (in.bool()) in.beLong(); break;
                case 10: in.jvarint(); break;
                case 11: if (in.bool()) in.jUUID(); break;
                case 12: in.jvarint(); break;
                case 13: NBT.skipRoot(in); break;
                default: return found;
            }
        }
        return found;
    }

    private void handlePlayerList(Buf in) throws IOException {
        int action = in.jvarint();
        int n = in.jvarint();
        for (int i = 0; i < n; i++) {
            UUID u = in.jUUID();
            switch (action) {
                case 0: {
                    String nm = in.str();
                    int props = in.jvarint();
                    for (int p = 0; p < props; p++) {
                        String pn = in.str(), pv = in.str();
                        if (in.bool()) in.str();
                        if (pn.equals("textures")) {
                            String[] info = Skins.parse(pv);
                            if (info != null) {
                                playerSkins.put(u, info);
                                final UUID fu = u;
                                Skins.fetch(info[0], !info[1].isEmpty(), sk -> onSkinReady(fu));
                            }
                        }
                    }
                    in.jvarint(); in.jvarint();
                    if (in.bool()) in.str();
                    playerNames.put(u, nm);
                    break;
                }
                case 1: case 2: in.jvarint(); break;
                case 3: if (in.bool()) in.str(); break;
                case 4: playerNames.remove(u); playerSkins.remove(u); break;
                default: return;
            }
        }
    }

    // =====================================================================================
    //  spawning
    // =====================================================================================
    private void tryStartGame() {
        if (startGameSent || !rpDone || !javaJoined || !havePos) return;
        startGameSent = true;
        startGameTime = System.currentTimeMillis();
        Buf sg = new Buf().put(START_GAME)
                .varlong(eid).uvarint(eid)
                .varint(peGamemode(gamemode))
                .peVec3((float) x, (float) y + EYE, (float) z)
                .leFloat(pitch).leFloat(yaw)
                .varint(-1)                 // seed
                .varint(peDim(dimension))
                .varint(1)                  // generator: infinite
                .varint(peGamemode(gamemode))
                .varint(difficulty)
                .peBlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))
                .bool(true)                 // achievements disabled
                .varint(-1)                 // day cycle stop time
                .bool(false)                // edu
                .leFloat(0).leFloat(0)      // rain, lightning
                .bool(true)                 // commands enabled
                .bool(false)                // texture packs required
                .uvarint(0)                 // game rules
                .str("")                    // level id
                .str(cfg.motd)              // world name
                .str("")                    // premium template
                .bool(false)
                .leLong(0);                 // current tick
        send(sg);
        send(new Buf().put(SET_TIME).varint(6000));
        sendAdventureSettings(gamemode == 1 || gamemode == 3, false);
        sendAttributes();
        send(new Buf().put(CHUNK_RADIUS_UPDATED).varint(radius));
        flush();
        sendNearbyChunks(true);
        Log.info("[" + username + "] Sent world start at " + String.format("%.1f %.1f %.1f", x, y, z));
    }

    private long lastWatchdog;

    /** Runs without the session lock so it still works if something is stuck. */
    void watchdog() {
        JavaConn j = java;
        if (j == null || !j.isPlay() || closed) return;
        long now = System.currentTimeMillis();
        if (now - lastWatchdog < 10000) return;
        boolean noRead = now - j.lastRead > 8000;
        boolean stuck = j.queued() > 0 && now - j.lastProcessed > 8000;
        if (!noRead && !stuck) return;
        lastWatchdog = now;
        StringBuilder sb = new StringBuilder("[" + name() + "] WATCHDOG: ");
        sb.append(noRead ? "no data from the Java server for " + (now - j.lastRead) / 1000 + "s" : "Java packets are not being processed");
        sb.append(" (read ").append(j.packetsRead).append(" packets, queued ").append(j.queued())
          .append(", last 0x").append(Integer.toHexString(j.lastPacketId)).append(")");
        for (Thread t : new Thread[]{j.readerThread, j.processorThread, j.writerThread}) {
            if (t == null) continue;
            sb.append("\n  ").append(t.getName()).append(" ").append(t.getState());
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(8, st.length); i++) sb.append("\n    at ").append(st[i]);
        }
        Log.warn(sb.toString());
    }

    private boolean outOfHeightWarned;

    // ---- terrain straight from the Paper server (plugin mode), for when the connection doesn't deliver any
    private UUID javaUuid;
    private long javaJoinTime, lastServerScan, lastServerRefresh;
    private boolean serverTerrain;
    private final Set<Long> serverPending = new HashSet<>(), serverFetched = new HashSet<>(), javaReal = new HashSet<>();

    private void serverTerrainTick() {
        if (!ServerChunks.available() || javaUuid == null || !havePos || !javaJoined || closed) return;
        long now = System.currentTimeMillis();
        int pcx = floor16(x), pcz = floor16(z);
        if (!serverTerrain) {
            if (!javaReal.contains(key(pcx, pcz)) && now - javaJoinTime > 2000) {
                serverTerrain = true;
                Log.warn("[" + username + "] the server isn't sending terrain over the connection - reading it directly from the world instead");
            } else return;
        }
        if (now - lastServerScan >= 300) {
            lastServerScan = now;
            List<long[]> order = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++)
                if (dx * dx + dz * dz <= radius * radius) order.add(new long[]{dx * dx + dz * dz, key(pcx + dx, pcz + dz), pcx + dx, pcz + dz});
            order.sort(Comparator.comparingLong(a -> a[0]));
            for (long[] o : order) {
                if (serverPending.size() >= 6) break;
                long k = o[1];
                if (serverPending.contains(k) || serverFetched.contains(k)) continue;
                if (javaReal.contains(k)) continue;   // the connection delivered this one
                requestServerChunk((int) o[2], (int) o[3], false);
            }
        }
        if (spawnSent && now - lastServerRefresh >= 1500) {
            // blocks changed by anyone (incl. us) may not come over the connection either: re-read nearby chunks
            lastServerRefresh = now;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                long k = key(pcx + dx, pcz + dz);
                if (serverFetched.contains(k) && !serverPending.contains(k)) requestServerChunk(pcx + dx, pcz + dz, true);
            }
        }
    }

    // ---- keep the server-side player in step with the 3DS (plugin mode)
    private long lastStateCheck, divergedSince, lastResync;
    private boolean stateBusy, bedWarned, stuckWarned;

    private void serverStateTick() {
        if (!ServerChunks.available() || javaUuid == null || !spawnSent || dead || stateBusy) return;
        long now = System.currentTimeMillis();
        if (now - lastStateCheck < 1000) return;
        lastStateCheck = now;
        stateBusy = true;
        ServerChunks.state(javaUuid, this::onServerState);
    }

    private synchronized void onServerState(ServerChunks.State st) {
        stateBusy = false;
        if (closed || dead) return;
        long now = System.currentTimeMillis();
        if (st.sleeping && !sleeping) {
            // The server has us lying in a bed but the 3DS isn't: a sleeping player can't move on the server.
            if (!bedWarned) { bedWarned = true; Log.warn("[" + username + "] the server had the 3DS player stuck in a bed - waking them up"); }
            javaEntityAction(2);
            ServerChunks.wakeUp(javaUuid);
            return;
        }
        // hunger / levels straight from the server, in case the connection doesn't deliver the updates
        if (st.food >= 0 && (st.food != food || st.level != xpLevel || Math.abs(st.exp - xpProgress) > 0.005f || Math.abs(st.saturation - saturation) > 0.5f)) {
            food = st.food; saturation = st.saturation; xpLevel = st.level; xpProgress = st.exp;
            sendAttributes();
        }
        double dx = st.x - x, dz = st.z - z, dy = st.y - y;
        boolean diverged = dx * dx + dz * dz > 4 || Math.abs(dy) > 3;
        if (!diverged || tpPendingUntil > now) { divergedSince = 0; return; }
        if (divergedSince == 0) { divergedSince = now; return; }
        if (now - divergedSince > 2500 && now - lastResync > 2000) {
            // the 3DS walked away but the server never moved us (its movement packets are being ignored): move it
            lastResync = now;
            if (!stuckWarned) {
                stuckWarned = true;
                Log.warn("[" + username + "] the server isn't applying the 3DS player's movement (server has them at "
                        + String.format("%.1f %.1f %.1f", st.x, st.y, st.z) + ", 3DS at " + String.format("%.1f %.1f %.1f", x, y, z) + ") - moving them on the server");
            }
            javaEntityAction(2);
            ServerChunks.moveTo(javaUuid, x, y, z);
        }
    }

    private void requestServerChunk(int cx, int cz, boolean refresh) {
        long k = key(cx, cz);
        serverPending.add(k);
        ServerChunks.fetch(javaUuid, cx, cz, col -> onServerChunk(k, col, refresh));
    }

    private synchronized void onServerChunk(long k, Mapping.PEColumn col, boolean refresh) {
        serverPending.remove(k);
        if (closed || col == null) return;
        serverFetched.add(k);
        Mapping.PEColumn old = chunks.get(k);
        if (refresh && old != null && sentChunks.contains(k)) {
            int changes = 0;
            List<int[]> diff = new ArrayList<>();
            for (int sy = 0; sy < 16 && changes <= 256; sy++) {
                Mapping.PESection a = old.sections[sy], b = col.sections[sy];
                if (a == null && b == null) continue;
                for (int i = 0; i < 4096; i++) {
                    int sa = a == null ? 0 : a.javaState[i] & 0xFFFF, sb = b == null ? 0 : b.javaState[i] & 0xFFFF;
                    if (sa != sb) {
                        if (++changes > 256) break;
                        // PE index is x<<8 | z<<4 | y
                        diff.add(new int[]{(col.x << 4) | (i >> 8), (sy << 4) | (i & 15), (col.z << 4) | ((i >> 4) & 15), sb});
                    }
                }
            }
            if (changes == 0) return;
            if (changes <= 256) { for (int[] d : diff) setBlock(d[0], d[1], d[2], d[3]); return; }
            chunks.put(k, col); sentChunks.remove(k); sendChunk(k);
            return;
        }
        if (javaReal.contains(k)) return;   // real terrain arrived over the connection meanwhile
        chunks.put(k, col);
        sentChunks.remove(k);
        if (startGameSent && inRadius(col.x, col.z)) sendChunk(k);
    }
    // diagnostics for "the server never sent terrain"
    private int javaFullChunks, javaPartialChunks, javaUnloads, nudges;
    private final java.util.TreeMap<Integer, Integer> preSpawnPackets = new java.util.TreeMap<>();

    private void sendClientSettings(int viewDistance) {
        java.send(new Buf().jvarint(0x04).str("en_US").put(Math.max(2, viewDistance)).jvarint(0).bool(true).put(0x7F).jvarint(1));
    }

    private String preSpawnSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("full chunks ").append(javaFullChunks).append(", partial chunks ").append(javaPartialChunks)
          .append(", unloads ").append(javaUnloads).append(", view distance sent ").append(Math.max(2, radius)).append("; packets:");
        for (Map.Entry<Integer, Integer> e : preSpawnPackets.entrySet()) sb.append(" 0x").append(Integer.toHexString(e.getKey())).append("x").append(e.getValue());
        return sb.toString();
    }

    /** ViaBackwards drops every block below y=0 and above y=255 for 1.12 connections (and PE 1.1 can't show them
     *  either), so a player down there sees only void. Say so clearly instead of looking like a bridge bug. */
    private void checkHeightLimit() {
        boolean out = y < 0 || y >= 256;
        if (out && !outOfHeightWarned) {
            outOfHeightWarned = true;
            Log.warn("[" + username + "] is at y=" + String.format("%.1f", y) + " (" + String.format("%.0f %.0f", x, z)
                    + "). Old clients can't see blocks below y=0 or above y=255 on 1.18+ worlds (a ViaBackwards limit),"
                    + " so the 3DS shows void here. Move them with: tp " + username + " ~ 80 ~  (or to spawn)");
            send(text(0, "\u00a7eYou're at y=" + (int) Math.floor(y) + ". The 3DS can't show blocks below y=0 or above y=255 - go back up/down to see the world."));
        } else if (!out) outOfHeightWarned = false;
    }

    /** Called every 50ms by Main. */
    synchronized void tick() {
        if (closed) return;
        if (startGameSent && !spawnSent) {
            // Wait until real terrain (not an empty placeholder) is loaded around the player,
            // otherwise the 3DS spawns in the void. Give up waiting after 20 seconds.
            int pcx = floor16(x), pcz = floor16(z), ready = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                long k = key(pcx + dx, pcz + dz);
                Mapping.PEColumn c = chunks.get(k);
                if (c != null && !c.isEmpty() && sentChunks.contains(k)) ready++;
            }
            Mapping.PEColumn own = chunks.get(key(pcx, pcz));
            boolean ownReady = own != null && !own.isEmpty() && sentChunks.contains(key(pcx, pcz));
            long waited = System.currentTimeMillis() - startGameTime;
            if (!ownReady && java != null && java.isPlay() && ((nudges == 0 && waited > 5000) || (nudges == 1 && waited > 12000))) {
                // No terrain yet: nudge the server the way a real client changing its render distance would
                nudges++;
                Log.warn("[" + username + "] no terrain from the server after " + (waited / 1000) + "s (" + preSpawnSummary() + ") - asking again");
                sendClientSettings(radius + 1);
                sendClientSettings(radius);
                sendJavaPosition();
            }
            if ((ownReady && ready >= 9 && waited > 1000) || (ownReady && waited > 6000) || waited > 20000) {
                if (!ownReady) Log.warn("[" + username + "] Terrain at the spawn point (" + String.format("%.1f %.1f %.1f", x, y, z)
                        + ", chunk " + pcx + "," + pcz + ") never arrived - spawning anyway. The server sent " + chunks.size()
                        + " chunks" + (own == null ? " but not this one" : own.isEmpty() ? "; this one was empty" : "") + ". " + preSpawnSummary());
                spawnSent = true;
                if (!sleeping) javaEntityAction(2);   // "leave bed": the server may have spawned us lying in one
                send(new Buf().put(PLAY_STATUS).beInt(3));
                sendMovePlayer(1);
                sendOwnEntityData();
                sendInventory();
                sendHeldItem();
                sendCommandList();
                send(new Buf().put(Extras.craftingData()));
                for (UUID bb : bossBars.keySet()) showBoss(bb);
                for (Map.Entry<Integer, Ent> en : entities.entrySet()) showEntity(en.getKey(), en.getValue());
                Log.info("[" + username + "] Spawned at " + String.format("%.1f %.1f %.1f", x, y, z) + " in dimension " + dimension
                        + " (" + sentChunks.size() + " chunks sent, " + entities.size() + " players/mobs nearby)");
                checkHeightLimit();
            }
        }
        serverTerrainTick();
        serverStateTick();
        bossTick();
        if ((!peChanges.isEmpty() || dropPending) && System.currentTimeMillis() - lastPeChange > 60 && !waitForMatchingChange()) processInventoryChanges();
        if (finishKey != Long.MIN_VALUE) {
            long now = System.currentTimeMillis();
            if (now >= finishAt) sendFinish();
            else if ((now / 50) % 5 == 0) javaSwing();   // keep swinging like a Java client holding the button
        }
        if (havePos && java != null && java.isPlay()) {
            if (!spawnSent) {
                if (System.currentTimeMillis() - lastJavaMove > 1000) sendJavaPosition();
            } else if (System.currentTimeMillis() - lastJavaMove >= 50 && (moveDirty || lookDirty || System.currentTimeMillis() - lastJavaMove >= 100) && takeMoveToken()) {
                flushMovement();   // nothing came from the 3DS this tick: send what a real client would
            }
        }
        flush();
    }

    // =====================================================================================
    //  helpers - PE output
    // =====================================================================================
    private boolean inRadius(int cx, int cz) {
        int pcx = floor16(x), pcz = floor16(z);
        int dx = cx - pcx, dz = cz - pcz;
        return dx * dx + dz * dz <= radius * radius;
    }

    private void sendNearbyChunks(boolean force) {
        if (!startGameSent) return;
        int pcx = floor16(x), pcz = floor16(z);
        if (!force && pcx == lastChunkX && pcz == lastChunkZ) return;
        lastChunkX = pcx; lastChunkZ = pcz;
        // nearest first
        List<long[]> order = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++)
            if (dx * dx + dz * dz <= radius * radius) order.add(new long[]{dx * dx + dz * dz, key(pcx + dx, pcz + dz)});
        order.sort(Comparator.comparingLong(a -> a[0]));
        for (long[] o : order) sendChunk(o[1]);
    }

    private void sendChunk(long key) {
        if (sentChunks.contains(key)) return;
        Mapping.PEColumn col = chunks.get(key);
        if (col == null) return;
        sentChunks.add(key);
        flush();
        send(new Buf().put(FULL_CHUNK).varint(col.x).varint(col.z).strBytes(col.serialize()));
        flush();
    }

    private void setBlock(int bx, int by, int bz, int javaState) {
        int pe = Mapping.block(javaState);
        long k = key(bx >> 4, bz >> 4);
        Mapping.PEColumn col = chunks.get(k);
        if (col != null) col.setBlock(bx, by, bz, pe, javaState);
        if (javaState == 0 && Buf.jPos(bx, by, bz) == finishKey) { finishKey = Long.MIN_VALUE; digKey = Long.MIN_VALUE; } // server already broke it
        if (Log.debug && Buf.jPos(bx, by, bz) == lastDigLog) Log.debug("[" + name() + "] server says block " + bx + "," + by + "," + bz + " is now " + (javaState >> 4) + ":" + (javaState & 15));
        if (sentChunks.contains(k)) {
            send(new Buf().put(UPDATE_BLOCK).peBlockPos(bx, by, bz).uvarint(pe >> 4).uvarint((0b1011 << 4) | (pe & 0xF)));
            if (Mapping.hasTile(pe >> 4)) sendBlockEntity(col, bx, by, bz);
        }
    }

    private void sendBlockEntity(Mapping.PEColumn col, int bx, int by, int bz) {
        int id = col == null ? 0 : col.getBlockId(bx, by, bz);
        byte[] t = Mapping.tileNBT(id, bx, by, bz, col == null ? null : col.javaTiles.get(Mapping.posKey(bx, by, bz)));
        if (t != null) send(new Buf().put(BLOCK_ENTITY_DATA).peBlockPos(bx, by, bz).put(t));
    }

    /** PocketMine's way of showing sleep: player flag "sleeping" + bed position; waking also needs an animation. */
    private void setSleeping(int e, boolean sleeping, int bx, int by, int bz) {
        if (e != eid) { Ent ent = entities.get(e); if (ent == null || !ent.shown || !ent.player) return; }
        Buf b = new Buf().put(SET_ENTITY_DATA).uvarint(e).uvarint(2);
        b.uvarint(27).uvarint(0).put(sleeping ? 2 : 0);          // DATA_PLAYER_FLAGS: bit 1 = sleeping
        b.uvarint(29).uvarint(6).varint(bx).varint(by).varint(bz); // DATA_PLAYER_BED_POSITION
        send(b);
        if (!sleeping) send(new Buf().put(ANIMATE).varint(3).uvarint(e)); // ACTION_STOP_SLEEP
        if (e == eid) { this.sleeping = sleeping; Log.debug("[" + name() + "] " + (sleeping ? "went to bed" : "left bed")); }
    }

    /** Our own player's entity data (PocketMine sends this on spawn and respawn; ladders need CAN_CLIMB). */
    private void sendOwnEntityData() {
        long flags = (1L << 14) | (1L << 15) | (1L << 19) | (1L << 33); // nametag, always show, can climb, breathing
        Buf b = new Buf().put(SET_ENTITY_DATA).uvarint(eid).uvarint(8);
        b.uvarint(27).uvarint(0).put(sleeping ? 2 : 0);      // player flags (clears a stuck "sleeping" state)
        b.uvarint(29).uvarint(6).varint(0).varint(0).varint(0); // bed position
        b.uvarint(0).uvarint(7).varlong(flags);              // flags
        b.uvarint(7).uvarint(1).leShort(400);                // air
        b.uvarint(43).uvarint(1).leShort(400);               // max air
        b.uvarint(4).uvarint(4).str(username);               // name tag
        b.uvarint(38).uvarint(7).varlong(-1);                // lead holder
        b.uvarint(39).uvarint(3).leFloat(1f);                // scale
        send(b);
    }

    private void sendMovePlayer(int mode) {
        send(new Buf().put(MOVE_PLAYER).uvarint(eid).peVec3((float) x, (float) y + EYE, (float) z)
                .leFloat(pitch).leFloat(yaw).leFloat(yaw).put(mode).bool(onGround).uvarint(0));
    }

    private void sendMoveEntity(int e, Ent ent) {
        if (!ent.shown) return;
        send(new Buf().put(MOVE_ENTITY).uvarint(e).peVec3((float) ent.x, (float) ent.y + (ent.player ? EYE : 0), (float) ent.z)
                .put(rot(ent.pitch)).put(rot(ent.headYaw)).put(rot(ent.yaw)).bool(true).bool(false));
    }

    private void removeEntity(int e) {
        Ent ent = entities.remove(e);
        if (ent == null || !ent.shown) return;
        send(new Buf().put(REMOVE_ENTITY).varlong(e));
        if (ent.player) send(new Buf().put(PLAYER_LIST).put(1).uvarint(1).peUUID(ent.uuid));
    }

    private void clearEntities() {
        for (Integer e : new ArrayList<>(entities.keySet())) removeEntity(e);
    }

    private void sendAttributes() {
        Buf b = new Buf().put(UPDATE_ATTRIBUTES).uvarint(eid).uvarint(6);
        attr(b, 0, 20, dead ? 0 : Math.max(0, health), 20, "minecraft:health");
        attr(b, 0, 20, food, 20, "minecraft:player.hunger");
        attr(b, 0, 20, saturation, 5, "minecraft:player.saturation");
        attr(b, 0, Float.MAX_VALUE, moveSpeed * speedMultiplier, 0.1f, "minecraft:movement");
        attr(b, 0, 24791, xpLevel, 0, "minecraft:player.level");          // enchanting needs levels on the 3DS
        attr(b, 0, 1, xpProgress, 0, "minecraft:player.experience");
        send(b);
    }
    private static void attr(Buf b, float min, float max, float cur, float def, String name) {
        b.leFloat(min).leFloat(max).leFloat(Math.min(max, Math.max(min, cur))).leFloat(def).str(name);
    }

    private void sendAdventureSettings(boolean allowFlight, boolean flying) {
        int flags = 0;
        if (gamemode == 3) flags |= 1;            // world immutable (spectator)
        flags |= 1 << 5;                          // auto jump
        if (allowFlight) flags |= 1 << 6;
        if (gamemode == 3) flags |= 1 << 7;       // no clip
        if (gamemode == 1) flags |= 1 << 8;       // world builder
        if (flying) flags |= 1 << 9;
        send(new Buf().put(ADVENTURE_SETTINGS).uvarint(flags).uvarint(0));
    }

    private void sendGamemode() {
        send(new Buf().put(SET_GAMETYPE).varint(peGamemode(gamemode)));
        sendAdventureSettings(gamemode == 1 || gamemode == 3, false);
    }

    private void sendInventory() {
        Buf b = new Buf().put(CONTAINER_SET_CONTENT).uvarint(0).varlong(eid).uvarint(45);
        for (int i = 0; i < 9; i++) writePESlot(b, inv[36 + i]);   // hotbar
        for (int i = 9; i < 36; i++) writePESlot(b, inv[i]);       // main inventory
        for (int i = 0; i < 9; i++) b.varint(0);                    // dummy slots
        b.uvarint(9);
        for (int i = 0; i < 9; i++) b.varint(i + 9);
        send(b);
        Buf a = new Buf().put(CONTAINER_SET_CONTENT).uvarint(120).varlong(eid).uvarint(4);
        for (int i = 5; i <= 8; i++) writePESlot(a, inv[i]);
        a.uvarint(0);
        send(a);
    }

    private void sendHeldItem() {
        if (heldSlot < 0 || heldSlot > 8) return;
        Buf b = new Buf().put(MOB_EQUIPMENT).uvarint(eid);
        writePESlot(b, inv[36 + heldSlot]);
        b.put(heldSlot).put(heldSlot).put(0);
        send(b);
    }

    private static void writePESlot(Buf b, JItem item) {
        int[] pe = item == null ? null : item.pe();
        if (pe == null || item.count <= 0) { b.varint(0); return; }
        b.varint(pe[0]);
        b.varint(((pe[1] & 0x7FFF) << 8) | Math.min(255, item.count));
        byte[] tag = item.peNbt();   // enchantments + custom name
        b.leShort(tag.length).put(tag);
        b.varint(0).varint(0);  // can place on / can destroy
    }

    private static void skipSlot(Buf in) throws IOException {
        int id = in.varint();
        if (id <= 0) return;
        in.varint();
        int nbt = in.leShort();
        in.bytes(nbt);
        int a = in.varint(); for (int i = 0; i < a; i++) in.str();
        int c = in.varint(); for (int i = 0; i < c; i++) in.str();
    }

    private static void writeMeta(Buf b, String nametag) {
        long flags = (1L << 14) | (1L << 15) | (1L << 33); // show nametag, always show, breathing
        int count = nametag != null ? 3 : 2;
        b.uvarint(count);
        b.uvarint(0).uvarint(7).varlong(flags);           // DATA_FLAGS (long)
        if (nametag != null) b.uvarint(4).uvarint(4).str(nametag);
        b.uvarint(39).uvarint(3).leFloat(1f);             // DATA_SCALE
    }

    private static Buf text(int type, String msg) { return new Buf().put(TEXT).put(type).str(msg); }

    // =====================================================================================
    //  helpers - Java output
    // =====================================================================================
    // token bucket: allows the 3DS's own (slightly uneven) 20/s movement through 1:1, never more than ~20/s overall
    private double moveTokens = 3; private long moveTokenTime = System.currentTimeMillis();
    private boolean takeMoveToken() {
        long now = System.currentTimeMillis();
        moveTokens = Math.min(3, moveTokens + (now - moveTokenTime) * 0.02); // 20 per second
        moveTokenTime = now;
        if (moveTokens < 1) return false;
        moveTokens -= 1;
        return true;
    }

    /** One Java movement packet: position, look, or the plain "still here" packet - like vanilla, ~20/s. */
    private void flushMovement() {
        if (java == null || !java.isPlay()) return;
        if (moveDirty) sendJavaPosition();
        else {
            lastJavaMove = System.currentTimeMillis();
            if (lookDirty) java.send(new Buf().jvarint(0x0F).beFloat(yaw).beFloat(pitch).bool(onGround));
            else java.send(new Buf().jvarint(0x0C).bool(onGround));
        }
        moveDirty = lookDirty = false;
    }

    private void sendJavaPosition() {
        if (java == null || !java.isPlay()) return;
        lastJavaMove = System.currentTimeMillis();
        java.send(new Buf().jvarint(0x0E).beDouble(x).beDouble(y).beDouble(z).beFloat(yaw).beFloat(pitch).bool(onGround));
    }
    private void javaDig(int status, int bx, int by, int bz, int face) {
        if (java == null || !java.isPlay()) return;
        java.send(new Buf().jvarint(0x14).jvarint(status).beLong(Buf.jPos(bx, by, bz)).put(Math.max(0, Math.min(5, face))));
    }
    private void javaSwing() { if (java != null && java.isPlay()) java.send(new Buf().jvarint(0x1D).jvarint(0)); }
    private void javaEntityAction(int action) {
        if (java != null && java.isPlay()) java.send(new Buf().jvarint(0x15).jvarint(eid).jvarint(action).jvarint(0));
    }

    // =====================================================================================
    //  batching / misc
    // =====================================================================================
    void send(Buf packet) {
        if (closed) return;
        byte[] b = packet.toArray();
        if (Log.debug && b.length > 0 && (b[0] & 0xFF) != MOVE_ENTITY) Log.debug("[" + name() + "] PE <- 0x" + Integer.toHexString(b[0] & 0xFF) + " (" + b.length + " bytes)");
        pending.add(b);
        pendingBytes += b.length;
        if (pendingBytes > 128 * 1024) flush();
    }

    synchronized void flush() {
        if (pending.isEmpty() || closed) return;
        Buf raw = new Buf(pendingBytes + pending.size() * 5);
        for (byte[] p : pending) raw.strBytes(p);
        pending.clear();
        pendingBytes = 0;
        rak.sendGame(deflate(raw.toArray()));
    }

    synchronized void disconnect(String reason) {
        if (closed) return;
        Log.info("[" + name() + "] Disconnecting: " + reason);
        send(new Buf().put(DISCONNECT).bool(false).str(reason == null ? "Disconnected" : reason));
        flush();
        closed = true;
        if (java != null) java.close();
        new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
            rak.close(reason, true);
        }).start();
    }

    /** RakNet session went away. */
    synchronized void onClosed(String reason) {
        if (!closed) Log.info("[" + name() + "] Left (" + reason + ")");
        closed = true;
        if (java != null) java.close();
    }

    String name() { return username == null ? rak.address.toString() : username; }

    /** round to 1/10000 block: removes float error so positions sit exactly on block surfaces */
    static double snap(double v) { return Math.round(v * 10000.0) / 10000.0; }

    static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }
    static int floor16(double v) { return (int) Math.floor(v) >> 4; }
    static int rot(float deg) { return ((int) (deg / 360f * 256f)) & 0xFF; }
    static float clamp01(float f) { return Math.max(0f, Math.min(1f, f - (float) Math.floor(f))); }
    static int peDim(int javaDim) { return javaDim == -1 ? 1 : javaDim == 1 ? 2 : 0; }
    static int peGamemode(int gm) { return gm == 3 ? 1 : gm == 2 ? 2 : gm; } // spectator -> creative (no spectator in 1.1)

    static byte[] inflate(byte[] data) throws DataFormatException {
        Inflater inf = new Inflater();
        inf.setInput(data);
        ByteArrayOutputStream o = new ByteArrayOutputStream(data.length * 4);
        byte[] tmp = new byte[8192];
        while (!inf.finished()) {
            int n = inf.inflate(tmp);
            if (n == 0) {
                if (inf.needsInput() || inf.needsDictionary()) break;
            }
            o.write(tmp, 0, n);
            if (o.size() > 16 * 1024 * 1024) throw new DataFormatException("batch too large");
        }
        boolean ok = inf.finished();
        inf.end();
        if (!ok) throw new DataFormatException("incomplete zlib stream");
        return o.toByteArray();
    }

    static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(6);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream(data.length / 2 + 64);
        byte[] tmp = new byte[8192];
        while (!d.finished()) { int n = d.deflate(tmp); o.write(tmp, 0, n); }
        d.end();
        return o.toByteArray();
    }
}
