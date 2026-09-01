# Working on this repository

UltraMine is a Minecraft 1.7.10 server core: Minecraft, Forge, FML and UltraMine
compiled together as one merged source tree and reobfuscated MCP→notch on the way
out. It is not a mod and not a Forge plugin — patches are already in the jar, so
much of what a normal Forge server does at runtime does not happen here.

## Build and test

Everything lives under `ultramine/ultrasource`. A JDK 8 toolchain is required;
Gradle comes via the wrapper.

```bash
cd ultramine/ultrasource
./gradlew test                     # unit tests
./gradlew jar_server serverDist    # jars + ready-to-run distribution
```

Artifacts land in `build/libs/` and `build/distributions/`.

## The rule that matters most

Almost every modpack failure this core has had was the same shape:

> A vanilla **name** was kept while what sits behind it changed — a field's type,
> a method's body, a local variable table, an allocation site. The coremod's patch
> then misses, while the rest of its rewrite still lands, and the class fails to
> load.

It fails hard, not softly: Mixin fails the whole mixin when a `@Shadow` or an
injection point does not resolve, and the target class then never loads. One
retyped field has taken down a packet class, and with it the server.

So: **if a change to a vanilla class renames a field, retypes a field, removes a
local, moves an allocation, or replaces a call with a different call, assume some
coremod patches exactly that.** Keep vanilla's shape alongside the new one — the
pattern used in `S21PacketChunkData` and `AnvilChunkLoader` is a branch on
`ChunkStorageMode`, vanilla first, locals introduced where vanilla introduces them
— or do not make the change. Issue #8 tracks the known cases.

Never leave a tripwire in a public vanilla method. `MapGenStructureData.writeToNBT`
threw `IllegalStateException` on the assumption that only this core would call it;
a coremod called it and every world after the first went unsaved.

## Evidence standards

These are not style preferences. Every one of them exists because ignoring it
produced a wrong claim in this repository.

- **A grep for a class name proves nothing about a fix.** `EntityHorse` appearing
  in 18 files says the class exists. Verify against a marker unique to the fix, or
  record the item as unreviewed and leave it without a status.
- **An optimisation without a measurement is not a fix.** Anything claiming a
  speedup needs a before and after from the same harness.
- **A green gate is a claim.** Two gates here were found passing on things they did
  not test: the modpack job booted a daily build nobody downloads while the release
  pack failed, and the boot marker matched a mod's log line so a server counted as
  up while it was still starting. When a gate goes green, ask what it would have
  caught.
- **Say what is not known.** "Not verified" is a finished answer. A plausible
  reading presented as a conclusion is not.

## CI gates

`.github/workflows/build.yml`. On every push: unit tests, build, boot on Java 8,
boot on Java 25 (no `--add-opens` — the jar manifest carries them, and the gate
exists to prove it).

The modpack boot test is dispatch-only:

| `modpack_url` | What it runs |
|---|---|
| `gtnh-release` | The pinned GT New Horizons release pack — what the download page serves. This is the gate. |
| `gtnh-latest` | Newest daily build. A moving target; an early warning, not a gate. |
| any URL | That pack. |

Use `modpack_java=8` and `chunk_storage=vanilla` for GTNH. The job fails if the
server does not reach vanilla's real `Done (Ns)! For help` line, or if shutdown
produces `Exception stopping the server` — booting and saving are both part of
working.

## Branches and releases

Work lands on `dev`. `dev` → `master` by **squash merge only** — the repository
rejects merge commits. Releases are cut from `master` by dispatching
`release.yml` with a version.

Release notes live in `docs/release-notes/`. Every release publishes SHA-256 sums
and build provenance attestations.

## Conventions

- Repository artifacts — commits, PR titles and bodies, issues, code comments,
  documentation — are written in **English**.
- Comments explain *why*, especially where the code looks wrong without the
  history. Mark deliberate divergences from vanilla with `ultramine:`.
- No model identifiers in anything pushed to the repository.
- Do not merge your own pull requests. Opening one, green and described, is where
  the work stops.

## Where to look

`docs/` carries the architecture notes: `02` the build pipeline, `04` what this
core changed in vanilla and Forge, `06` the modernization work and what is still
open, `07` CI and releases.
