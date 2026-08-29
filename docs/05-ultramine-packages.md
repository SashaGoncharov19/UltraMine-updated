# `org.ultramine` Package & Subsystem Map

> Part of the codebase notes series. Index: [docs/README.md](README.md)
> Paths relative to `ultramine/ultrasource/src/main/java/`. 217 files, ~22k LOC, 33 packages.

`org.ultramine` is the *added* code; it is called from the patched vanilla/Forge classes described in [04-vanilla-forge-modifications.md](04-vanilla-forge-modifications.md). Top-level split:

- `org/ultramine/core/…` — **public API** (services/DI, permissions, economy, undo tokens). Stable surface intended for external providers ("plugins" in the UltraMine sense are Forge mods using these APIs).
- `org/ultramine/server/…` — the server core implementation.
- `org/ultramine/commands/…` — command framework.
- `org/ultramine/scheduler/…` — cron scheduler.

## 1. Bootstrap & mod container

| Class | Role |
|---|---|
| `server/UltraminePlugin` | `IFMLLoadingPlugin` coremod, `@SortingIndex(MAX_VALUE)` (sorts last). Registers the ASM transformer collection and the mod container; adds classloader exclusions (jline, jansi, snakeyaml, disruptor, dbcp2, own bootstrap/asm packages). |
| `server/UltramineServerModContainer` | The `DummyModContainer` for modId `UltramineServer` (version stamped at build). Subscribes to FML lifecycle: **preInit** — register `ChunkAllocService`(off-heap)/`AntiXRayService`(no-op), load `server.yml`+`worlds.yml`, init DB pools, register economy + `OpBasedPermissions`; **init** — `UMEventHandler` on both buses; **serverAboutToStart** — `ChunkGenerationQueue`, `MultiWorld`, `ItemBlocker`; **serverStarting** — `PlayerCoreData` extension, built-in commands, scheduler start; **serverStarted** — player-data cache, warps + per-warp commands, `RecipeCache`; **serverStopped** — teardown. |
| `server/bootstrap/UMBootstrap` | Pre-launchwrapper terminal & async-log4j setup (called from `ServerLaunchWrapper`). |
| `server/bootstrap/log4j/*` | Log4j2 plugins: `UMConsoleLayout` (`§`→ANSI), `UMStripColorsRewritePolicy` (clean file logs). |

## 2. ASM layer — `server/asm`

- `UMTBatchTransformer` — single `IClassTransformer` running a chain of `IUMClassTransformer`s over one shared `ClassNode` per class (global + per-name); result levels `NOT_MODIFIED`/`MODIFIED`/`MODIFIED_STACK`. Also repairs stack frames of any class compiled above V1_6 (`-Dorg.ultramine.core.asm.repairJavaClassFrames`, default on) to tolerate other mods' bytecode.
- Transformers (`asm/transformers/`):
  - `ServiceInjectionTransformer` — **the DI backbone**: rewrites every `static @InjectService` field to `field = ServiceBytecodeAdapter.provideService(T.class)` at `<clinit>` head and makes it final.
  - `PrintStackTraceTransformer` — redirects all `*.printStackTrace()` to `UMHooks.printStackTrace` (adds world/entity context to error logs).
  - `TrigMathTransformer` — `Math.atan/atan2` → fast `TrigMath` approximations.
  - `BlockLeavesBaseFixer` — conflict fix when another mod patches `BlockLeavesBase` the same way.
- `ComputeFramesClassWriter` — frame computation that resolves class hierarchy through `LaunchClassLoader`/FML remappers without loading classes.

## 3. Service locator / DI — `core/service` + `server/service`

- API: `@Service` (marks service interfaces), `@InjectService` (static field injection via ASM), `ServiceManager.register(Class, provider|loader, priority)` → returns `Undoable`; `provide(Class)`.
- Implementation: `UMServiceManager` (concurrent, priority-ordered providers, `singleProvider` enforcement); `ServiceDelegateGenerator` synthesizes per-interface delegate classes **via `sun.misc.Unsafe.defineAnonymousClass` + ASM** (lazy "not resolved" provider until a real one registers). Covered by the only test in the repo (`src/test/java/.../ServiceDelegateGeneratorTest.groovy`, Spock).
- Services registered in-tree: `ServiceManager`, `Permissions`, `Economy`, `EconomyRegistry`, `DefaultCurrencyService`, `DefaultHoldingsProvider`, `ChunkAllocService`, `AntiXRayService`.
- `core/util/Undoable*` — the pervasive "unregister token" idiom (`Undoable`, `UndoableValue`, `UndoableAction`, `UndoableOnce`). Note: `UndoableOnce.of()/empty()` are non-static — latent bug, effectively unusable.

## 4. Permissions — `core/permissions`

