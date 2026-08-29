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
| log4j2 | **2.0-beta9** | Within the Log4Shell-affected range (CVE-2021-44228 affects 2.0-beta9+; Mojang shipped a mitigation config for 1.7–1.11.2). Also a *beta* of the plugin API that `UMConsoleLayout`/`UMStripColorsRewritePolicy` are compiled against — upgrading to 2.17+ requires porting those two plugins and re-testing async logging (Disruptor selector). Highest-priority security item. |
| snakeyaml | 1.16 | CVE-2022-1471 class of issues (unsafe global tags). Risk is limited because YAML inputs are admin-owned config files, but upgrade to 1.33/2.x with `SafeConstructor`-style loading is cheap insurance. `YamlConfigProvider` uses custom `PropertyUtils` — check API drift. |
| netty-all | 4.0.10.Final | Very old; upgrade within 4.0.x (→ 4.0.56.Final) is near drop-in; 4.1.x needs a compile check of patched network classes. Mods bundling their own netty usage generally tolerate 4.1. |
| guava | 17.0 | Many 1.7.10 mods compile against 17 — **do not bump** casually; keep at 17 unless shading. |
| mysql-connector-java | 5.1.31 | Old JDBC driver; `JDBCDataProvider`/`Databases` reference `com.mysql.jdbc.Driver` by name — 8.x renamed the class (`com.mysql.cj.jdbc.Driver`) and changed defaults (timezone, SSL). Straightforward but needs code touch. |
| commons-dbcp2 | 2.1.1 | Upgrade freely (2.9+). |
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

### Stage 0 — "make it build again" (small, do first)
1. Install a JDK 8 toolchain (e.g. Temurin 8) or move to Gradle 8 + toolchains right away.
2. Fix repository URLs in `build.gradle` (`https://maven.minecraftforge.net`, drop sonatype); optionally add `ultramine/libraries/libraries.zip` (unzipped) as a local Maven repo for full offline builds.
3. Verify the whole pipeline: `compileJava → injectVersion → reobf → sidesplit → jar_server`, and that the produced jar boots a test server with `libraries/` beside it.
4. Set a version source (git tag or `-Poverride_version`) so artifacts aren't named `indev`.
5. Optional cleanups that cost nothing: rename `SpeicialClassTransformTask` → `SpecialClassTransformTask`, fix `UndoableOnce` statics, implement `CommandRegistry` map-view `remove` (see [05](05-ultramine-packages.md) §18).

*Gradle-8 migration notes:* `compile`/`runtime` configurations are gone (→ `implementation`/`runtimeOnly` or custom configurations — the build already uses custom ones, they just need `canBeResolved` flags), `IncrementalTaskInputs` (used by all three buildSrc tasks) was removed in Gradle 8 (→ `InputChanges`), and `SpecialSource:1.7.3` should bump to 1.11.x.

### Stage 1 — dependency & security refresh (moderate, still Java 8)
- log4j2 → 2.17.2 (port the two custom plugins, keep async logging), snakeyaml → 1.33/2.x, dbcp2 → 2.9+, mysql → 8.x (rename driver class, set `serverTimezone`), netty → 4.0.56 (then evaluate 4.1), commons-* bumps.
- Keep guava 17 and Scala 2.11 for mod compatibility.
- Re-test: async chunk IO, JDBC storage, RCON, console colors, backups.

### Stage 2 — modern JVM support (large, optional)
Only worth it if running mods on Java 17/21 is a goal (prior art: the GTNewHorizons 1.7.10 stack — lwjgl3ify/RFB — proves it's possible but they patch launchwrapper, coremods and many mods):
- ASM 5 → 9.x everywhere (runtime transformers + buildSrc).
- `ServiceDelegateGenerator`: `Unsafe.defineAnonymousClass` → `MethodHandles.Lookup.defineHiddenClass` (JDK 15+) with a Java-8 fallback.
- `UnsafeChunkAlloc`: still works through 21 (with warnings); plan an FFM (`java.lang.foreign`) or `ByteBuffer.allocateDirect` backend before JDK 24+.
- `FMLSecurityManager` (`cpw/mods/fml/common/FMLSecurityManager`): SecurityManager is permanently disabled in JDK 24 — must become a no-op.
- `--add-opens` set for launchwrapper's classloader tricks; jline/jansi upgrades; Scala mods will simply not work.

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
