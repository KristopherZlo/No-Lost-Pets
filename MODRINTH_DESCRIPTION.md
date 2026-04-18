# NoLostPets

Recall your companion pets to your position, including pets stored in unloaded chunks, without loading their original chunks.

## Main feature

**NoLostPets can move supported pets out of unloaded chunks without loading those chunks at all.**

That means it avoids triggering chunk mechanics in the source area while still bringing your companion back.

## What it does

- Recalls supported companion pets from unloaded chunks by moving entity data directly
- Does **not** load source chunks while doing it (no chunk-tick side effects there)
- Recalls supported companion pets from loaded chunks too
- Uses safe-spot placement near the player (vanilla-style checks)
- Skips sitting pets
- Supports silent automatic recall triggers (vanilla-like behavior)

## Why this mod exists

Pets can get left behind after long travel, teleports, server movement, or chunk unloads. Vanilla follow logic cannot help when the pet is not loaded. NoLostPets fills that gap with a chunk-safe recall approach that keeps placement predictable.

## Compatibility

- Built for Fabric `1.21.11`
- Designed for vanilla companion-style pets and many modded pets with standard owner/sit data
- Works on dedicated servers

## Notes

- This mod intentionally targets follow-capable companion pets, not every tamed/owned mob
