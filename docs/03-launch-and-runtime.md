# Launch Chain & Server Runtime

> Part of the codebase notes series. Index: [docs/README.md](README.md)
> Paths below are relative to `ultramine/ultrasource/src/main/java/`.

## Startup sequence (dedicated server)

The server jar manifest (set by `jar_server` in `build.gradle`) declares
`Main-Class: cpw.mods.fml.relauncher.ServerLaunchWrapper` and `Class-Path: libraries/*.jar`.

1. **`cpw/mods/fml/relauncher/ServerLaunchWrapper.java`** — the very first UltraMine hook. Before anything else it calls `UMBootstrap.handleFirstLine(args)` (`org/ultramine/server/bootstrap/UMBootstrap.java`), which:
   - forces log4j2's async logger (`Log4jContextSelector = AsyncLoggerContextSelector`, LMAX Disruptor-backed);
   - selects the console mode from `-Dorg.ultramine.terminal=default|raw|ansi|jline` and creates the JLine `ConsoleReader` when appropriate;
   - sets the terminal charset and prints a greeting banner.

   It then reflectively invokes `net.minecraft.launchwrapper.Launch.main(...)`, injecting `--tweakClass cpw.mods.fml.common.launcher.FMLServerTweaker`.
2. **launchwrapper** (external dep `net.minecraft:launchwrapper:1.11`) sets up `LaunchClassLoader` and runs the tweaker.
3. **`cpw/mods/fml/common/launcher/FMLTweaker.java` → `FMLServerTweaker.java`** — installs `FMLSecurityManager`, parses args, configures the class loader, and hands off to `FMLLaunchHandler.configureForServerLaunch(...)`. Launch target: `net.minecraft.server.MinecraftServer`.
4. **`cpw/mods/fml/relauncher/FMLLaunchHandler.java`** — sets `Side.SERVER`, reads `fmlversion.properties` (`FMLInjectionData`), then runs coremods via `CoreModManager.handleLaunch(...)`.
5. **`cpw/mods/fml/relauncher/CoreModManager.java:63`** — the second UltraMine hook: the built-in coremod list is
   `{ FMLCorePlugin, FMLForgePlugin, "org.ultramine.server.UltraminePlugin" }`.
   `UltraminePlugin` (`@SortingIndex(Integer.MAX_VALUE)`, i.e. last) registers the ASM transformer chain `UMTransformerCollection` and the mod container `UltramineServerModContainer` (modId `UltramineServer`). See [05-ultramine-packages.md](05-ultramine-packages.md) §1–2.
6. **`net/minecraft/server/MinecraftServer.java` `main()`** (`@SideOnly(Side.SERVER)`, ~line 1295) — vanilla bootstrap, then `new DedicatedServer(ConfigurationHandler.getWorldsDir())` (vanilla used `new File(".")` — UltraMine relocates world storage). The vanilla shutdown hook is commented out; shutdown ordering is reworked (see below).
7. **`net/minecraft/server/dedicated/DedicatedServer.startServer()`** — console-reader thread (JLine-aware via `UMBootstrap.isJLine()`), then configuration: **`server.properties` is not used** — everything comes from `ConfigurationHandler.getServerConfig()` (`settings/server.yml`) and `getWorldsConfig().global` (`settings/worlds.yml`): online mode, bind IP/port, player settings, mob spawn, generation options.
8. FML lifecycle proceeds as in stock Forge: `FMLCommonHandler.onServerStart` → `FMLServerHandler.beginServerLoading` (`Loader.loadMods()` / `preinitializeMods()`) → `finishServerLoading` (`initializeMods()`) → `handleServerAboutToStart` → `handleServerStarting` → world init → `serverStarted`. UltraMine's own subsystems start from `UltramineServerModContainer`'s lifecycle subscriptions at each of these stages (config load in preInit, event handlers in init, commands/scheduler at serverStarting, warps/data cache at serverStarted).

Note: `Start.java` in the source root is the stock Forge *client* dev launcher, unrelated to the server path.

## Main loop (`MinecraftServer.run()`, ~line 394)

Fully rewritten relative to vanilla:

