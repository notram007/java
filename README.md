# Pillars of Fortune - Multi-Arena Edition (v2.0.5)

## v2.0.5 - Block Placement + Region Regeneration

**What changed:**
- Players can now **place block-type loot items** anywhere inside the arena's WorldGuard region during a live game
- Block **breaking** is still denied — players can only place, not mine
- When the game ends (win, timeout, or `/pof stop`), the **entire region regenerates** back to exactly how it looked before the game started
- The snapshot is taken automatically the moment the game starts, so you never need to do anything manually
- Requires **WorldEdit** on the server (alongside WorldGuard) — both are `softdepend` so the plugin still loads without them, but region snap/restore won't work

**How it works internally:**
1. `startGame()` calls `RegionSnapshotManager.snapshot()` — every block inside the WG region bounding box is saved to memory using WorldEdit's API
2. During the game, `BlockListener` un-cancels WorldGuard's block-place denial for game players inside the region
3. `endGame()` / `forceStop()` calls `RegionSnapshotManager.restore()` — WorldEdit replays every saved block state back into the world on the next tick (after players have been teleported out)


## v2.0.4 - Pillar Teleport on Join + Freeze + Slow Falling

**What changed:**
- Players are now teleported **directly to their assigned pillar the moment they join** (via sign, NPC, or `/pof join`), not when the game starts
- Each player gets a unique pillar assigned at join time — no two players share a pillar
- Players receive **Slow Falling for 3 seconds** on join so they float down safely and land on the pillar top without fall damage
- Players are **frozen in place** (cannot move their position) during the WAITING and COUNTDOWN phases — they can still look around freely
- Freeze is removed the instant the game starts
- Players who leave during waiting/countdown are unfrozen and returned to the lobby
- Pillar slots are freed up again if a player leaves before the game starts, so a new joining player can take that slot

**No config changes required** — everything works automatically.

## v2.0.3 - WorldGuard Region Integration

Removed the fragile map-radius/center boundary logic and replaced it with WorldGuard region boundary checking.

- Each arena can be assigned a WorldGuard region via `/pof arena setregion <arena> <regionName>`
- Players who leave their assigned region are automatically eliminated
- WorldGuard is a `softdepend` — the plugin works fine without it

## v2.0.2 - Instant Elimination at Game Start Fix

Fixed players being eliminated the instant the game started (race condition with off-map checking). Added a 3-second grace period before the boundary check runs.

## v2.0.1 - Inventory Loss Fix

Fixed the bug where starting a game cleared every player's inventory permanently. All inventory data is now snapshotted and restored properly.

## What's in this project

SOURCE CODE for a multi-arena rebuild of the plugin. NOT a compiled .jar — it needs to be built via Maven (see below).

## How to build it

### Option A: GitHub Actions (recommended if already set up)
Push these files to your existing GitHub repo and GitHub Actions will auto-rebuild.

### Option B: IntelliJ IDEA (free, GUI)
1. Install IntelliJ IDEA Community Edition + JDK 21.
2. File -> Open -> select this folder.
3. Let it auto-import the Maven project.
4. Maven panel -> Lifecycle -> package.
5. Find the jar in `target/PillarsOfFortune.jar`.

### Option C: Command line (if you have JDK 21 + Maven)
```
mvn clean package
```

## Installing on your server

1. Remove any older version of this plugin's jar.
2. Copy the new `PillarsOfFortune.jar` into `plugins/`.
3. **OPTIONAL but recommended:** Install WorldGuard for region boundaries.
4. Fully restart the server.
5. Confirm in console: "Pillars of Fortune enabled with N arena(s) loaded."

## Commands

```
/pof arena create <name>          - create a new named arena
/pof arena remove <name>          - delete an arena
/pof arena list                   - list all arenas + their state
/pof arena setlobby <name>        - set that arena's lobby spawn (stand in it first)
/pof arena setregion <name> <wgRegion>  - assign a WorldGuard region as the play boundary

/pof setpillar <name>             - register a pillar for that arena (stand on it first)
/pof removepillar <name>          - remove the closest pillar
/pof liststate <name>             - show arena status (state, player count, pillars)
/pof forcestart <name>            - start the game immediately
/pof stop <name>                  - force-stop a running game

/pof join <name>                  - join an arena directly
/pof leave                        - leave your current arena

/pof reload                       - reload config.yml and messages
```

## Quick Setup

```bash
/pof arena create main
/pof arena setlobby main           # stand in your lobby first
/pof setpillar main                # stand on pillar 1, repeat per pillar
/pof setpillar main
... repeat setpillar for all pillars ...
/pof arena setregion main pof_main # optional: WorldGuard region
```

Players join with `/pof join main` or via a sign (line 1: `[pof]`, line 2: `main`).

## Notes

- **Pillars must be registered before players can join** — the plugin needs one free pillar per player in the waiting room
- **Region name matching is case-sensitive** — `pof_main` ≠ `POF_MAIN`
- **The void-level check is always active** — players who fall to Y < -10 are eliminated regardless of region
- **No map-center/radius config** — use WorldGuard regions instead

## Changelog

- v2.0.4: Pillar teleport on join, freeze during waiting, Slow Falling on landing
- v2.0.3: WorldGuard region support
- v2.0.2: Fixed instant elimination at game start
- v2.0.1: Fixed permanent inventory loss
- v2.0.0: Initial multi-arena release
