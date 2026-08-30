# UltraMine — Minecraft 1.7.10 server core

**UltraMine is a high-performance Minecraft 1.7.10 dedicated-server core built on MinecraftForge 10.13.4.1614**, made for heavily modded production servers with hundreds of mods. Unlike Cauldron-family hybrids it deliberately does **not** implement the Bukkit API — server-side functionality comes from the core itself and from Forge mods.

## Project status: alive 🟢

The project was revived in 2026 after years of dormancy:

- ✅ **Builds again** — working Maven repositories, full CI on GitHub Actions
- ✅ **Verified releases** — every release is built from source by CI, ships `SHA256SUMS.txt` and cryptographically signed build provenance (no hand-uploaded binaries)
- ✅ **Dependencies refreshed** — log4j 2.17.2 (Log4Shell closed), snakeyaml 1.33, netty 4.0.56, and more
- ✅ **Runs on modern Java** — the core now boots on everything from **Java 8 to Java 25**, and CI verifies both ends on every build

## Java compatibility

| Java version | Status |
|---|---|
| **8** | Fully supported — the original code path, CI-boots every build |
| 9 – 24 | Expected to work — the launch layer is version-adaptive and `start.sh` adds the required JVM flags automatically; not individually CI-tested |
| **25 (LTS)** | Fully supported — CI-boots every build on Temurin 25 |

Note on mods: the **core** runs on Java 25, but individual 1.7.10 mods and coremods may rely on JVM internals removed after Java 8 — test your modpack. Scala-based mods will not work on modern JVMs (Scala 2.11 cannot run there).

## Features

Async chunk IO and background world generation, off-heap chunk storage, adaptive per-player chunk streaming, incremental world saving, tick-loop TPS regulation with a watchdog, per-chunk/per-mod profiling, entity load balancer, a rewritten mob-spawn engine, multiworld with zip import, YAML configuration (`server.yml` / `worlds.yml`), warps and homes, permissions and economy APIs for extensions, a cron scheduler, automatic backups with restore, and an item blocker. See [docs/](docs/README.md) for the full architecture documentation.

## Downloads

Grab the latest [release](../../releases). **`*-server-dist.zip`** is a ready-to-run server: unzip and run `start.sh` (Linux) or `start.cmd` (Windows) — the scripts pick the right JVM flags for your Java version. First boot creates `settings/server.yml`, `settings/worlds.yml` and the `worlds/` directory.

Every release can be verified:

```bash
sha256sum -c SHA256SUMS.txt --ignore-missing                              # integrity
gh attestation verify <file> --repo SashaGoncharov19/UltraMine-updated    # build provenance
```

The provenance check proves the file was built by this repository's public CI from a specific commit — details in [docs/07-ci-and-releases.md](docs/07-ci-and-releases.md).

## Building from source

```bash
cd ultramine/ultrasource
./gradlew jar_server serverDist        # requires JDK 8; Gradle comes via the wrapper
```

Artifacts land in `build/libs/` and `build/distributions/`. The build pipeline (MCP→notch reobfuscation, client/server side split) is described in [docs/02-build-system.md](docs/02-build-system.md).

## Documentation

- **[docs/README.md](docs/README.md)** — full English codebase documentation: architecture, launch flow, package map, build system, modernization roadmap
- Legacy Russian wiki (configuration guides): [Quickstart](https://github.com/4gname/UltraMine/wiki/Quickstart), [server.yml](https://github.com/4gname/UltraMine/wiki/Server.yml), [worlds.yml](https://github.com/4gname/UltraMine/wiki/Worlds.yml), [permissions](https://github.com/4gname/UltraMine/wiki/Permissions), [itemblocker.yml](https://github.com/4gname/UltraMine/wiki/Itemblocker.yml), [mob spawning](https://github.com/4gname/UltraMine/wiki/Спавн-мобов-в-UltraMine), [launch options](https://github.com/4gname/UltraMine/wiki/Launching)

## Chunk storage modes

Chunk sections are stored one of two ways, chosen at startup:

| `-Dorg.ultramine.chunk.storage=` | What a section is | When to use it |
|---|---|---|
| `offheap` *(default)* | one 12 KiB off-heap slot — no heap, no GC | anything that fits in vanilla's 4096 block ids |
| `vanilla` | vanilla's five heap arrays (`blockLSBArray`, `blockMSBArray`, `blockMetadataArray`, `blocklightArray`, `skylightArray`) | packs whose coremods patch chunk storage |

Both hold the identical packing, so a world is the same world in either mode.
The difference is what mods can reach: off-heap there are no arrays to patch, so
coremods built against vanilla's chunk storage cannot apply. `vanilla` gives
them the live arrays they expect, at the cost of the memory and GC time
off-heap storage exists to avoid.

Set it on the server's command line, before a world is generated:

```
java -Dorg.ultramine.chunk.storage=vanilla -jar ultramine-server.jar
```

The server logs which mode it started in, and warns if it finds mods that need
the other one. It never switches on its own.

## Known incompatible mods

FastCraft, ServerTools, ForgeEssentials, DragonAPI, zzzzzcustomconfigs — they coremod-patch the same internals UltraMine rewrites (see [docs/04](docs/04-vanilla-forge-modifications.md)).

**Mods that patch chunk storage** — EndlessIDs and NEID (which lift the 4095 block-id ceiling large packs run into), ChunkAPI, and **ArchaicFix's Phosphor backport** (`enablePhosphor`, on by default) — need `-Dorg.ultramine.chunk.storage=vanilla`. In the default off-heap mode they have nothing to patch: they either fail to load or silently do nothing, so leave Phosphor off (`B:enablePhosphor=false` in `config/archaicfix.cfg`) unless the server runs in `vanilla` mode. The rest of ArchaicFix works either way.

## Lineage & license

Original core by **vlad20012** (WTFPL — see `ultramine/ultrasource/LICENSE`), continued as the `4gname/UltraMine` fork, revived and modernized here. The legacy binaries in `ultramine/libraries/` and `ultramine/bootstrap/` predate the CI pipeline and are kept for history only — use the releases instead.
