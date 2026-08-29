# Modifications to Vanilla / Forge / FML Sources

> Part of the codebase notes series. Index: [docs/README.md](README.md)
> Paths below are relative to `ultramine/ultrasource/src/main/java/`.

UltraMine does not patch at install time — its changes are **committed directly into the merged decompiled sources**. Many modified files carry `/* ===== ULTRAMINE START ===== */ ... /* ===== ULTRAMINE END ===== */` marker blocks (25 files); in total **54 of 1822 files** under `net/` + `cpw/` reference `org.ultramine` (~3%). The client is essentially untouched (1 of 373 files under `net/minecraft/client`, a trivial `ChunkHash` usage in `WorldClient`).

Rule of thumb used by the authors: no Java 8 language features inside `net.minecraft.*` classes — lambdas needed by patched code live in `org/ultramine/server/internal/LambdaHolder.java`.

## Where the changes are (files touched per area)

| Area | Files | Depth |
|---|---|---|
| `net/minecraft/world` (+chunk, gen, storage) | 12 | deepest — chunk pipeline rewritten |
| `net/minecraft/entity` | 11 | tracking, entity typing, player |
| `net/minecraft/server` | 7 | tick loop, config, player list |
| `net/minecraft/network` | 6 | packet loop, rcon, chunk packet |
| `net/minecraft/command` | 3 | command registry replaced |
| `net/minecraftforge/common/chunkio` | 2 | async chunk IO retargeted |
| `cpw/mods/fml` (relauncher, common) | 4 | launch hook, coremod list, event bus profiling |
| others (`nbt`, `item`, `block`, `tileentity`, `stats`, `client`) | ~8 | point changes |

## Chunk pipeline (most invasive)

- **`net/minecraft/world/gen/ChunkProviderServer.java`** — `LongHashMap` replaced by UM's int-keyed `ChunkMap` (`public ChunkMap chunkMap`), with `VanillaChunkHashMap`/`VanillaChunkHashSet` shim views kept for mods that reflect into the old fields. Adds the async load API (`loadAsync`, `loadAsyncRadius`, `loadAsyncRadiusThenRun`, `loadAsyncWithRadius` + `IChunkLoadCallback`), `ChunkGenerationQueue` fallback, `ChunkGC`, `isWorldUnloaded`/`preventSaving`/`isGenerating` flags, incremental `saveOneChunk` (queue cap 64, full-save interval 10 min), and a `-Dultramine.debug.chunksyncload` warning for synchronous loads. The vanilla path survives as `originalLoadChunk`.
- **`net/minecraftforge/common/chunkio/ChunkIOExecutor.java` / `ChunkIOProvider.java`** — Forge's async chunk loader retargeted from `Runnable` callbacks to UM's `IChunkLoadCallback`; results land in `provider.chunkMap` keyed by `ChunkHash`; every stage aborts if the world is unloading.
- **`net/minecraft/world/chunk/storage/ExtendedBlockStorage.java`** — block/metadata/light arrays moved **off the Java heap**: `@InjectService ChunkAllocService alloc` + `volatile MemSlot slot` (see [05-ultramine-packages.md](05-ultramine-packages.md) §7). `net/minecraft/nbt/EbsSaveFakeNbt.java` is a new NBT type that serializes a `MemSlot` directly without copying to heap.
- **`net/minecraft/world/chunk/Chunk.java`** — per-chunk pending block updates (pooled sets), `fastTileEntityMap` (Koloboke short-keyed), `ChunkBindState` + chunk dependency list (`IChunkDependency`), load/unbind timestamps, and cached per-`EntityType` entity counters feeding the load balancer and mob spawn engine.
- **Storage/IO classes** (`RegionFileCache`, `AnvilChunkLoader`, `AnvilSaveHandler`, `SaveHandler`, `MapStorage`, `StatisticsFile`, `PlayerProfileCache`) — file writes rerouted to `GlobalExecutors.writingIO()` / `AsyncIOUtils`.

## World tick

