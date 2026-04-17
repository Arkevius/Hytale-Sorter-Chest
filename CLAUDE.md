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
- **Plugin entry**: extend `com.hypixel.hytale.server.core.plugin.JavaPlugin`, take `JavaPluginInit` in the constructor, override **`protected void start()`**. `setup()` runs too early — other core plugins aren't up yet.
- **ECS events do NOT dispatch through the plugin `EventRegistry`.** Events in `com.hypixel.hytale.server.core.event.events.ecs.*` (e.g. `PlaceBlockEvent`) fire on per-store buses. `events.player.*` events (e.g. `PlayerChatEvent`) work fine via `getEventRegistry().registerGlobal(Class, Consumer)`. For block-placement-style triggers, prefer polling via `HytaleServer.SCHEDULED_EXECUTOR` + ECS iteration.
- **World thread**: `World implements Executor`. Dispatch all ECS reads/writes via `world.execute(Runnable)`.
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
4. Reproduce the behavior; confirm `[sort] ... itemsMoved=N` lines only appear when items actually transfer (the counter diffs before/after quantity, so zero-actual-transfer ticks don't log).

## Code layout

Single-file plugin at `src/main/java/dev/sorter/sortingchest/SortingChestPlugin.java`. Assets and manifest at `src/main/resources/` (jar root at runtime):

```
src/main/resources/
  manifest.json                              unified plugin + asset-pack manifest
  Server/
    Item/Items/Sorting_Chest.json            item + block + inline recipe
    Languages/en-US/server.lang              key=value, not JSON
```

`processResources` templates `manifest.json` with `mod_name`, `mod_version`, and `hytale_build` from `gradle.properties`.