- **Watchdog**: `WatchdogThread.doStart()` before the loop and `WatchdogThread.tick()` after every tick (skipped in single-player). `org/ultramine/server/internal/WatchdogThread` polls every 10 s; past `settings.watchdogThread.timeout` it dumps all thread stacks (server thread first) at FATAL and can force a restart (`watchdogThread.restart`).
- **Nanosecond TPS regulation** instead of vanilla's 50 ms drift loop: `TICK_TIME = 1_000_000_000 / 20`, catch-up time clamped to 20 ticks, exponential moving average `currentTPS`, plus `currentWait` / `peakWait` metrics (surfaced by `/uptime`, `/lagometer`, auto-debuginfo).
- **Spare-time utilization**: instead of `Thread.sleep` between ticks, `DedicatedServer` overrides `utilizeCPU(nanos)` → `UMHooks.utilizeCPU(nanos)`, which drains next-tick task queues and generates queued chunks (`ChunkGenerationQueue`) in the idle window.
- **Incremental saving**: vanilla's "save everything every 900 ticks" block is removed. Instead, every tick saves one player (`ServerConfigurationManager.saveOnePlayerData`, round-robin) and one chunk per world (`ChunkProviderServer.saveOneChunk`); `world.saveOtherData()` (map data, structures, etc.) runs every 2401 ticks. A full save still happens on demand (`/save-all`, backups, shutdown).
- **Async chunk IO drain**: `updateTimeLightAndEntities()` starts with `ChunkIOExecutor.tick()` — completed async chunk loads are applied on the main thread each tick.
- **Shutdown**: `handleServerStopping`/`expectServerStopped` reordered before the final tick; `finally` → `stopServer()` → `handleServerStopped()` → `System.exit(0)` (`DedicatedServer.systemExitNow`).

## Console, logging, RCON

- **Console**: JLine 2 (`jline:jline:2.13`) console with tab completion; completion requests are marshalled onto the server thread and back (`org/ultramine/server/internal/JLineSupport`, thread `"Server console handler"`). Terminal mode and charset via `-Dorg.ultramine.terminal[.charset]`.
- **Logging** (`src/main/resources/log4j2.xml`): async logging everywhere; console layout `UMConsoleLayout` (`[HH:mm:ss] [LEVL]`, translates `§` color codes to ANSI); file appender `logs/latest.log` rolling to `logs/yyyy-MM-dd-N.log.gz` with colors stripped (`UMStripColorsRewritePolicy`); `NETWORK_PACKETS` marker denied.
- **RCON / Query**: enabled/configured from `server.yml` (`listen.rcon.{enabled,port,password,whitelist}`, `listen.query`); RCON commands are executed on the server thread via `GlobalExecutors.nextTick().await(new RConCommandRequest(...))` (`net/minecraft/network/rcon/RConThreadClient.java`).
- **Login auth** (`net/minecraft/server/network/NetHandlerLoginServer.java`) runs on the cached IO pool instead of spawning a thread per login.

## Filesystem layout at runtime

`org/ultramine/server/ConfigurationHandler` creates and resolves (all overridable via `-Dorg.ultramine.dirs.{settings,storage,worlds,vanilla}`):

```
settings/           server.yml, worlds.yml, itemblocker.yml
storage/            server-level persistent data
worlds/             all world saves (per-world directories; DIM<x> only for legacy layout)
world -> worlds/world   symlink created for mod compatibility
logs/               latest.log + gzipped dailies
backups/            BackupManager zips (tools.autobackup)
```

Vanilla config files (`ops.json`, `whitelist.json`, `banned-*.json`, `eula.txt` via `net/minecraft/server/ServerEula`) live in the vanilla-configs dir.

## Server thread model

| Thread / pool | Source | Purpose |
|---|---|---|
| `Server thread` | `MinecraftServer.startServerThread()` | main tick loop (stored in UM field `serverThread`; `getServerThread()` used for "am I on the server thread" checks) |
| `UM IO writing #N` (1 thread) | `GlobalExecutors.writingIO()` | all file writes (region files, NBT, YAML, stats, zips) |
| `UM IO cached #N` (2..∞, 60 s idle) | `GlobalExecutors.cachedIO()` | latency-sensitive async work (login auth, world load phases, economy async) |
| next-tick queue | `GlobalExecutors.nextTick()` (`SyncServerExecutorImpl`) | tasks executed on the server thread at `ServerTickEvent.END`; also drained by `utilizeCPU` |
| `PlayerData loader #N` | `TwoStepsExecutor` | async player NBT/stats load, applied on server thread |
| chunk send pool (1 thread) | `ChunkSendManager` | chunk snapshot compression off-thread |
| `UM Scheduler thread` | `org/ultramine/scheduler/Scheduler` | crontab-style scheduled tasks |
| `Watchdog Thread` | `WatchdogThread` | stall detection |
| `Server console handler` | `JLineSupport` | console input |
| `OffHeapChunkAlloc cleaner` (Timer) | `UnsafeChunkAlloc` | delayed free-list cleanup for off-heap chunk slots |
| Chunk IO threads | Forge `ChunkIOExecutor` (patched) | async chunk load/generate stages |