- **`net/minecraft/world/World.java`** — `activeChunkSet` replaced by a Koloboke `IntByteMap activeChunks` (chunk key → priority byte) with a vanilla-compatible view; `ServerLoadBalancer.canUpdateEntity()` gate in `updateEntities`; every entity/tile-entity/block/packet update wrapped in `WorldEventProxy` push/pop (attribution of world changes to their initiator); per-chunk timing via `ChunkProfiler`; "Possible lag source" warnings now gated by `settings.other.spamLagConsole` (this fork's addition); entity spawn triggers `loadAsync` of the target chunk.
- **`net/minecraft/world/WorldServer.java`** — per-world `WorldConfig` (`applyConfig()`: difficulty, gamerules, spawn flags, `WorldBorder`, event proxy); global `pendingTickListEntries` replaced by **per-chunk** pending updates (`updatePendingOf(Chunk)` + `PendingBlockUpdate`); vanilla `SpawnerAnimals` replaced by `MobSpawnManager` when `spawnEngine: NEW`; per-world view distance; `checkSessionLock()` disabled server-side; `saveOtherData()`.

## Player chunk sending & entity tracking

- **`net/minecraft/server/management/PlayerManager.java`** — reduced to a compatibility shim; real work happens in `org.ultramine.server.chunk.ChunkSendManager` (field `EntityPlayerMP.getChunkMgr()`): adaptive rate-limited, off-thread compressed chunk streaming sorted by view direction. (This fork made `PlayerInstance.playersWatchingChunk` public for ChickenChunks.)
- **`net/minecraft/network/play/server/S21PacketChunkData.java`** — chunk packets built from immutable `ChunkSnapshot`s via `UMHooks.extractAndDeflateChunkPacketData` (reads straight from off-heap `MemSlot`s, reusable `Deflater(7)`, thread-safe).
- **`net/minecraft/entity/EntityTracker.java` / `EntityTrackerEntry.java`** — vanish support (`hidePlayer`/`showPlayer`, `MinecraftPermissions.SEE_INVISIBLE_PLAYERS`).
- **`net/minecraft/entity/Entity.java` + subclasses** — `EntityType computeEntityType()` classification (item/xp/monster/animal/water/ambient) feeding per-chunk counters; "object owner" `GameProfile` persisted on entities and tile entities (`UMHooks.read/writeObjectOwner`).

## Network, commands, permissions

- **`net/minecraft/network/NetworkManager.java`** — packet handling wrapped in the world event proxy (world changes get attributed to the sending player), per-packet profiler sections, >20 ms slow-packet warning (gated by `spamLagConsole` in this fork).
- **`net/minecraft/network/NetHandlerPlayServer.java`** — `@InjectService Permissions`; chat-spam threshold bypass (`ALLOW_SPAM`); new Forge events `PlayerSwingItemEvent`, `PlayerSneakingEvent`; join-message hiding (`HIDE_JOIN_MESSAGE`); chunk manager lifecycle.
- **`net/minecraft/command/CommandHandler.java`** — vanilla `commandMap`/`commandSet` are now live views over `org.ultramine.commands.CommandRegistry`; mods registering commands the vanilla way land in the UM registry transparently.
- **`cpw/mods/fml/common/event/FMLServerStartingEvent.java`** — mod-facing API extended: `registerCommand(IExtendedCommand)`, `registerCommands(Class)` (annotation-based), `registerArgumentHandler(s)`.
- **`net/minecraft/server/network/NetHandlerLoginServer.java`** — Mojang auth on the cached IO pool.
- **`net/minecraft/network/rcon/*`** — config from `server.yml`; command execution marshalled to the server thread.

## FML internals

- **`cpw/mods/fml/relauncher/ServerLaunchWrapper.java`** — `UMBootstrap.handleFirstLine()` (terminal/log setup) before launchwrapper.
- **`cpw/mods/fml/relauncher/CoreModManager.java:63`** — `UltraminePlugin` added to the built-in coremod list.
- **`cpw/mods/fml/common/LoadController.java`** — special-cases `UltramineServerModContainer` in the mod object list.
- **`cpw/mods/fml/common/eventhandler/EventBus.java` / `ASMEventHandler.java`** — `postWithProfile(Profiler, Event)` + `getOwner()`: event dispatch time is attributed per-mod in the profiler output.

## Collections & misc

Koloboke primitive maps used inside vanilla classes (`LongHashMap`, `IntHashMap`, `World`, `WorldServer`, `ChunkProviderServer`, `Chunk`, `AnvilChunkLoader`, `WorldClient`, `NBTTagCompound` — the last behind `-Dorg.ultramine.core.nbt.useKolobokeMap`); Trove in 6 files. `net/minecraft/server/ServerEula.java` implements the EULA check.

## Side handling

The whole tree is dual-side; the split happens at build time (`SideSplitTask` strips `@SideOnly` members — 1754 `@SideOnly(CLIENT)` vs 61 `@SideOnly(SERVER)` annotations across `net/`+`cpw/`). UM server-only members inside shared classes are annotated `@SideOnly(Side.SERVER)` (e.g. `WorldServer.border`, `ChunkProviderServer.chunkGC`) and/or guarded by runtime `FMLCommonHandler.instance().getSide().isServer()` checks. In `org/ultramine` itself: 72 `@SideOnly(SERVER)` vs 3 `@SideOnly(CLIENT)`.

## Compatibility consequences

Because these rewrites replace the exact internals that performance/compat coremods also patch, the README's known-incompatible list (FastCraft, ServerTools, ForgeEssentials, DragonAPI, zzzzzcustomconfigs, NEID) maps directly onto the areas above (chunk storage, world tick, IDs, network). Any mod reflecting into `ChunkProviderServer.loadedChunkHashMap`, `World.activeChunkSet`, `CommandHandler.commandMap`, or `CraftingManager.recipes` is served by the compatibility shim views — mods bypassing those shims (raw field access by obfuscated name, ASM patching the same methods) will break.
