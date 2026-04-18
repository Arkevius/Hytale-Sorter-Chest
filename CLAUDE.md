# CLAUDE.md

Hytale Update 4 server plugin + asset pack. Sort Chest that redistributes items to nearby chests on lid close.

## Build & install

```bash
./gradlew build            # produces build/libs/sorting_chest-<version>.jar
./gradlew installLocal     # builds and copies the jar into <Hytale UserData>/mods/
```

`installLocal` auto-detects the UserData directory (`$HYTALE_USERDATA`, else per-OS defaults). On macOS: `~/Library/Application Support/Hytale/UserData`. There is no sibling asset-pack directory — the single jar is the entire artifact.

There is no unit-test suite. Verification is end-to-end against a running Hytale server.

## Hytale plugin API

Compiles against the official Maven artifact `com.hypixel.hytale:Server:$hytale_build` from `https://maven.hytale.com/release`. The `hytale_build` value in `gradle.properties` must match a published server build. **Do not invent APIs** — if a class/method under `com.hypixel.hytale.*` is unknown, discover it via `javap`:

```bash
JAR=$(find ~/.gradle/caches/modules-2/files-2.1/com.hypixel.hytale -name 'Server-*.jar' 2>/dev/null | head -1)
javap -cp "$JAR" -public <fully.qualified.ClassName>
```

## Non-obvious behaviors discovered the hard way

