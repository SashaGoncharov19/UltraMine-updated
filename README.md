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
| 9 – 24 | Expected to work — the launch layer is version-adaptive; not individually CI-tested |
| **25 (LTS)** | Fully supported — CI-boots every build on Temurin 25 |

No JVM flags are needed on any version. The server jar's manifest carries the
`Add-Opens` attribute, so `java -jar ultramine_core-*-server.jar` opens the JDK
internals launchwrapper, FML and Mixin reflect into — this matters on hosting
panels, where there is often nowhere to put JVM flags. (Java 8 ignores the
attribute; it needs nothing.) On Java 23+ add
`--sun-misc-unsafe-memory-access=allow` to silence the off-heap storage warning
— that one cannot be expressed in a manifest.

Note on mods: the **core** runs on Java 25, but individual 1.7.10 mods and coremods may rely on JVM internals removed after Java 8 — test your modpack. Scala-based mods will not work on modern JVMs (Scala 2.11 cannot run there).

## Features

Async chunk IO and background world generation, off-heap chunk storage, adaptive per-player chunk streaming, incremental world saving, tick-loop TPS regulation with a watchdog, per-chunk/per-mod profiling, entity load balancer, a rewritten mob-spawn engine, multiworld with zip import, YAML configuration (`server.yml` / `worlds.yml`), warps and homes, permissions and economy APIs for extensions, a cron scheduler, automatic backups with restore, and an item blocker. See [docs/](docs/README.md) for the full architecture documentation.

## Downloads

Grab the latest [release](../../releases). **`*-server-dist.zip`** is a ready-to-run server: unzip and run `start.sh` (Linux) or `start.cmd` (Windows), or just `java -jar` the server jar — no JVM flags required. First boot creates `settings/server.yml`, `settings/worlds.yml` and the `worlds/` directory.

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

**GT New Horizons 2.8.4** — the release the download page serves — boots on this
core in `vanilla` mode: the whole pack, nothing excluded, ArchaicFix's Phosphor
left enabled, on Java 8. CI reproduces it: dispatch the Build workflow with
`modpack_url=gtnh-release` and `chunk_storage=vanilla`. `gtnh-latest` runs the
newest daily build instead, which is a moving target and not the gate.

**Run coremod-heavy packs on Java 8.** The bare server runs on Java 8 through 25
and both are CI gates, but a pack that ships coremods does not boot on Java 9+
yet: launchwrapper, FML and Mixin all assume the loader that loaded them is a
`URLClassLoader`, which no JVM after 8 provides. See
[docs/06](docs/06-modernization-notes.md) for how far it gets and what is left.

Set it on the server's command line, before a world is generated:

```
java -Dorg.ultramine.chunk.storage=vanilla -jar ultramine-server.jar
```

The server logs which mode it started in, and warns if it finds mods that need
the other one. It never switches on its own.

## Running a modpack

Take the pack's **`mods/`, `config/`, `scripts/` and `resources/`** and drop them
next to the server jar. Do **not** copy the pack's `libraries/` folder or the
Forge jar it ships — this core is a merged build of Minecraft, Forge, FML and
UltraMine, and its own `libraries/` are named in the jar manifest's `Class-Path`.

For **GT New Horizons**, take the **`server-java8`** pack variant and run it on
Java 8:

```
java -server -Xms6G -Xmx6G -Dfml.readTimeout=180 \
     -Dorg.ultramine.chunk.storage=vanilla \
     -jar ultramine_core-*-server.jar nogui
```

The `server-java17-26` variant is a different arrangement: it ships **lwjgl3ify**
and boots through `lwjgl3ify-forgePatches.jar`, a repackaged Forge that patches
itself for modern Java. That replaces the core rather than running on it — point
it at this jar and lwjgl3ify's relauncher aborts with *"does not support server
launches"*, taking the boot down with it. Neither `lwjgl3ify-forgePatches.jar`
nor its `java9args.txt` has a role here; this core does its own modern-Java
launch, and the jar manifest supplies the opens they exist to pass.

`eula.txt` is not needed — UltraMine does not carry that gate.

## Known incompatible mods

FastCraft, ServerTools, ForgeEssentials, DragonAPI, zzzzzcustomconfigs — they coremod-patch the same internals UltraMine rewrites (see [docs/04](docs/04-vanilla-forge-modifications.md)).

**lwjgl3ify** — a client-side LWJGL 3 shim whose server half is a relauncher that
only works when it is the thing being launched. It refuses a server boot on any
Java version and takes FML down with it. Remove it from `mods/`; nothing else in
a pack depends on it server-side.

**Mods that patch chunk storage** — EndlessIDs and NEID (which lift the 4095 block-id ceiling large packs run into), ChunkAPI, and **ArchaicFix's Phosphor backport** (`enablePhosphor`, on by default) — need `-Dorg.ultramine.chunk.storage=vanilla`. In the default off-heap mode they have nothing to patch: they either fail to load or silently do nothing, so leave Phosphor off (`B:enablePhosphor=false` in `config/archaicfix.cfg`) unless the server runs in `vanilla` mode. The rest of ArchaicFix works either way.

## Lineage & license

Original core by **vlad20012** (WTFPL — see `ultramine/ultrasource/LICENSE`), continued as the `4gname/UltraMine` fork, revived and modernized here. The legacy binaries in `ultramine/libraries/` and `ultramine/bootstrap/` predate the CI pipeline and are kept for history only — use the releases instead.
