# UltraMine Codebase Notes

English documentation of the UltraMine server core, produced by walking the actual sources in this repository (`ultramine/ultrasource/`). Written as groundwork for updating/modernizing the core.

## Quick facts

- Minecraft **1.7.10** dedicated-server core built as a fork-with-merged-sources of **Forge 10.13.4.1614 / FML 7.99.39.1** (MCP 9.05).
- **No Bukkit API** — by design; server features come from the core's own APIs (services/DI, permissions, economy, commands, multiworld, scheduler).
- Java 8, Gradle 6.0.1, custom build pipeline (version inject → MCP→notch reobfuscation → client/server side split).
- ~2040 Java files: 1574 vanilla+Forge, 248 FML, **217 UltraMine** (`org.ultramine`, ~22k LOC).
- UltraMine touches ~54 vanilla/FML files (chunk pipeline, tick loop, networking, commands); the rest of its logic lives in `org.ultramine`.
- Headline server features: async chunk IO + background generation, off-heap chunk storage (`sun.misc.Unsafe`), adaptive per-player chunk streaming, incremental world saving, TPS regulation + watchdog, per-chunk/per-mod profiling, entity load balancer, new mob-spawn engine, multiworld with import/temp worlds, YAML configs (`server.yml`/`worlds.yml`), warps/homes, economy API, permissions API, crontab scheduler, backups/restarts, item blocker, RU/EN command transliteration.

## Contents

| Doc | What's inside |
|---|---|
| [01-overview.md](01-overview.md) | What UltraMine is, repo layout, distribution model, versions, this fork's delta |
| [02-build-system.md](02-build-system.md) | Gradle build, buildSrc tasks (reobf/side-split), mappings, versioning, how artifacts are produced |
| [03-launch-and-runtime.md](03-launch-and-runtime.md) | Startup chain (ServerLaunchWrapper → FML → DedicatedServer), rewritten tick loop, threads, console/logging/RCON, runtime dirs |
| [04-vanilla-forge-modifications.md](04-vanilla-forge-modifications.md) | Exactly what was changed inside `net.minecraft*` / `cpw.mods.fml` and why mods conflict |
| [05-ultramine-packages.md](05-ultramine-packages.md) | Package-by-package map of `org.ultramine`: every subsystem with key classes |
| [06-modernization-notes.md](06-modernization-notes.md) | Outdated-component inventory, security items, staged update plan, the Bukkit question, build-today recipe |
| [07-ci-and-releases.md](07-ci-and-releases.md) | GitHub Actions build/release pipeline, checksums, signed build provenance, how users verify downloads |

## Reading order

New to the codebase → 01, 03, 05. Planning the update work → 02, 04, 06.
