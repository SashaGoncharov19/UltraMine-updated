# Modernization Notes — What Is Outdated and How to Update It

> Part of the codebase notes series. Index: [docs/README.md](README.md)
> Status: initial assessment (code survey only; nothing has been upgraded yet).

The project is a 2016–2017 codebase frozen around Minecraft 1.7.10 / Forge 10.13.4.1614. "Updating" can mean several different things with very different costs. This document inventories what is outdated and proposes a staged plan.

## A. Outdated component inventory

### Toolchain

| Component | Pinned | Problem | Direction |
|---|---|---|---|
| Gradle | 6.0.1 (wrapper) | Won't run on JDK 17/21 (needs JDK 8–13); deprecated APIs used by the build | Upgrade to Gradle 8.x; use Java toolchains (build runs on a modern JDK, compiles with a JDK 8 toolchain) |
| Java target | 8 | Fine for 1.7.10 (mods expect 8); modern JDKs break several core mechanisms (see D) | Keep target 8 short-term; treat "run on 17/21" as a separate project |
| Forge maven | `http://files.minecraftforge.net/maven` | Dead (HTTP, host retired) | `https://maven.minecraftforge.net` + `https://libraries.minecraft.net`; note `ultramine/libraries/libraries.zip` already contains a Maven-layout mirror of the whole runtime classpath and can serve as a local repo for offline builds |
| Sonatype snapshots repo | oss.sonatype.org | Being retired | Remove (nothing actually resolves from it) |
| Publishing block | commented out | Used Gradle-internal classes that broke on newer Gradle | Rewrite with public `maven-publish` API if publishing is needed |
| Versioning | git-tag based | This repo has **no tags** (history was re-uploaded), so builds produce version `indev` | Tag (e.g. `v0.1`) or set `-Poverride_version` |

### Runtime dependencies (server-relevant)

| Library | Pinned | Notes |
|---|---|---|
| log4j2 | ~~2.0-beta9~~ → **2.17.2** | **Upgraded in Stage 1.** Was within the Log4Shell-affected range (CVE-2021-44228 affects 2.0-beta9+). Both custom plugins ported; async logging kept (disruptor → 3.4.4). Residual risk: third-party coremods compiled against beta9 *core internals* (rare — mods normally use only the stable log4j API). |
| snakeyaml | ~~1.16~~ → **1.33** | **Upgraded in Stage 1.** CVE-2022-1471-class risk remains limited (YAML inputs are admin-owned config files); full fix (2.x + SafeConstructor-style loading) deferred — 2.x removes APIs `YamlConfigProvider` uses. |
| netty-all | ~~4.0.10.Final~~ → **4.0.56.Final** | **Upgraded in Stage 1** (last 4.0.x — near drop-in). 4.1.x needs a separate compile/runtime pass of the patched network classes. |
| guava | 17.0 | Many 1.7.10 mods compile against 17 — **do not bump** casually; keep at 17 unless shading. |
| mysql-connector-java | ~~5.1.31~~ → **5.1.49** | **Upgraded in Stage 1** to the last 5.1.x (keeps `com.mysql.jdbc.Driver` and MySQL 5.5-server support). 8.x deferred: renamed driver class + TLS/timezone default changes — needs fallback logic in `Databases` and user migration notes. |
| commons-dbcp2 | ~~2.1.1~~ → **2.9.0** | **Upgraded in Stage 1** (API-compatible). |
| ASM (`asm-debug-all`) | 5.0.3 | Cannot parse class files newer than Java 8 → blocks any modern-JVM work; `asm-debug-all` artifact is discontinued (→ `asm` + `asm-tree` + `asm-util` + `asm-commons` 9.x). UM's transformers + `ServiceDelegateGenerator` + buildSrc all use ASM. |
| koloboke | 0.6.8 | Abandoned project; works on 8, annotation-processor-generated impls may misbehave on newer JVMs. Candidate for replacement (fastutil) only if going beyond Java 8. |
| trove4j | 3.0.3 | Abandoned but stable on 8. |
| jline | 2.13 | Works; jline3 is a rewrite (only needed if console breaks on modern terminals). |
| Scala + Akka | 2.11.1 / 2.3.3 | Shipped because stock FML 1.7.10 ships them (Scala mod support). Akka/Scala are unused by UltraMine's own code. Scala 2.11 fails on modern JVMs — irrelevant while target is Java 8. |
| authlib | 1.5.16 | Mojang session auth for 1.7.10 still functions, but endpoints/certs have shifted over the years — verify `onlineMode=true` login against current Mojang/Microsoft session servers; community-patched authlibs exist if needed. |
| launchwrapper | 1.11 | Frozen tech; fine on Java 8. (Stock 1.7.10 used 1.12 — see `conf/build.gradle.forge`; difference is negligible.) |