- `Permissions` service interface: `has(world, player, permission)`, `getMeta(world, player, key)` + many convenience overloads; `useVanillaCommandPermissions()`.
- `MinecraftPermissions` — constants (`minecraft.op`, `minecraft.allow_spam`, `ability.admin.seeinvisibleplayers`, …).
- Default provider `server/internal/OpBasedPermissions` (priority 0): op-based fallback so the server works without a permissions provider; a real one (e.g. the separate UltraPermissions mod) registers at higher priority. Permission *meta* drives chat prefix/suffix/color in `UMEventHandler`.

## 5. Economy — `core/economy` + `server/economy`

Full multi-currency economy **API** with a built-in default implementation storing balances in player NBT (`PlayerCoreData`, tag `acc`, scaled-long): `Currency`, `Account`/`PlayerAccount`, `Holdings` (thread-safe balance ops incl. `computeBalance(DoubleUnaryOperator)` as the documented read-modify-write), `AsyncHoldings` (`CompletableFuture` mirror), services `Economy`/`EconomyRegistry`/`DefaultCurrencyService`/`DefaultHoldingsProvider`, typed exceptions. Implementation: `UMEconomy`, `UMEconomyRegistry`, `UMIntegratedHoldingsProvider` (+ lock-free `MemoryHoldings` via `AtomicLongFieldUpdater`). Currencies are declared in `server.yml` (`tools.economy`).

## 6. Command framework — `commands/`

- `CommandRegistry` — the real command store; vanilla `CommandHandler.commandMap/commandSet` are live views onto it (`MapWrapper`/`SetWrapper`). Registers commands under `group:name`, name, aliases, **and RU transliterations** (`TranslitTable`). Vanilla/mod `ICommand`s are wrapped (`VanillaCommandWrapper`, permission `command.<group>.<name>`; group = registering mod id).
- Declarative commands: static methods annotated `@Command(name, group, aliases, permissions, syntax[], isUsableFromServer)` + `@Action` sub-handlers; syntax DSL parsed by `ArgumentsPatternParser`, e.g. `"<player% dst>"`, `"[list|add|remove] <int%radius>"`, trailing `...` = variadic. Completion/validation handlers: `DefaultCompleters` (`player`, `item`, `block`, `entity`, `list`, `world`, `warp`; validators `int`, `world`), extensible per-mod via `FMLServerStartingEvent.registerArgumentHandler`.
- `CommandContext` — typed argument access, permission checks, thread-safe messaging (auto-hops to server thread), async command support (`finishAfter(CompletableFuture)`), admin notification.
- `OfflinePlayer` — load/modify/save offline players via `FakePlayer`.
- Built-ins (`commands/basic/`): `VanillaCommands` (reimplemented help/tp/msg/time/weather/difficulty…), `TechCommands` (~735 LOC: `/uptime`, `/lagometer`, `/memstat` (incl. off-heap), `/debuginfo`, `/multiworld` (list/load/unload/hold/import/delete/goto — async), `/countentity`, `/clearentity`, `/backup make|apply`, `/restart`, `/chunkgc`, `/chunkdebug`, `/reloadcfg`, `/recipecache`, `/javagc`, `/startlags`), `GenWorldCommand` (background world pre-generation, spiral iterator, tick-budgeted), `FastWarpCommand` (per-warp commands like `/spawn`).

## 7. Configuration — `server/ConfigurationHandler` + config classes

- `settings/server.yml` ↔ `UltramineServerConfig`: `listen.{minecraft,query,rcon}`, `settings.{authorization,player,other,spawnLocations,teleportation,messages,watchdogThread,inSQLServerStorage,security}`, `tools.{autobroadcast,autoDebugInfo,autobackup,economy}`, `databases` (named JDBC pools), plus `vanilla.unresolved` — a catch-all map absorbing legacy `server.properties` keys. This fork added `settings.other.spamLagConsole`.
- `settings/worlds.yml` ↔ `WorldsConfig`: a `global` block + per-world `WorldConfig{dimension, name, importFrom, generation, mobSpawn (spawnEngine OLD|NEW|NONE + newEngineSettings), settings (difficulty/pvp/time/weather/useIsolatedPlayerData/respawnOnWarp/reconnectOnWarp/fastLeafDecay), borders[], chunkLoading{viewDistance,chunkActivateRadius,chunkCacheSize,enableChunkLoaders,maxSendRate}, loadBalancer.limits (per-entity-type PerChunkEntityLimits), portals}`. Default template: `src/main/resources/org/ultramine/defaults/defaultworlds.yml` (YAML anchors, `{seed}` substitution).
- YAML via SnakeYAML (`YamlConfigProvider`, public-field introspection, skip-missing tolerant); saves async.

## 8. Multiworld — `server/world`

