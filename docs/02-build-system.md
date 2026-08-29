# Build System

> Part of the codebase notes series. Index: [docs/README.md](README.md)
> Project root for everything below: `ultramine/ultrasource/`

## Toolchain requirements

- **JDK 8** (`sourceCompatibility = targetCompatibility = '1.8'`; the IDEA run config and Start scripts also assume JRE 1.8).
- **Gradle 6.0.1** via the wrapper (`gradle/wrapper/gradle-wrapper.properties`). Gradle 6 does not run on modern JDKs (17/21), so building currently requires a real JDK 8 install (see [06-modernization-notes.md](06-modernization-notes.md)).
- **git** on PATH — the version number is computed from `git describe --tags` at configure time.
- Network access to Maven repos (see below — one of them is dead today).

## Repositories and dependencies

Declared in `build.gradle`:

| Repo | URL | Status today |
|---|---|---|
| forge | `http://files.minecraftforge.net/maven` | **dead** (moved to `https://maven.minecraftforge.net`, plain HTTP no longer served) |
| mavenCentral | — | OK |
| sonatypeSnapshot | `https://oss.sonatype.org/content/repositories/snapshots/` | legacy, being retired |
| minecraft | `https://libraries.minecraft.net/` | OK |

Dependency configurations model the client/server split:

```
compileCommon / compileClient / compileServer      (compile-time, per side)
runtimeCommon / runtimeClient / runtimeServer      (runtime-only, per side)
packageClient / packageServer / packageAll         (aggregates; used for Class-Path + dumpLibs)
```

Noteworthy runtime stack (all pinned to the 1.7.10 era): launchwrapper 1.11, ASM `asm-debug-all` 5.0.3, Netty 4.0.10.Final, Guava 17.0, log4j2 **2.0-beta9**, Scala 2.11.1 + Akka 2.3.3 (FML ships Scala for scala mods), trove4j 3.0.3, **koloboke** 0.6.8 (high-perf primitive collections, used inside patched vanilla classes), snakeyaml 1.16 (UM configs), LMAX disruptor 3.2.1 (async log4j), commons-dbcp2 2.1.1 + mysql-connector 5.1.31 (server-side DB pool), jline 2.13 (server console). Client-only: LWJGL 2.9.1, paulscode sound, realms, twitch.

Test stack: Spock 1.1 (groovy 2.4).

## Custom build logic (`buildSrc/`)

Three Gradle task classes under `org.ultramine.gradle.task` (plus helpers in `...gradle.internal`), built against `net.md-5:SpecialSource:1.7.3` and ASM:

1. **`SpeicialClassTransformTask`** (sic — typo in the class name) — bytecode string-replacement. Used by the `injectVersion` task to replace the `@version@` placeholder inside `org.ultramine.server.UltramineServerModContainer` with the computed version, directly in the compiled `.class`.
2. **`ReobfTask`** — remaps compiled classes **MCP → notch** using `conf/mcp2notch.srg` via SpecialSource's `JarMapping`/`JarRemapper` (1836 class mappings + field/method mappings; `net/minecraft/src` maps to the default package). This is why the shipped jar contains obfuscated vanilla names just like a vanilla+Forge server, keeping binary compatibility with 1.7.10 mods (FML deobfuscates mod references at runtime using `deobfuscation_data-1.7.10.lzma`).
3. **`SideSplitTask`** — reads every class with ASM, inspects `@cpw.mods.fml.relauncher.SideOnly` on classes/fields/methods, and writes two stripped class trees: `classes_server` (client-only members removed) and `classes_client`. Supports incremental builds.

## Task pipeline

```
compileJava
   └─> injectVersion   (stamp @version@ into UltramineServerModContainer)
        └─> reobf      (MCP -> notch, conf/mcp2notch.srg)
             └─> sidesplit  (strip @SideOnly per side)
                  ├─> jar_server  (+ processServerResources; manifest: Main-Class
                  │    cpw.mods.fml.relauncher.ServerLaunchWrapper, TweakClass FMLTweaker,
                  │    Class-Path libraries/*.jar)             -> classifier 'server'
                  └─> jar_client  (+ processClientResources)   -> classifier 'client'
jar            -> classifier 'dev'      (MCP names, unsplit — for dev use)
jar_universal  -> classifier 'universal' (reobf, unsplit; disabled by default)
jar_source     -> classifier 'sources'
dumpLibs       -> copies the runtime configuration into build/libs/libraries (the folder
                  the server jar's Class-Path points at)
```

Resource splitting: `processServerResources` excludes client assets (`assets/minecraft/{font,shaders,texts,textures}`, `assets/fml/textures`); `processClientResources` excludes `org/ultramine/defaults` (server-side config templates).

Artifact switches in `gradle.properties`: `produce_server_jar=true`, `produce_client_jar=true`, `produce_universal_jar=false`.

## Versioning scheme

`computeVersion()` in `build.gradle`:

- `major.minor` always comes from the latest git tag `v<major>.<minor>` (`git describe --tags --long`).
- `release_type` in `gradle.properties` selects the format:
  - `indev` (current default) → `{major}.{minor}.0-indev`; if no tag exists, just `indev`.
  - `stable` → `{major}.{minor}.{revision}` with the revision auto-incremented per build (state kept in `build/versions/<major.minor>` as `commit:revision`).
  - anything else (`alpha`/`beta`/`rc`) → `{major}.{minor+1}.0-{type}.{revision}`.
- `minecraft_version` (1.7.10) is concatenated into the **project name** (`concat_mc_version_to=name`), producing artifacts like `ultramine_core-1.7.10-server-0.1.5.jar`.
- Overrides: `-Poverride_version=`, `-Poverride_revision=`, `-Pincrement_revision`.
- `changelog` task generates `...-changelog.txt` from `git log` between the previously-built commit and HEAD.
- The maven `publishing` block is fully commented out (it used Gradle-internal APIs — `ArchivePublishArtifact`, `JavaLibrary` — that broke on newer Gradle; publishing is effectively disabled).

## Mappings (`conf/`)

SpecialSource SRG files for 1.7.10 / MCP 9.05: `mcp2notch.srg`, `mcp2srg.srg`, `notch2mcp.srg`, `notch2srg.srg` (+ `mcp.exc`, `srg.exc` exceptor data, and `build.gradle.forge` — a reference copy of stock Forge's dev build script, not used by the build).

Sources are kept in **MCP names** (readable, e.g. `MinecraftServer.getServerThread()`), with some members still in SRG form (`func_151354_b`) where MCP had no name.

## Running from source

IDEA run config `.run/Run Server.run.xml`: main class `cpw.mods.fml.relauncher.ServerLaunchWrapper`, working dir `build/server_dir`, JRE 1.8, module `ultramine_core-1.7.10.main`. In dev the classes are in MCP names and FML runs in deobf mode; no reobf needed.

Production start (from `ultramine/libraries/Start.cmd`):

```
java -server -XX:+UseG1GC -XX:+UseStringDeduplication -Xms2G -Xmx4G -jar ultramine_core-1.7.10-server-0.1.5.jar
```

Useful system properties observed in code: `-Dorg.ultramine.terminal=default|raw|ansi|jline` (console mode), `-Dultramine.debug.chunksyncload` (warn on synchronous chunk loads), `-Dorg.ultramine.core.nbt.useKolobokeMap` (koloboke-backed NBTTagCompound).
