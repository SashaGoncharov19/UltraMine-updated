# UltraMine — Project Overview

> Part of the codebase notes series. Index: [docs/README.md](README.md)

## What this is

**UltraMine** is a Minecraft **1.7.10** dedicated-server core built on top of **MinecraftForge 10.13.4.1614 / FML 7.99.39.1** (MCP 9.05 mappings). It targets high-load production servers running hundreds of Forge mods.

Key positioning (from the original README):

- Unlike Cauldron/Thermos, it does **not** implement the Bukkit API and does **not** support Bukkit plugins. Server-side functionality is provided by the core itself (permissions, multiworld, teleportation, economy hooks, etc.) and by Forge mods.
- The focus is performance and operability: async chunk IO, incremental world saving, a rewritten tick loop with TPS regulation, off-heap chunk storage, a watchdog, per-mod/per-player profiling attribution.

Original author: `vlad20012` (license: WTFPL, see `ultramine/ultrasource/LICENSE`). This repository (`SashaGoncharov19/UltraMine-updated`) is a re-upload of the `4gname/UltraMine` fork of the original project.

## Repository layout

This is a **distribution repo + full sources**, not a patch-based Forge workspace:

```
/
├── README.md                     # Russian; wiki links, known incompatibilities
└── ultramine/
    ├── bootstrap/
    │   └── bootstrap.jar         # org.ultramine.bootstrap.Main — installer/launcher
    ├── libraries/
    │   ├── libraries.zip         # ~83 MB of runtime jars + SHA-1 checksums
    │   ├── ultramine_core-1.7.10-server-0.1.5.jar   # prebuilt server core
    │   └── Start.cmd             # Windows start script (G1GC, 2–4 GB heap)
    └── ultrasource/              # the actual Gradle source project
        ├── build.gradle          # root build (jar_server/jar_client/reobf/sidesplit)
        ├── gradle.properties     # versioning + artifact switches
        ├── settings.gradle
        ├── buildSrc/             # custom Gradle tasks (reobf, side split, class transform)
        ├── conf/                 # MCP<->SRG<->notch mappings for 1.7.10 (SpecialSource format)
        ├── gradle/wrapper/       # Gradle 6.0.1 wrapper
        ├── .run/                 # IDEA run config ("Run Server", JRE 1.8)
        └── src/
            ├── main/java/
            │   ├── cpw/mods/fml/         # FML sources (248 files)
            │   ├── net/minecraft*/       # full decompiled MC 1.7.10 + Forge (1574 files)
            │   └── org/ultramine/        # UltraMine's own code (217 files)
            ├── main/resources/           # fmlversion.properties, ATs, log4j2.xml, defaults
            └── test/java/                # 1 Spock test (service delegate generator)
```

Unlike stock Forge development (vanilla jar + binary patches applied at install time), UltraMine keeps a **fully merged source tree**: decompiled Minecraft with Forge/FML patches already applied and UltraMine's own modifications made directly in the vanilla sources (marked with `/* ===== ULTRAMINE START/END ===== */` comment blocks in many files). The build then re-obfuscates everything back to vanilla ("notch") names so the resulting jar is mod-compatible.

## Distribution model

1. `bootstrap.jar` (`org.ultramine.bootstrap.Main`) is a self-contained installer: it resolves the `ultramine_core` artifact and its dependency tree from Maven repositories (historically `maven.ultramine.ru`), verifies SHA-1 checksums, and generates start scripts (`ScriptCreator`). Bundles its own Maven-metadata/versioning code and Apache commons-io.
2. `libraries.zip` is the offline equivalent: the full runtime classpath laid out both flat and in Maven-repo layout, with `checksums/*.sha1`.
3. The server jar's manifest `Class-Path` references `libraries/<jar>` relative paths, so the server runs as `java -jar ultramine_core-...-server.jar` with the `libraries/` folder next to it.

## Versions at a glance

| Component | Version | Where defined |
|---|---|---|
| Minecraft | 1.7.10 | `gradle.properties` (`minecraft_version`), `cpw/mods/fml/common/Loader.java` (`MC_VERSION`) |
| Forge | 10.13.4.1614 | `net/minecraftforge/common/ForgeVersion.java` |
| FML | 7.99.39.1 | `src/main/resources/fmlversion.properties` |
| MCP mappings | 9.05 | `fmlversion.properties`, `mcpmod.info`, `conf/*.srg` |
| UltraMine core | 0.1.5 (prebuilt jar); source builds derive version from git tags | `build.gradle` `computeVersion()` |
| Java | 8 (source/target 1.8) | `build.gradle` |
| Gradle | 6.0.1 (wrapper) | `gradle/wrapper/gradle-wrapper.properties` |

## Known mod incompatibilities (from README)

FastCraft, ServerTools, ForgeEssentials, DragonAPI, zzzzzcustomconfigs, NEID.

These are consistent with how deep UltraMine's changes go: those mods coremod-patch the same vanilla areas (chunk handling, world tick, network, IDs) that UltraMine has rewritten (see [04-vanilla-forge-modifications.md](04-vanilla-forge-modifications.md)).

## This fork's delta (vs. upstream UltraMine)

Only two functional commits on top of the imported sources, both by `4gname` (2022-01-27):

1. `9b80111` — `PlayerManager.PlayerInstance.playersWatchingChunk` changed `private` → `public` so the **ChickenChunks** mod can access it.
2. `1bb56ca` — new `server.yml` flag `settings.other.spamLagConsole` (default `false`) gating the "Possible lag source ..." warnings in `NetworkManager` (slow packet handling), `World` (slow entity/tile-entity updates/unloads). Accessor `UltramineServerConfig.isSpamLogConsole()`.

Everything else in the git history is the initial upload of the distribution and sources.

## Documentation index

- [02-build-system.md](02-build-system.md) — Gradle build, custom tasks, versioning, how to build.
- [03-launch-and-runtime.md](03-launch-and-runtime.md) — startup chain, tick loop, watchdog, logging, console.
- [04-vanilla-forge-modifications.md](04-vanilla-forge-modifications.md) — what UltraMine changed inside vanilla/Forge/FML and where.
- [05-ultramine-packages.md](05-ultramine-packages.md) — map of `org.ultramine` packages and subsystems.
- [06-modernization-notes.md](06-modernization-notes.md) — inventory of outdated components and an update roadmap.
