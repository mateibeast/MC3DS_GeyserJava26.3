# MC3DS Bridge 0.1.18 (experimental)

Lets **Minecraft: New Nintendo 3DS Edition with the fan-made 1.10 update** (Pocket Edition protocol 113)
join your **existing Java server** (tested design: Paper 1.21.4 + Geyser/Floodgate).

```
3DS (PE 1.1, UDP) ──► MC3DS Bridge ──► Java 1.12.2 connection ──► ViaVersion/ViaBackwards ──► your Paper 1.21.4 server
```

Your server keeps working exactly as before. Geyser stays on 19132 for modern Bedrock; the bridge uses its own UDP port.

## Requirements on your server
1. **ViaVersion** and **ViaBackwards** plugins (the bridge speaks Java 1.12.2).
2. **Floodgate** (you already have it with Geyser): the bridge uses Floodgate's `key.pem` so 3DS players
   don't need Java accounts. They show up with Floodgate's prefix, e.g. `.Toad_3DS`.

## Option A: run it as a plugin (easiest on PebbleHost)
1. Upload `MC3DSBridge-0.1.0.jar` to your server's `plugins/` folder (also install ViaVersion + ViaBackwards if you haven't).
2. In the PebbleHost panel: **Network → Allocate New Port**. Note the number (e.g. `25612`).
3. Restart the server once. It creates `plugins/MC3DSBridge/bridge.properties`.
4. Edit that file: set `bind-port=` to the port you allocated. Everything else is auto-detected
   (`java-port` = your server's port, `floodgate-key=../floodgate/key.pem`).
5. Restart. The console should say `Listening for 3DS players on UDP 0.0.0.0:<port>`.
6. On the 3DS, add a server with your server's IP/hostname and **that port**.

## Option B: run it standalone
```
java -jar MC3DSBridge-0.1.0.jar
```
It creates `bridge.properties` next to the jar. Set `java-host`/`java-port` to your server, copy
`plugins/floodgate/key.pem` next to the jar, set `bind-port`, restart. Console commands: `list`, `debug`, `stop`.

## What works in 0.1.0
- Joining, the world (chunks, block mapping), walking, looking around
- Chat both ways (Java chat → 3DS text), commands typed in chat
- Breaking and placing blocks, switching hotbar slots
- Seeing other players and mobs move, attacking/interacting with them
- Health/hunger, death + respawn, day/night, gamemode changes
- Inventory display (hotbar, main inventory, armor)

## Not done yet / known limits
- **Moving items inside the inventory, crafting, chests and furnaces are not synced yet.** The inventory is view-only.
- Dropped items, item frames, paintings, minecarts and boats are not shown.
- Blocks/items newer than 1.12 appear as whatever ViaBackwards substitutes.
- Nether/End switching is implemented but untested.
- This has been tested against a simulated 3DS client and a simulated Java server, **not yet on a real 3DS**.

## If your server is behind Velocity/BungeeCord
Run the bridge standalone (or on the proxy machine) and point `java-host`/`java-port` at the **proxy**,
using the Floodgate `key.pem` from the proxy. Direct connections to a backend that uses modern forwarding are refused.

## If something breaks
Set `debug=true` in `bridge.properties`, reproduce it, and look at (or send) the console log.
Common messages:
- `asked for a Mojang login`: the Floodgate key wasn't found. Check the `floodgate-key` path.
- `Address already in use`: pick another `bind-port` (19132 is Geyser's).
- 3DS can't see/join the server: make sure the port is allocated in the panel and it's UDP.
- Kicked with "outdated client/server" by ViaVersion: install ViaBackwards.

## Credits / references
Protocol layouts were taken from PocketMine-MP (2017, protocol 113), sel-utils (MIT), DragonProxy's
2017 protocol-113 work, MCProtocolLib 1.12.2, and GeyserMC/Floodgate (data format). No code from
those projects is bundled; the jar has no dependencies.
