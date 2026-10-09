# Minecraft 26 compatibility

This branch builds exact-version candidate jars. Compilation is checked; behavioral verification is pending.

| Minecraft | Fabric API | Loader | Java |
| --- | --- | --- | --- |
| 26.1 | 0.145.1+26.1 | 0.19.5 | 25 |
| 26.1.1 | 0.145.4+26.1.1 | 0.19.5 | 25 |
| 26.1.2 | 0.155.3+26.1.2 | 0.19.5 | 25 |
| 26.2 | 0.161.0+26.2 | 0.19.5 | 25 |
| 26.3 | 0.162.0+26.3 | 0.19.5 | 25 |

Build: Gradle wrapper 9.6.0, `net.fabricmc.fabric-loom` 1.17.21. Minecraft is unobfuscated; Yarn and remapping are not used.

The core uses public chunk readiness and ticket APIs, UUID ownership, and strict saved-index loading. Compile-time adapters cover entity constants moved in 26.2 and the LAN publishing signatures changed in 26.2 and 26.3. They do not broaden any jar's supported Minecraft version.

`scripts/build-26x.ps1` compiles main, unit-test, and GameTest sources, packages candidates, checks artifact metadata and Java class versions, and writes SHA-256 files. It does not run tests or publish a release. `scripts/verify-all.ps1` is the explicit behavioral verification entry point.

Before release, execute the prepared unit/GameTest suites and verify upgrade/restart of a copied 1.21.11 world, singleplayer, dedicated server, and LAN. Compilation alone does not establish runtime compatibility.

Sources: [Fabric 26.1 porting guide](https://docs.fabricmc.net/26.1.2/develop/porting/), [Fabric 26.2 changes](https://www.fabricmc.net/2026/06/15/262.html), [Fabric 26.3 changes](https://www.fabricmc.net/2026/09/15/263.html), and the official Minecraft server/client jars for the respective versions.