- `MultiWorld` — dim↔descriptor registry; on server start **unregisters vanilla dims -1/0/1 and re-creates all worlds from `worlds.yml`**; registers void provider type `-10` (`WorldProviderEmpty`, translated to 0 for clients); syncs dimensions to clients via Forge `DimensionRegisterMessage`; temp-world support (`temp_<rand>_<dim>`); tracks dims with isolated player data.
- `WorldDescriptor` — per-dimension state machine `LOADED/AVAILABLE/HELD/UNREGISTERED` with sync and `CompletableFuture` async transitions (load/unload/hold/delete/wipe; async phases on cached IO, finish on next tick); moves players out on unload.
- `world/load/*` — pluggable world loaders: split per-name dirs (default), legacy `DIM<n>` layout, and **import** (from directory or zip: `world/imprt/*` chunk loaders read region files straight out of archives).
- `server/WorldBorder` — multiple square/round borders per world, enforced per player tick.

## 9. Chunk subsystem — `server/chunk`

- `ChunkSendManager` — per-player adaptive chunk streaming: rate `MIN_RATE..maxSendRate` adjusted by client ack pace, direction-sorted queue, snapshot (`ChunkSnapshot`) compressed on a dedicated single-thread pool, sent with Netty write-future callbacks; `AntiXRayService` hook point (default no-op `EmptyImpl`).
- `ChunkGC` — replaces vanilla unload logic: bound-state-aware (`ChunkBindState`: `NONE/PLAYER/LEAK/FORGE/ETERNAL`), cache-size-driven, ≤1024 unloads per pass, 10-min leak threshold.
- `ChunkGenerationQueue` — coalesced background generation, drained in spare tick time (`UMHooks.utilizeCPU`).
- `ChunkProfiler` — per-chunk tick-cost accounting (`/chunkdebug`).
- `chunk/alloc/*` — **off-heap block storage**: `ChunkAllocService` → `UnsafeChunkAlloc` (`Unsafe.allocateMemory`, 12 KB slots, default limit 6 GB via `-Dorg.ultramine.chunk.alloc.offheap.memlimit`, delayed free-list + cleaner timer; two layouts selectable with `-Dorg.ultramine.chunk.alloc.layout=7|8`). `MemSlot` is the typed accessor used by `ExtendedBlockStorage` and the chunk packet packer.
- `ChunkHash` — packed int/long chunk & block keys used across the core; `ChunkMap` — Koloboke-based chunk container.

## 10. Mob spawn engine — `server/mobspawn`

Per-world `spawnEngine: OLD|NEW|NONE`. `MobSpawnManager` + abstract `MobSpawner` (works over the active-chunk priority map, spreads work across `performInterval` ticks, honors `minPlayerDistance`, per-area `localLimit`/`localCheckRadius`, Forge spawn events) with concrete `MobSpawnerMonsters` (day/night limits, sky/underground strategies), `Animals`, `Water`, `Ambient`. Config in `worlds.yml` `mobSpawn.newEngineSettings`.

## 11. Anti-lag & diagnostics

- `server/ServerLoadBalancer` — per-chunk, per-entity-type update throttling and hard caps (`loadBalancer.limits`: `lowerLimit` probabilistic skipping, `higherLimit` entity removal, dead-chunk `updateInactive()`).
- `server/event/WorldEventProxy` + `WorldUpdateObject(Type)` — "what is being updated right now" context stack (entity/TE/block/player/weather) with initiator `GameProfile`; enriches `UMHooks.printStackTrace` error reports and feeds block-change attribution.
- `server/internal/WatchdogThread` — stall detector (full thread dumps, optional auto-restart).
- `server/RecipeCache` — caches `CraftingManager` lookups (12k-entry LRU-ish, remap-aware, `settings.other.recipeCacheEnabled`).
- `server/internal/UMHooks` — static hook surface for patched vanilla code: `utilizeCPU` (spare-time task/chunk-gen draining), chunk packet packing from `MemSlot`s, `onChunkPopulated`/`forceProcessPendingAndFallingBlocks` (post-generation cleanup), object-owner NBT, per-player chat re-translation, contextual `printStackTrace`.

## 12. Player data & persistence — `server/data`

- `ServerDataLoader` — central player-data cache (by UUID and lowercase name) + warps/fast-warps; async login pipeline (`TwoStepsExecutor "PlayerData loader"`: NBT+stats off-thread, apply on server thread); first-spawn & `respawn/reconnectOnWarp` handling; **per-world isolated player data** (inventory/NBT swapped per dimension for configured worlds); `registerPlayerDataExt` SPI for mods.
- `PlayerData` + `PlayerDataExtension` — extensible per-player storage; built-in `PlayerCoreData` (`"core"` tag): first/last login, **homes**, economy holdings, mute state, hidden flag, active teleport.
- Storage providers (`IDataProvider`): `NBTFileDataProvider` (default; `<world>/playerdata/ultramine/`, warps in YAML) and `JDBCDataProvider` (MySQL via commons-dbcp2 pools from `server.yml` `databases:`; tables `player_ids`, `player_gamedata` per-dim blobs, `player_data`, `warps`, `warps_fast`; writes on the writing-IO thread). Toggle: `settings.inSQLServerStorage.enabled`.
- `server/util/WarpLocation` — warp/home model (dim+pos+rotation+randomRadius).

