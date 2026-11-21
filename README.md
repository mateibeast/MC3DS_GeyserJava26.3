# MC3DS Bridge

Lets **Minecraft: New Nintendo 3DS Edition** (with the fan-made 1.10 update, Pocket Edition protocol 113)
join a **modern Paper server**, next to your normal Java and Geyser/Bedrock players.

```
3DS (PE 1.1, UDP) ──► MC3DS Bridge ──► Java 1.12.2 connection ──► ViaVersion/ViaBackwards ──► Paper 1.21.x
```

The bridge runs as a Paper plugin (or standalone). It listens on its own UDP port, translates everything
the 3DS sends into a Java 1.12.2 client connection, and lets ViaVersion/ViaBackwards turn that into your
server's version. Your server keeps working exactly as before. Geyser stays on its port.

> **Status: experimental.** Developed and tested against a real 3DS on Paper 1.21.4, but many features were
> worked out from old protocol documentation. Expect rough edges and please report what breaks.

## Requirements

- **Paper 1.21.x** (developed on 1.21.4)
- **ViaVersion** + **ViaBackwards**: the bridge connects as a Java 1.12.2 client
- **Floodgate**: 3DS players log in as Floodgate players, so they don't need Java accounts
  (they appear with Floodgate's prefix, e.g. `.Steve`)
- A free **UDP port** for 3DS players (not Geyser's)

## Install

1. Download `MC3DSBridge.jar` from [Releases](../../releases) and put it in `plugins/`.
2. Restart once. This creates `plugins/MC3DSBridge/bridge.properties`.
3. Set `bind-port=` to your free UDP port (on hosts like PebbleHost: allocate an extra port in the panel).
   The Java port and the Floodgate key (`../floodgate/key.pem`) are detected automatically.
4. Restart. The console should show `Listening for 3DS players on UDP 0.0.0.0:<port>`.
5. On the 3DS, add a server with your server's address and **that port**.

### Standalone (or behind Velocity/BungeeCord)
```
java -jar MC3DSBridge.jar
```
It creates `bridge.properties` next to the jar. Set `java-host` / `java-port` to your server (or proxy),
copy Floodgate's `key.pem` next to the jar, set `bind-port`, restart. Console commands: `list`, `debug`, `stop`.
Some features (reading terrain/player state directly from the world, 3DS skins on the server) only work in plugin mode.

## Configuration (`bridge.properties`)

| Key | Default | Meaning |
|---|---|---|
| `bind-host` / `bind-port` | `0.0.0.0` / `19133` | UDP address the 3DS connects to |
| `motd` | `3DS Bridge` | Name in the 3DS server list |
| `java-host` / `java-port` | `127.0.0.1` / server port | The Java server to connect to |
| `java-handshake-host` | empty | Hostname sent in the handshake (empty = `java-host`) |
| `floodgate-key` | `../floodgate/key.pem` | Floodgate key, relative to the config file |
| `chunk-radius` | `4` | View distance for the 3DS (it has little memory) |
| `max-players` | `20` | Shown in the server list |
| `speed-multiplier` | `1.0` | 3DS walking speed (test in-game with `/3ds speed 0.8`) |
| `skin-upload` | `true` | Show 3DS skins to Java/Bedrock players (signed via mineskin.org) |
| `mineskin-api-key` | empty | Optional key from account.mineskin.org to avoid rate limits |
| `skin-classic-fix` | `true` | Rebuild 3DS skins as clean 64x64 skins |
| `debug` | `false` | Log every packet. Turn on when reporting a bug |

## What works

- Joining, the world (block/metadata mapping, light, block entities: chests, beds and their colours, signs,
  furnaces, heads, …), day/night, dimensions
- Walking, sprinting, sneaking, jumping, stairs and slabs (Java collision shapes), server-timed block breaking,
  placing, combat, death and respawn, sleeping
- Other players (with their Java skins), mobs with held items and armour, projectiles, dropped items and pickup
- Your 3DS skin shown to Java and Bedrock players
- Chat both ways (colours, translations), server commands, titles, action bar, boss bars
- Inventory, chests, double chests, ender chests, shulker boxes, furnaces, hoppers, dispensers, droppers
- Crafting (2x2 and crafting table), enchanting, anvils, brewing stands, villager trading
- Hunger, XP and levels, enchantments and custom names on items, many sounds

## Known limitations

- **Blocks below y=0 or above y=255 are invisible.** ViaBackwards can't send them to 1.12 clients, and PE 1.1
  can't show them either. A warning is printed when a player goes there.
- Blocks and items newer than 1.12 show as their closest old equivalent (or stone).
- Not possible on PE 1.1: scoreboard sidebar, world border, banners, your Java resource pack.
- The 3DS doesn't report the slots of its enchanting table/anvil/brewing menus, so the bridge works out
  what moved from what left and came back to the inventory. Unusual moves can get undone by the server.
- If the server stops sending terrain or ignores a 3DS player's movement (seen on some setups), the plugin
  reads the world directly and moves the player server-side. This works, but other players/mobs may then not show.

## ⚠️ Security

3DS player names are **not verified**. Anyone can join through the bridge with any name, and Floodgate
will trust it. Use a whitelist or an auth plugin if names matter on your server, and never give out your
`key.pem`: anyone with it can log in as any Floodgate player.

## Reporting bugs

Set `debug=true`, restart, reproduce the problem, and attach the console log (`logs/latest.log`).
Lines like `3DS slot window=…` and `click window=…` show exactly what the 3DS sent and what the bridge did.

## Building

Only a JDK (17 or newer) is needed, no Maven or Gradle:
```
sh build.sh        # -> build/MC3DSBridge.jar
```
`stubs/` contains a few empty Bukkit classes so it compiles without downloading Paper. Everything else the
plugin uses at runtime is reached through reflection, so one jar works as a plugin and standalone.

`test/run.sh` runs an end-to-end test (a fake Java 1.12.2 server, the bridge and a fake 3DS client). It needs
the [sel-utils](https://github.com/sel-project/sel-utils) pocket113 Java classes compiled into `test/sel/`.

## Code overview

| File | What it does |
|---|---|
| `BridgePlugin.java` | Paper plugin entry point (auto-detects port and Floodgate key) |
| `Main.java` | Startup, 20 tps tick loop, standalone console |
| `RakNet.java` | RakNet (UDP) server for the 3DS |
| `PESession.java` | The translator: login, world, entities, movement, inventories, crafting, enchanting, trading, … |
| `JavaConn.java` | Java 1.12.2 client connection |
| `Floodgate.java` | Floodgate login data |
| `Mapping.java` | Block/item/mob tables, chunk conversion, block entities |
| `ServerChunks.java` | Plugin-mode fallbacks: terrain and player state read directly from Paper |
| `Collision.java`, `BreakTime.java` | Java block shapes and break times |
| `Extras.java` | Recipe and sound tables |
| `JItem.java`, `NBT.java`, `Chat.java`, `Buf.java` | Items, NBT, chat conversion, byte buffers |
| `Skin*.java` | Skins in both directions |

## Credits

Protocol details come from PocketMine-MP (2017, protocol 113), sel-utils, DragonProxy's protocol-113 work,
MCProtocolLib (1.12.2) and GeyserMC/Floodgate (login data format). Crafting recipes and sound IDs are derived
from [PrismarineJS minecraft-data](https://github.com/PrismarineJS/minecraft-data) (MIT). No third-party code
is bundled; the jar has no dependencies.

This project is not affiliated with Mojang, Microsoft or Nintendo.

## License

MIT, see [LICENSE](LICENSE).