- **Packaging**: a plugin that also ships assets belongs in ONE jar. Put `manifest.json` at the jar root with `Main` (FQCN of the plugin class) AND `IncludesAssetPack: true`, and put `Common/` / `Server/` at the jar root. Hytale's `PluginManager` loads the code and `AssetModule` registers the same jar as a pack. Do NOT also ship the assets as a sibling `<UserData>/mods/<name>/` directory — the same manifest will register twice and Hytale logs a duplicate-pack warning. One file, one registration.
- **Manifest schema**: the canonical `PluginManifest` fields are `Group`, `Name`, `Version`, `Description`, `Authors` (array of `{Name, Email, Url}`), `Website`, `Main`, `ServerVersion`, `Dependencies`, `OptionalDependencies`, `LoadBefore`, `DisabledByDefault`, `IncludesAssetPack`, `SubPlugins`. Other fields (e.g. `Id`, `Author` singular, `ApiVersion`) are silently ignored by the runtime but fail strict validation on CurseForge and similar gates.
- **Plugin entry**: extend `com.hypixel.hytale.server.core.plugin.JavaPlugin`, take `JavaPluginInit` in the constructor, override `protected void setup()` for ECS schema registration (components + systems) and `protected void start()` for runtime logic (event listeners, anything that depends on other plugins being enabled). `setup()` fires before the wider plugin graph is up — that's why event registration belongs in `start()` — but ECS component/system registration MUST happen in `setup()` so chunk deserialization can find codecs before worlds load.
- **ECS dispatch is idiomatically via a system, not polling.** Register a `DelayedSystem<ChunkStore>` (or the non-delayed `TickingSystem` / `EntityTickingSystem` / `ArchetypeTickingSystem`) in `setup()` via `getChunkStoreRegistry().registerSystem(...)`. The engine drives ticks per-store with delta-time; no `SCHEDULED_EXECUTOR`, no manual thread hop. `DelayedSystem(float intervalSec)` gates at your desired cadence (we use 2.0s). `SortingChestSystem` is our canonical example.
- **Custom components on existing entities**: declare the component in the block's JSON under `BlockEntity.Components`; the engine attaches it on placement and chunk load. For runtime attachment to entities we don't own (e.g. vanilla chests), use `CommandBuffer` from inside a system's tick. `addComponent` has two forms: the 2-arg `addComponent(ref, type)` calls the codec's default supplier to instantiate a fresh component (right for marker-style components with a private no-arg constructor — see `SortingChestSystem` migrating legacy chests), while the 3-arg `addComponent(ref, type, value)` attaches a component instance you've already built (right for components carrying state). `putComponent` is add-or-replace; `ensureComponent` is no-op if already attached; `removeComponent` / `tryRemoveComponent` delete.
- **Cross-build API compatibility via reflective shim**: Hytale's release and pre-release branches diverge on API shape (e.g. `SpatialData.getVector(int)` returns `com.hypixel.hytale.math.vector.Vector3d` on release but `org.joml.Vector3d` on pre-release — confirmed by `javap` on both jars). Rather than maintain dual builds, we use the pattern in `SpatialPositions.java`: (1) define a neutral internal type (`Pos`) with zero Hytale dependencies, (2) resolve the method via `MethodHandles.publicLookup().findVirtual(...)` at class-load time, probing each candidate return type via `Class.forName(...)`, (3) convert the result to the neutral type at the call site. JOML uses public fields (`findGetter`) while Hytale's types used getter methods (`findVirtual`) — handle both when reading primitive coordinates. Extend this pattern only where there's a confirmed API break; for everything else, keep direct compile-time references and rely on the disabled-mode catch-all below.
- **Disabled-mode safety net**: wrap the top-level tick body in a catch-all. For the one-shot guard, collapse the "is disabled" flag and the disable reason into a single `AtomicReference<String>` where `null` means enabled and any non-null string means disabled-with-that-reason; flip it via `compareAndSet(null, reason)` so flag-and-reason publish atomically in one step. Do NOT use a separate `AtomicBoolean flag` + `volatile String reason` pair — that shape has a two-writer race where concurrent `markDisabled` calls can attribute one thread's reason to the other thread's CAS win (the race the `AtomicReference` collapse specifically fixes). And do NOT use plain `volatile boolean` + check-then-set either — that's an even more basic TOCTOU. On a successful CAS, broadcast via `Universe.get().sendMessage(Message.raw(...))` and include the disable reason so players see WHY. Register an `AddPlayerToWorldEvent` listener via `getEventRegistry().registerGlobal(...)` that sends the same warning via `playerRef.sendMessage(...)` to late-joiners — extract the `PlayerRef` with `event.getHolder().getComponent(PlayerRef.getComponentType())`. `PlayerRef.sendMessage(Message)` works on both release and pre-release; avoid `Player.sendMessage(...)` — pre-release dropped that signature.
- **ECS events do NOT dispatch through the plugin `EventRegistry`.** Events in `com.hypixel.hytale.server.core.event.events.ecs.*` (e.g. `PlaceBlockEvent`, `BreakBlockEvent`) fire on per-store buses — not yet solved for plugin code. `events.player.*` events (e.g. `PlayerChatEvent`, `PlayerInteractEvent`) work fine via `getEventRegistry().registerGlobal(Class, Consumer)`.
- **Thread guarantees inside a system tick**: the engine calls `tick(...)` / `delayedTick(...)` with the store already locked for us — direct ECS reads/writes and direct container mutations are safe without `world.execute(...)` wrapping. Only wrap when dispatching INTO the world thread from code that runs outside the ECS tick (e.g. a player-event listener). HOWEVER: do not assume the engine dispatches us on the world thread. Any system shared state that might be touched by concurrent per-store ticks needs thread-safe types — see `worldByStore` in `SortingChestSystem` (`Collections.synchronizedMap(new WeakHashMap<>())` accessed via `computeIfAbsent`, which holds the map lock across the compute step and per the `Map` contract does NOT cache null returns — giving us positive-only caching atomically without the TOCTOU of a manual `get`-then-`put`).
- **`World.getChunkIfLoaded` / `getChunkIfInMemory` / `getChunkIfNonTicking` are deadlock-prone off-thread.** `javap -c` on each shows the same shape: check `isInThread()`; if off-thread, dispatch via `CompletableFuture.supplyAsync(lambda, world).join()`. The lambda runs on the world thread and acquires the store lock. If the caller is off-thread AND holds the store lock (e.g. inside a `store.forEachChunk` callback), the `join()` deadlocks waiting for a world-thread slot that can't make progress. Rule: before calling any of these from inside `forEachChunk`, gate on `world.isInThread() && world.isStarted()` and degrade gracefully (skip + log) if false. Our migration path in `SortingChestSystem` does this.
- **Engine modules vs plugins at `setup()` time**: the startup warning "setup() runs too early" applies to OTHER PLUGINS — `getEventRegistry().registerGlobal(...)` handlers, cross-plugin dependencies. Hytale-internal engine modules (`BlockModule`, `ItemModule`, `AssetModule`, etc.) are guaranteed available by `setup()`; that's precisely when you're supposed to query them for canonical `ComponentType`s and `ResourceType`s. Cache those once in the system/plugin constructor during `setup()` rather than re-resolving per tick — see `SortingChestSystem`'s `itemContainerType` + `spatialType` fields initialized from `BlockModule.get()` in its constructor.
- **Chunk store unwrap**: `world.getChunkStore()` returns a wrapper; `.getStore()` drills to the actual `Store<ChunkStore>` where `forEachChunk` lives. Same pattern for `EntityStore`.
- **State-variant block ids**: any block declared with `State.Definitions` in its JSON gets exploded into one `BlockType` per state, with generated ids of the form `*<BaseId>_State_Definitions_<StateName>`. `BlockType.getId()` at runtime returns a variant id, never the bare id. Match on the prefix.
- **Worlds**: `Universe.get().getWorlds()` contains both `"default"` (empty placeholder) and the actual gameplay world (e.g. `"flat_world"`). Iterate all of them; skip any with `getEntityCountFor(...)` == 0.
- **`ComponentType` implements `Query<ECS_TYPE>`** — pass it directly to `Store.forEachChunk(Query, BiConsumer)`. Casting isn't needed but type inference sometimes fails at the call site; assign to a local first if the overload resolver complains.

