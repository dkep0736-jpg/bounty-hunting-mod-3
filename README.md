# Bounty Hunter — Fabric mod for Minecraft 26.1.2

Put item bounties on players, hunt them with range-limited tracking compasses, collect the reward.

**Server-side only.** Players join with a completely vanilla client — nobody needs to install anything.
The UI is a chest-style menu, which is the only kind of custom UI a vanilla client can render.

---

## 1. Build the jar

There is no jar in this folder — Minecraft mods have to be compiled, and the compile needs to download
Minecraft and Fabric libraries. Two ways to do it:

### Option A — GitHub builds it for you (no dev setup)

1. Create a new repository on GitHub (private is fine).
2. Upload everything in this folder to it (drag the whole folder into the GitHub web uploader, or
   `git init && git add . && git commit -m "init" && git push`).
3. Go to the **Actions** tab. The included workflow runs automatically on push.
4. When it finishes (2–4 min), open the run and download the **bountyhunter-jar** artifact.
   Inside is `bountyhunter-1.0.0.jar`.

If the build fails, open the failed step and copy the error — see Troubleshooting below.

### Option B — build locally

Requires **Java 25** (Temurin is fine). From this folder:

```
./gradlew build          # macOS / Linux
gradlew.bat build        # Windows
```

Jar lands in `build/libs/bountyhunter-1.0.0.jar`. Ignore any `-sources.jar`.

---

## 2. Install on your Apex server

1. In the Apex panel, make sure the server is running **Fabric for 26.1.2** (Game File / Server Type).
2. Stop the server.
3. Open the **FTP File Access** or file manager, go to the `mods` folder.
4. Upload two files:
   - `fabric-api-0.155.3+26.1.2.jar` — download from Modrinth or CurseForge (Fabric API, version for 26.1.2)
   - `bountyhunter-1.0.0.jar` — the one you just built
5. Start the server. The log should show `Bounty Hunter loaded: compass range 300.0 blocks, cooldown 10.0h`.

A config file appears at `config/bountyhunter.json` after the first start.

---

## 3. How players use it

| Command | What it does |
|---|---|
| `/bounty` | Opens the Bounty Board |
| `/bounty set <name>` | Opens the reward screen for that player (tab-completes) |
| `/bounty compass <name>` | Gives you a tracking compass for an active bounty |
| `/bounty list` | Text list of active bounties + who's on cooldown |
| `/bounty cancel <name>` | Withdraw your own reward and get the item back |

**Bounty Board** (`/bounty`) — a 6-row menu of player heads. Targets with an active bounty are red and
sort to the top; everyone else online is below them.
- **Left-click a head** → opens the reward screen for that player
- **Right-click a red head** → hands you a tracking compass for them

**Reward screen** — drag any item from your inventory into the center slot, then click the green
**Confirm** pane. The item leaves your inventory and is held until someone claims the bounty.
Red pane cancels and returns your item. Closing the menu also returns it.

Several players can stack rewards on the same target. Whoever gets the kill takes **all** of them.

**Tracking compass** — a glowing compass named after the target. It points at them while they are
within **300 blocks and in the same dimension**; outside that it spins uselessly. It updates twice a
second. The moment the bounty is claimed, every compass for that target is deleted from every
inventory (including ender chests, and offline players' inventories when they next log in).

**Claiming** — only a player kill claims a bounty. Dying to a mob, fall damage, or lava leaves the
bounty standing. When it's claimed, the killer gets every reward, the server gets a broadcast, and the
victim is **protected for 10 hours** — nobody can put a new bounty on them until it expires.

### Admin commands (op / permission level 2+)

```
/bounty admin remove <name>          # cancel a bounty, refund the items to whoever placed them
/bounty admin delete <name>          # cancel a bounty, destroy the items
/bounty admin clearcooldown <name>   # end someone's 10-hour protection early
/bounty admin reload                 # re-read config/bountyhunter.json
```

---

## 4. Config — `config/bountyhunter.json`

```json
{
  "compassRangeBlocks": 300.0,
  "cooldownHours": 10.0,
  "compassUpdateIntervalTicks": 10,
  "broadcastEvents": true,
  "allowPlacerToCancel": true,
  "maxRewardsPerTarget": 9
}
```

- `compassRangeBlocks` — tracking radius. Beyond this the compass spins.
- `cooldownHours` — protection after being killed for a bounty. Real time, survives restarts.
- `compassUpdateIntervalTicks` — 20 = once a second. Lower is smoother, higher is cheaper. 10 is fine
  for a few dozen players.
- `broadcastEvents` — server-wide chat announcements when bounties are placed and claimed.
- `allowPlacerToCancel` — set false to make bounties final once placed.
- `maxRewardsPerTarget` — how many separate rewards can stack on one player.

Edit the file, then `/bounty admin reload` (or restart).

Everything (bounties, cooldowns, known player names) is stored in the world save at
`world/data/bountyhunter/bounties.dat` and survives restarts.

---

## 5. Troubleshooting the build

The code was written against Minecraft 26.1.2's API, but I could not compile it against the real
Minecraft jar while writing it — that happens on the first build. Three spots are the most likely to
need a one-line fix if the build errors. Each has an obvious replacement:

**`cannot find symbol: codecOrThrow` or a PROFILE error** — in `gui/GuiItems.java`, the player-head
skin lookup. Delete the whole `try { ... } catch ...` block in `playerHead(...)`. You lose skins on the
heads (they become blank Steve heads with the right names); everything else works.

**`cannot find symbol: getStringOr`** — in `BountyManager.compassTarget(...)`, change:
```java
String raw = custom.copyTag().getStringOr(COMPASS_TARGET_KEY, "");
```
to
```java
String raw = custom.copyTag().getString(COMPASS_TARGET_KEY).orElse("");
```
(or drop `.orElse("")` if `getString` returns a plain String in this build).

**`cannot find symbol: LEVEL_GAMEMASTERS` / `hasPermission`** — in `BountyCommands.java`, change
`.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))` to `.requires(src -> true)` to open
admin commands to everyone, or ask me and I'll wire the current permission API.

Anything else: paste the compiler output and I'll fix it.

---

## 6. Things worth knowing before you launch it

- **PvP must be on** for the server (`server.properties` → `pvp=true`), and bounties are pointless in a
  world where people can't hurt each other.
- A bounty on an offline player stays up. Compasses for them just spin until they log back in.
- Rewards are held by the mod, not escrowed in a chest. `/bounty admin delete` destroys them; use
  `remove` if you want people refunded.
- Players can put bounties on anyone who has ever joined, including offline players, by name.
- Someone can bounty-hunt their own alt for free item transfer. If that's a concern on your server,
  set `allowPlacerToCancel: false` and watch `/bounty list`.