## 13. Teleport / backup / restart — `server/`

- `Teleporter` — instant and delayed teleports with cooldown/delay from `settings.teleportation`, admin bypass abilities, cancel-on-death; ticked from `UMEventHandler`.
- `BackupManager` — scheduled zips (`tools.autobackup`: interval, maxBackups, maxDirSize, world list): coordinates a consistent snapshot (save-all, `preventSaving`, wait for IO, clear region cache), zips off-thread, prunes old backups; **restore** (`/backup apply`) with path validation, staged unpack, world swap or mount-as-temp-world.
- `Restarter` — countdown restart with localized warnings, kick, shutdown (used by `/restart` and the watchdog).

## 14. Scheduler — `scheduler/`

Crontab-compatible scheduler (own `SchedulingPattern` parser: 5 fields, steps, ranges, name aliases, `L`, `|`-alternatives, timezones; derived from cron4j). Tasks run sync (queued to tick end) or async (cached IO pool). Daemon thread `"UM Scheduler thread"` aligned to minute boundaries. Driven from config-defined tools (autobackup/autobroadcast use their own tick counters; the scheduler is also exposed via `server.getScheduler()` for mods).

## 15. Event & tool extras

- `server/event/*` — added Forge events: `SetBlockEvent`, `EntitySetFireEvent`, `EntityPotionApplyEffectEvent`, `HangingBreakEvent`, `InventoryCloseEvent`, `PlayerDeathEvent` (mutable message + keepInventory), `PlayerSwingItemEvent`, `PlayerSneakingEvent`, `PreDimChangeEvent`, `ForgeModIdMappingEvent`.
- `server/tools/ItemBlocker` — `settings/itemblocker.yml` (global + per-world lists; flags `useItem/rmItem/useBlock/rmBlock`); enforced on interact/pickup events and by sweeping open containers each tick.
- `server/internal/UMEventHandler` — the big Forge/FML event subscriber: chat formatting from permission meta (prefix/color), mute enforcement, auto-broadcast, auto-debuginfo (TPS/load lines), world-border push-back, teleport/pre-gen/profiler/backup ticking, keep-inventory ability, login/death/clone handling.

## 16. Utilities — `server/util`

`GlobalExecutors` (writing IO / cached IO / next-tick sync executor), `TwoStepsExecutor`, `AsyncIOUtils`, `ZipUtil`, `YamlConfigProvider`, `ConfigUtil` (reflective deep-clone for world configs), `BasicTypeParser`/`BasicTypeFormatter` (durations like `10d7h5m3s`, colored messages), `TranslitTable` (EN↔RU), `TrigMath`, `UnsafeUtil`, chunk-order comparators, id+meta-keyed `ItemStackHashMap/Set` (remap-aware via `UMInternalRegistry`), vanilla-compat collection shims (`VanillaChunkHashMap`, `VanillaChunkCoordIntPairSet`, `MapWrapper`, `SetWrapper`, `ListAsLinkedList`), `WeakObjectPool`, `SpiralCoordIterator`.

## 17. JVM-sensitive spots (matters for any Java upgrade)

1. `Unsafe.defineAnonymousClass` — `server/service/ServiceDelegateGenerator` (**removed in JDK 15+**; hardest blocker).
2. Raw `Unsafe` memory ops — `chunk/alloc/unsafe/*` (off-heap chunk storage).
3. `Unsafe.theUnsafe` reflection — `server/util/UnsafeUtil`.
4. `setAccessible(true)` deep reflection — `ConfigUtil.deepClone`, various.
5. ASM 5.0.3 — cannot read class files newer than Java 8.
6. Log4j2 2.0-beta9 plugin API (`UMConsoleLayout`, rewrite policy) — beta-era APIs differ from modern log4j2 (and predate the Log4Shell fixes; the vulnerable JNDI lookup appeared in 2.x releases *after* beta9, but staying on a beta is its own problem).

## 18. Known minor issues spotted while mapping

- `UndoableOnce.of()/empty()` not static (unusable as designed).
- `CommandRegistry` map-view `remove(String)` is a stub (`// TODO remove ?`) — `removeFastWarp` never unregisters the command name.
- Class name typo in buildSrc: `SpeicialClassTransformTask`.
- Comments/javadoc partially in Russian (`ChunkBindState`, `GlobalExecutors`, `BackupManager`, `IDataProvider`).