## Repository etiquette

- Default branch: `main`. **Never commit or push directly to `main`** — open a pull request.
- Develop on short-lived feature branches: `feature/<topic>` for new features, `fix/<topic>` for bug fixes. Branch off `main`, merge back via PR.
- Squash or rebase before merging so `main`'s history stays linear and each PR is one logical commit.
- One logical change per commit with a clear subject line. Prefer small diffs — easier to revert if a guess is wrong.
- `javap` **before** writing code that references an unfamiliar API, not after a compile error.

## Testing workflow (manual)

1. `./gradlew build installLocal`
2. Restart Hytale and join the `Chest-Mod-Test` save
3. Tail the server log:
   ```bash
   HYTALE_LOG=$(ls -t "$HOME/Library/Application Support/Hytale/UserData/Saves/Chest-Mod-Test/logs/"*_server.log | head -1)
   grep -niE '\[sort\]|Exception' "$HYTALE_LOG" | tail -20
   ```
   (The `chestlog` alias in the developer's shell wraps this.)
4. Reproduce the behavior; confirm log lines of shape `[sort] store=<hash> src=(x,y,z) itemsMoved=N dest=[(x,y,z)=qty, ...]` only appear when items actually transfer (the counter diffs before/after quantity, so zero-actual-transfer ticks don't log).

## Code layout

Plugin code in `src/main/java/dev/sorter/sortingchest/` — one file per ECS concept, mirroring the idiom in upstream mods like Simply-Trash:

```
src/main/java/dev/sorter/sortingchest/
  SortingChestPlugin.java         JavaPlugin lifecycle: setup() registers component + system, start() logs
                                  and registers join listener, holds the disabled/markDisabled state
  SortingChestBlock.java          marker Component<ChunkStore> for Sorting Chest entities
  SortingChestSystem.java         DelayedSystem<ChunkStore> that does the sort pass every 2s
  Pos.java                        neutral 3D position record; decouples internals from Hytale's Vector3d
  SpatialPositions.java           MethodHandle-based adapter for SpatialData.getVector so one jar runs on
                                  both release and pre-release Hytale builds
```

Assets and manifest at `src/main/resources/` (jar root at runtime):

```
src/main/resources/
  manifest.json                              unified plugin + asset-pack manifest
  Server/
    Item/Items/Sorting_Chest.json            item + block + inline recipe, declares Arkevius_SortingChestBlock
    Languages/en-US/server.lang              key=value, not JSON
```

`processResources` templates `manifest.json` with `mod_name`, `mod_version`, and `hytale_build` from `gradle.properties`.


<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:b9766037 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

## Landing the Plane (Session Completion)

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   bd dolt push
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds
<!-- END BEADS INTEGRATION -->

## Project overrides to the beads boilerplate above

The beads-installed block above is generic. This project's actual conventions override several of its rules — keep both in mind, and prefer these when they conflict:

- **`bd remember` vs `MEMORY.md`**: this project keeps native Claude Code memory files at `~/.claude/projects/-Users-derek-Projects-hytale-chest-mod/memory/` (MEMORY.md + per-entry files). They predate beads and already hold validated feedback. Use them for cross-session *preferences / feedback / project facts*. Use `bd` for *issue tracking specifically* (epics, bugs, feature work). They're not interchangeable.
- **`bd` vs `TaskCreate`**: `bd` is persistent across sessions (issues, epics, roadmap). `TaskCreate` is the Claude Code ephemeral in-session task list — a 6-step plan that evaporates when the session ends. Use `TaskCreate` for intra-session tracking of the current unit of work; use `bd` for anything that should survive the session.
- **"Push on every session"**: explicitly overridden. We use **branch protection + PR review** on `main`. Don't push to `main`; open a PR. Don't merge PRs without user approval. When ending a session with unpushed feature-branch commits, push the feature branch and leave the PR to the user for approval.
- **Destructive / shared-state actions require user confirmation**: force-pushes, `bd dolt push` to a remote the user hasn't set up, branch deletions, CurseForge uploads, server restarts on the Docker production server. Always ask before, never unilateral.
- **Issue shape**: phases use type=`epic` (e.g. `sc-l47` Phase 2). Phase work uses type=`task` with `--parent <epic-id>`. Review advisories use type=`task` with label `phase-1-followup` or `v0.1.2-followup`. Priorities: P0 critical/research, P1 main feature work, P2 default, P3 polish/low.

### Starter command set

```bash
bd ready                         # What can I pick up right now?
bd show sc-l47                   # Inspect an epic or issue
bd children sc-l47               # Drill into an epic's children
bd list --status open -l phase-2 # Filter by label
bd update <id> --claim           # Claim before working
bd close <id> --reason "..."     # Close with context
bd create "Title" -t task -p 2 --parent <epic-id>  # New child task under an epic
```
