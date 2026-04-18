# Fabric Compatibility Notes

These notes are based on official Fabric documentation and official Fabric metadata endpoints.

## Official sources

- https://docs.fabricmc.net/1.21.8/develop/getting-started/creating-a-project
- https://docs.fabricmc.net/1.21.11/develop/getting-started/creating-a-project
- https://docs.fabricmc.net/1.21.11/develop/porting/
- https://docs.fabricmc.net/develop/getting-started/creating-a-project
- https://docs.fabricmc.net/develop/porting/mappings/loom
- https://docs.fabricmc.net/develop/porting/next
- https://fabricmc.net/blog/
- https://meta.fabricmc.net/v2/versions/game
- https://meta.fabricmc.net/v2/versions/loader
- https://meta.fabricmc.net/v2/versions/yarn
- https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml

## What the docs say

- The Fabric docs page for `1.21.8` is written for that version and uses the current project-generation flow.
- The Fabric docs page for `1.21.11` is written for that version and points modders to the Fabric Develop site for the exact Minecraft, mappings, Loader, Loom, and Fabric API versions.
- The `1.21.11` porting docs say `1.21.11` is the final release where Yarn mappings will be available for porting to newer versions.
- The current Fabric docs page is written for `26.1.1`.
- The current `26.1` snapshot porting docs say mods still on Yarn must first migrate to Mojang mappings before porting to `26.1`.
- The same `26.1` docs also say to switch the Loom plugin id from `fabric-loom` to `net.fabricmc.fabric-loom`, remove the `mappings` dependency line, and replace `modImplementation` or `modCompileOnly` with `implementation` and `compileOnly`.

## Official version matrix

| Minecraft | Yarn | Loader | Fabric API | Status in this repo |
| --- | --- | --- | --- | --- |
| `1.21.8` | `1.21.8+build.1` | `0.18.2` | `0.136.1+1.21.8` | `compileJava` passes, `remapJar` passes |
| `1.21.9` | `1.21.9+build.1` | `0.18.2` | `0.134.1+1.21.9` | `compileJava` passes, `remapJar` passes |
| `1.21.10` | `1.21.10+build.3` | `0.18.2` | `0.138.4+1.21.10` | `compileJava` passes, `remapJar` passes |
| `1.21.11` | `1.21.11+build.4` | `0.18.2` | `0.141.3+1.21.11` | `compileJava` passes, `remapJar` passes |
| `26.1` | none | `0.18.2` | `0.145.1+26.1` | blocked on Yarn -> Mojang migration |
| `26.1.1` | none | `0.18.2` | `0.145.4+26.1.1` | blocked on Yarn -> Mojang migration |
| `26.1.2` | none | `0.18.2` | `0.145.4+26.1.2` | blocked on Yarn -> Mojang migration |

## Current conclusion

- The current source tree can be built for the full `1.21.8` through `1.21.11` line after the added compatibility bridge for world access, command permissions, and chunk NBT writes.
- `26.1.x` is not a simple dependency bump. It requires a dedicated port to Mojang mappings or unobfuscated names, plus build script changes described in the official Fabric porting docs.
- Treat `1.21.8-1.21.11` and `26.1.x` as separate release lines.
- Loom still prints a remap warning about the `storage` accessor during `remapJar`. The jars are produced successfully, but that accessor should be verified in a real game runtime before publishing broadly.
