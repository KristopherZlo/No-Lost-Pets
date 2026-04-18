# NoLostPets

![NoLostPets banner](banner.png)

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.8--1.21.11-5E7C16?style=for-the-badge)
![Loader](https://img.shields.io/badge/Loader-Fabric-DBD0B4?style=for-the-badge)
![Environment](https://img.shields.io/badge/Environment-Server--Side-1F6FEB?style=for-the-badge)
![License](https://img.shields.io/badge/License-GPL--3.0-2EA043?style=for-the-badge)

> Pet recall without chunk loading.
>
> Safe. Automatic. Server-side. Built for real lost pets.

NoLostPets is a Fabric mod for Minecraft `1.21.8` through `1.21.11` that brings companion pets back to their owner, including pets stored in unloaded chunks, without loading the original chunk first. It is built as one universal jar for the full `1.21.8-1.21.11` line.

For a shorter storefront-style description, see [MODRINTH_DESCRIPTION.md](MODRINTH_DESCRIPTION.md).

## What Is This?

**A server-side pet recall mod for pets that got left behind.**

- No minimap.
- No client UI.
- No teleporting random tamed mobs you do not own.
- No source chunk loading just to recover a pet.

It is meant for companion-style pets that should follow a player but can end up stuck in unloaded chunks after travel, death, portals, or server movement.

## Why Use It?

Vanilla follow logic only helps when the pet is already loaded.

NoLostPets is for servers and singleplayer worlds where:

- pets get stranded far away
- owners change dimension or respawn
- travel unloads the original chunk
- you want recovery without chunk-side effects in the source area

If the real problem is "my pet is lost somewhere outside simulation distance", this mod solves that problem directly.

## Features

- Recalls supported pets from unloaded chunks without loading the source chunk.
- Recalls already loaded pets too.
- Uses safe vanilla-style placement checks near the owner.
- Treats short grass as valid empty space and avoids water, fluids, and leaves.
- Skips sitting pets.
- Blocks cross-dimension recall on purpose.
- Preserves ownership checks for both loaded and unloaded recall paths.
- Works automatically in the background for unloaded pets.
- Triggers automatic checks on join, respawn, dimension change, chunk movement, landing, and major movement.
- Uses a delayed join warmup and optional owner-only repair scan instead of heavy immediate work.
- Batches unloaded recalls and throttles retries/backoff to reduce server spikes.
- Keeps per-world pet index data on the server.
- Cleans up stale records after repeated misses.
- Supports vanilla tameables and many modded pets with standard owner/sit NBT.
- Includes built-in admin stats and verify/self-test commands.
- Ships as one universal jar for Minecraft `1.21.8` through `1.21.11`.

## Commands

All commands require admin/operator permission.

- `/petrecall force <player>`
  Recalls the player's indexed pets, including loaded and unloaded pets.
- `/petrecall rescan <player>`
  Re-indexes currently loaded pets owned by that player.
- `/petrecall stats`
  Shows global runtime/index stats.
- `/petrecall stats <player>`
  Shows stats scoped to one player.
- `/petrecall verify singleplayer`
  Runs the built-in singleplayer self-test suite.
- `/petrecall verify multiplayer <otherPlayer>`
  Runs the ownership-focused multiplayer self-test suite.
- `/petrecall verify status`
  Shows current verify/self-test progress.
- `/petrecall verify cancel`
  Stops the active verify/self-test run.

## How It Works

### Recall Behavior

- Loaded pets are teleported to a safe spot near the owner.
- Unloaded pets are reconstructed from stored entity data and moved without loading the source chunk into active simulation.
- Placement prefers safe walkable positions near the player.
- Short grass is considered valid empty space, while water, fluids, and leaves are rejected.
- If no valid safe spot exists, recall fails instead of spawning the pet into a bad location.
- Pets are never recalled across dimensions.

### Automatic Recall

- Automatic recall only targets unloaded pets.
- Pets must belong to the player, be in the same dimension, and not be sitting.
- Automatic checks happen after join, respawn, world change, chunk movement, landing, and large travel events.
- Automatic runs are throttled and batched so the server does not spam recall work every tick.
- Join uses a short warmup and can do an owner-only loaded-pet repair scan when the index is empty.

### Pet Detection

- Vanilla `TameableEntity` mobs are supported directly.
- Many modded pets are supported if they expose normal owner UUID and sitting/follow signals in NBT.
- Tamed mounts such as horses, donkeys, mules, llamas, camels, and similar `AbstractHorseEntity` mobs are intentionally excluded.

### Stale Record Handling

- The server stores indexed pet records in persistent world data.
- When a record points to a pet that can no longer be found, the mod quarantines retries with backoff.
- After three consecutive misses, the stale record is removed automatically.

### Verification And Debugging

- Built-in verify commands can exercise loaded recall, unloaded recall, sitting-pet skips, ownership protection, safe-spot rules, auto-recall speed, batch recall, and stale-record cleanup.
- `stats` commands expose indexed/runtime counters for live debugging.
- Extra file tracing is available with `-Dnolostpets.debug=true`.

## Compatibility

| Minecraft | Status | Jar | Notes |
| --- | --- | --- | --- |
| `1.21.8` | Supported | Universal | Baseline build target |
| `1.21.9` | Supported | Universal | Same jar |
| `1.21.10` | Supported | Universal | Same jar |
| `1.21.11` | Supported | Universal | Same jar |

## Installation

### Dedicated Server

1. Install Fabric Loader for your Minecraft version.
2. Install the matching Fabric API version.
3. Put the universal `NoLostPets` jar into the server `mods` folder.
4. Start the server.

Clients do not need the mod on a dedicated server.

### Singleplayer

1. Install Fabric Loader and Fabric API.
2. Put the jar into your local `mods` folder.
3. Launch the game.

Singleplayer works because the integrated server runs the mod locally.

## FAQ

### Does this load the chunk where the pet was lost?

No. The unloaded-chunk recall path moves entity data without loading the original chunk into active simulation.

### Is this server-side?

Yes. On dedicated servers, only the server needs the mod.

### Does this work in singleplayer?

Yes. Singleplayer uses the integrated server.

### Does it support modded pets?

Many do, as long as they behave like companion pets and expose normal owner/sitting data.

### Does it recall sitting pets?

No. Sitting pets are skipped on purpose.

### Does it teleport pets between dimensions?

No. Cross-dimension recall is intentionally blocked.

### Does it support horses or other mounts?

No. Mount-style tamed mobs are intentionally excluded.

### Where is pet data stored?

In the world save as server persistent state under the mod's saved data.

## Build From Source

Java `21` is required.

```bash
./gradlew build
```

On Windows:

```powershell
.\gradlew.bat build
```

To build the universal artifact with the helper script:

```powershell
.\build-universal.ps1
```

Artifacts are written to `dist/universal/`.

## Local Test Clients

Windows helper scripts are included for all supported versions:

```powershell
.\run-1.21.8.ps1
.\run-1.21.9.ps1
.\run-1.21.10.ps1
.\run-1.21.11.ps1
.\verify-all.ps1
```

All test clients share the same runtime data in `run/shared`, including worlds, config, and `options.txt`, while still launching different Minecraft versions.

`verify-all.ps1` runs the built-in `verify singleplayer` and `verify multiplayer` command-path suites headlessly across `1.21.8` through `1.21.11`, sequentially, and writes per-version logs to `build/tmp/verify-all/`.

## Debug Logging

NoLostPets can write a dedicated trace file at `logs/NoLostPets-debug.log` inside the current game or server directory.

By default this trace is enabled in development environments. For normal server runs, enable it explicitly with:

```text
-Dnolostpets.debug=true
```

For local multi-version runs in this repo, the file is version-specific:

- `run/1.21.8/logs/NoLostPets-debug.log`
- `run/1.21.9/logs/NoLostPets-debug.log`
- `run/1.21.10/logs/NoLostPets-debug.log`
- `run/1.21.11/logs/NoLostPets-debug.log`

## Project Layout

- `src/main/` contains the mod logic, commands, tracking, recall service, and mixins.
- `src/gametest/` contains Fabric game tests.
- `scripts/` contains helper scripts for universal builds and per-version client runs.
- `FABRIC_COMPATIBILITY_NOTES.md` documents the verified Fabric version matrix for the `1.21.x` line.

## License

This project is licensed under `GPL-3.0-only`. See [LICENSE](LICENSE).