### Infrastructure / ecosystem

- `maven.ultramine.ru` (bootstrap's artifact source, README link) — presumed dead; `bootstrap.jar` cannot download anything anymore. The offline `libraries.zip` route still works.
- Upstream UltraMine development stopped (~2017); this repo has no upstream git history (flat re-upload), so obtaining the original repo would help future diffing but is not required.
- Prebuilt `ultramine_core-1.7.10-server-0.1.5.jar` predates the two fork commits — after any source change the jar must be rebuilt to match.

## B. Security-relevant items (do these first)

1. **log4j 2.0-beta9** — see above. Short-term mitigation (before a real upgrade): launch with Mojang's message-lookup-stripping config or `-Dlog4j2.formatMsgNoLookups=true` equivalents where applicable to that lineage; real fix is the 2.17+ port.
2. **snakeyaml 1.16** — upgrade + constrain constructible types.
3. **mysql 5.1 driver** — old TLS defaults; upgrade alongside DBCP.
4. `settings.security` block exists in `server.yml` schema (`UltramineServerConfig`) — audit what it actually toggles when touching auth code.

## C. Staged modernization plan

### Stage 0 — "make it build again" (small, do first) — **IN PROGRESS**
1. ~~Fix repository URLs in `build.gradle`~~ **done**: `mavenCentral` + `https://libraries.minecraft.net` + `https://maven.minecraftforge.net`; dead sonatype repo removed. (Verified against Central: SpecialSource 1.7.3 and every Scala/ASM/koloboke/lwjgl/jinput coordinate resolve there; only Mojang-era artifacts — launchwrapper, authlib, realms, lzma, icu4j-core-mojang, paulscode, twitch — come from `libraries.minecraft.net`.)
2. ~~CI builds~~ **done**: GitHub Actions builds on JDK 8 (Temurin) with the stock Gradle 6.0.1 wrapper, wrapper-jar checksum validation, SHA-256 checksums, and tag-driven releases with signed provenance — see [07-ci-and-releases.md](07-ci-and-releases.md).
3. ~~Runnable distribution~~ **done**: new `serverDist` task zips server jar + `libraries/` + start scripts.
4. Version source: CI passes `-Poverride_version` (branch: `indev-<sha>`, release: the tag); the git-describe scheme remains for local use.
5. Remaining: boot-test a built server against a real mod set; optional zero-cost cleanups (rename `SpeicialClassTransformTask` → `SpecialClassTransformTask`, fix `UndoableOnce` statics, implement `CommandRegistry` map-view `remove` — see [05](05-ultramine-packages.md) §18); later, Gradle dependency verification (`gradle/verification-metadata.xml`) to pin dependency checksums.

*Gradle-8 migration notes:* `compile`/`runtime` configurations are gone (→ `implementation`/`runtimeOnly` or custom configurations — the build already uses custom ones, they just need `canBeResolved` flags), `IncrementalTaskInputs` (used by all three buildSrc tasks) was removed in Gradle 8 (→ `InputChanges`), and `SpecialSource:1.7.3` should bump to 1.11.x.

### Stage 1 — dependency & security refresh (moderate, still Java 8) — **DONE (first pass)**
- ~~log4j2 → 2.17.2~~ **done**: closes the Log4Shell-range exposure. Ported `UMConsoleLayout` (`helpers.Charsets`/`Constants` removed upstream → local replacements; `getMillis()` → `getTimeMillis()`) and `UMStripColorsRewritePolicy` (event copy now via `Log4jLogEvent.Builder`); replaced log4j-internal `Integers`/`Strings` helpers used by FML/vanilla (`FMLProxyPacket`, `FMLNetworkHandler`, `TwitchStream`). Disruptor → 3.4.4 (2.17 async requirement). `log4j2.xml` unchanged — verified compatible. The CI smoke test now fails on any log4j StatusLogger ERROR, so a broken logging setup cannot slip through.
- ~~snakeyaml → 1.33~~ **done** (dropped the removed `IntrospectionException` from `YamlConfigProvider`). 2.x deferred: it removes the `Representer()`/`Constructor()` APIs used here; revisit with a SafeConstructor-style hardening pass.
- ~~netty → 4.0.56.Final~~ **done** (last 4.0.x; 4.1 needs a separate compatibility pass).
- ~~dbcp2 → 2.9.0~~ **done** (API-compatible with `Databases`).
- ~~mysql-connector → 5.1.49~~ **done** — last 5.1.x, keeps `com.mysql.jdbc.Driver` and old-MySQL-server compatibility. 8.x deliberately deferred: it renames the driver class, changes TLS/timezone defaults and drops MySQL 5.5-server support; do it as an opt-in with `Databases` fallback logic.
- Bonus: Forge's version check now points at the live `https://maven.minecraftforge.net` promotions URL and logs one warning line on failure instead of a stack trace at every boot.
- Kept on purpose: guava 17, gson 2.2.4, Scala 2.11 (1.7.10 mods compile against these exact versions).
- Still to re-test manually on a real setup: JDBC player-data storage (`inSQLServerStorage`, not covered by the CI smoke test), RCON, console colors on Windows terminals, backups under load.

### Stage 2 — modern JVM support, target: **Java 25** — **CORE MILESTONE REACHED**

**The server boots to `Done` on Temurin 25 (and still on Java 8) — both are now mandatory CI gates on every build.** Landed beyond the increments below: a launchwrapper `URLClassLoader`-cast workaround (`ModernJavaLaunch` reimplements the tweaker flow for Java 9+, publishing the live `Tweaks` blackboard list FML mutates; Java 8 uses the stock path untouched), `@ObjectHolder` static-final writes via `Unsafe` (the `sun.reflect.ReflectionFactory` path is gone since Java 9), and an ASM 9 strict-descriptor fix in `EventSubscriptionTransformer` (`getObjectType` for internal names — ASM 5 silently tolerated the misuse and every Event subclass transform was failing). Remaining Stage 2 items: FFM backend for the off-heap chunk allocator (before JDK removes Unsafe memory ops), `CoreModManager.handleCascadingTweak`'s parent-loader `addURL` reflection (affects only coremods shipping cascading tweakers), and real modpack testing on 25.
Goal set by the maintainer: the server should start and run on **Java 25** (current LTS). Prior art: the GTNewHorizons 1.7.10 stack (lwjgl3ify/RFB) proves 1.7.10 on modern JVMs is possible, but they patch launchwrapper, coremods and many mods. Planned increments, each kept green on Java 8 while it lands:
1. **ASM 5 → 9.x** — **DONE** (runtime): `asm-debug-all:5.0.3` replaced with `asm`/`asm-tree`/`asm-commons` **9.10.1**; the only removed-API usages (`RemappingClassAdapter`/`RemappingMethodAdapter` in FML's `DeobfuscationTransformer`/`FMLRemappingAdapter`) ported to `ClassRemapper`/`MethodRemapper`. The transform pipeline can now parse modern class files. buildSrc deliberately stays on ASM 5 + SpecialSource 1.7.3 (build-time only, runs on JDK 8 in CI; SpecialSource 1.7.3 itself needs the old ASM API).
2. **`ServiceDelegateGenerator`** — **landed**: on Java 15+ it now defines service delegates via `MethodHandles.privateLookupIn(...).defineHiddenClass(...)` (resolved reflectively so the Java 8 baseline still compiles/runs; on 8–14 the old `Unsafe.defineAnonymousClass` path is used). Generated class names are normalized into the lookup class's package (a hidden-class requirement). Validated by the Java 25 smoke job.
3. **`FMLSecurityManager`** — **landed**: `FMLTweaker` now survives `System.setSecurityManager` throwing `UnsupportedOperationException` (Java 17+ without `-Djava.security.manager=allow`; always on 24+ per JEP 486) and continues with one log line — exit-trapping is still covered by `TerminalTransformer`'s bytecode rewrite.
4. **`UnsafeChunkAlloc`** off-heap storage: `sun.misc.Unsafe` memory methods are deprecated-for-removal (JEP 498) — still functional on 25 with warnings; add an FFM (`java.lang.foreign`) or `ByteBuffer.allocateDirect` backend behind the existing `ChunkAllocService` SPI.
5. Launch layer — **partially landed**: generated `start.sh` now detects the Java major version and adds the `--add-opens` set on 9+ and `--sun-misc-unsafe-memory-access=allow` on 23+ (`start.cmd` exposes a `MODERN_JAVA_FLAGS` variable). jline/jansi refresh still pending if the console misbehaves.
6. Known casualties on modern JVMs: Scala 2.11 mods (scala-compiler won't run), and any coremod generating pre-Java-8-era bytecode; document per-mod findings as they surface.
CI: **landed and passing** — the `smoke-java25` job boots the same built server-dist on Temurin 25 with the modern flag set on every build, and is now a required (non-experimental) gate alongside the Java 8 smoke.

### Stage 3 — functional updates (product decisions, see D)

## D. The "does not support Bukkit plugins" question

The README states this **by design**: UltraMine deliberately implements its own server-side APIs (services/DI, permissions, economy, commands, scheduler, multiworld — see [05](05-ultramine-packages.md)) instead of Bukkit's, arguing Cauldron-style hybrids are unstable under heavy mods.

Options, honestly assessed:

1. **Keep the philosophy** (cheapest): extend the native APIs; "plugins" are Forge mods using `org.ultramine.core.*` services. Existing ecosystem mods (permissions/anti-xray providers) plugged in this way.
2. **Bridge selected Bukkit APIs** (bounded but leaky): implement a compatibility layer for a *subset* (events, commands, permissions, economy via Vault-like shim) targeting specific plugins you actually need. Every additional plugin pulls in more of CraftBukkit's surface; NMS-using plugins (most interesting ones) will never work without a full CraftBukkit mapping layer.
3. **Full Bukkit API implementation** (Cauldron/Thermos-class effort): re-implement CraftBukkit 1.7.10 on top of UltraMine's *rewritten* internals (chunk map, command registry, per-world configs all differ from vanilla) — a multi-month project with licensing history to respect (the 2014 Bukkit DMCA). If Bukkit-plugin support on 1.7.10 is the hard requirement, evaluating existing maintained hybrids (Thermos/Crucible lineage) may be cheaper than adding Bukkit to UltraMine.

Recommendation: decide this *before* Stage 2 — it determines whether effort goes into UltraMine's own API surface or into a compat layer.

## E. How to build today (until Stage 0 lands)

```bash
# needs: JDK 8 on PATH, git, network (or the local-repo trick below)
cd ultramine/ultrasource
# 1) point the 'forge' repo at https://maven.minecraftforge.net in build.gradle
# 2) (offline option) unzip ../libraries/libraries.zip somewhere and add
#    maven { url uri('<path>/libraries') } as the first repository
./gradlew jar_server            # -> build/libs/ultramine_core-1.7.10-<ver>-server.jar
./gradlew dumpLibs              # -> build/libs/libraries/ (runtime classpath)
```

Run: copy jar + `libraries/` into a server dir, `java -server -XX:+UseG1GC -Xms2G -Xmx4G -jar <jar>`. First boot creates `settings/server.yml`, `settings/worlds.yml`, `worlds/`.

## F. Open questions for the maintainer

1. What does "update" mean for this project: (a) build/tooling revival, (b) dependency/security refresh, (c) Java 17/21 support, (d) Bukkit plugin support, (e) newer Minecraft version? (A newer MC version would be a rewrite — the value of UltraMine is precisely its 1.7.10 internals.)
2. Is there access to the original upstream repo (full history, tags) for reference?
3. Which mods/plugins must be supported? (Drives the Bukkit decision and regression-test list.)
4. Is the MySQL storage path (`inSQLServerStorage`) in real use? (Determines how carefully to treat the JDBC upgrade.)

## G. Modpack compatibility: findings from booting GTNH

The CI `Modpack boot test` (dispatch `build.yml` with `modpack_url`) overlays a
real server pack onto a freshly built server-dist and requires it to reach
`Done`. Running it against the current GT New Horizons daily pack (294 mods)
exposed a series of conflicts. Most were the same mistake in different places
and are now fixed; two are architectural and are documented as
incompatibilities.

### Fixed: the core must not diverge from vanilla shapes that coremods patch

A coremod compiled against stock 1.7.10 patches a *shape* — a field of a given
name and descriptor, an allocation site, a local-variable table. Where the core
had quietly changed that shape, the coremod's patch silently missed while the
rest of its rewrite still applied, and the class failed to load, taking every
dependent mod with it.

| Divergence | Symptom | Fix |
|---|---|---|
| `NBTTagCompound(int)` / `NBTTagList(int)` added to vanilla classes | `ClassFormatError: Duplicate method <init>(I)V` — coremods inject the same signature | static `withExpectedSize` factories instead |
| NBT map allocated in `createMap()` rather than inline in `<init>()` | `ClassCastException: HashMap → Object2ObjectOpenHashMap` on every `ItemStack.copy()` — Hodgepodge rewrites the inline `new HashMap()` and overwrites `copy()` to cast it | allocate inline, vanilla-shaped |
| `DataWatcher.watchedObjects` as `WatchableObject[32]` | `@Shadow field field_75695_b was not located` (CoreTweaks) → class fails to load | `WatchableObjectMap`: a `Map` outside, id-indexed array inside, grows past 32 |
| Forge prelude locals dropped from `World.updateEntityWithOptionalForce` | ArchaicFix's LVT capture fails the whole `World` class | locals restored, behaviour unchanged |
| Every Java-7+ class re-emitted through `COMPUTE_FRAMES` | mod classes fail to link (`GuiContainer` and friends are stripped server-side, so merge types dead-end) | untouched classes pass through byte-identical |
| log4j upgraded to 2.17.2 while mods target 2.0-beta9 | `NoSuchMethodError` in mod static initializers (GregTech), `NoClassDefFoundError: core/helpers/Loader` (CoFHCore) | call bridge for removed factory signatures + type remap for the moved `core.helpers` package |

Also fixed along the way: launchwrapper's transformer list is now copy-on-write
(coremods register transformers *during* iteration — a stock CME), and a failed
launch prints its real stack trace instead of being swallowed by `halt()`.

### Architectural: block-id width and lighting

These two cannot be papered over — they are competing implementations of the
same subsystem, so one side has to give:

- **Block ids.** `MemSlot` packs a block as 12 bits of id plus 4 of metadata:
  4096 ids, exactly vanilla's ceiling. Modern large packs exceed it — GTNH asks
  for id **10617** — and solve it with **EndlessIDs**/NEID, which `@Shadow` the
  vanilla `blockLSBArray`/`blockMSBArray` arrays and extend them. This core has
  no such arrays (blocks live off-heap), so EndlessIDs fails to apply, and
  without it the pack runs out of ids. **GTNH-class packs are therefore out of
  reach until `MemSlot` itself carries wider ids** — a chunk-format change, and
  the natural next big step if such packs are a goal.
- **Lighting.** ArchaicFix's Phosphor backport reads, caches and assigns the
  vanilla `NibbleArray` light fields. Chunk light is off-heap here and
  `getSkylightArray()` materializes a copy, so Phosphor cannot work against it:
  set `B:enablePhosphor=false` in `config/archaicfix.cfg`. The rest of
  ArchaicFix is fine.

Everything else in the pack — 293 of 294 mods including GregTech, Thaumcraft,
AE2, Railcraft, Forestry and the whole GTNH coremod stack — got through mod
construction and pre-init on this core.
