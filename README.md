# Sorting Chest

A Hytale server-side mod that adds a magical chest you craft at the Arcanist's Workbench. Drop items into it and close the lid — they redistribute automatically into any chest within 100 blocks that already holds that item type. Unmatched items and overflow stay put.

Think of it as the terminus of a lazy storage system: one drop-off, everything goes home.

## How it behaves

- **Craft it** at the Arcanist's Workbench for 4 seconds with Redwood Planks ×4, Iron Bar ×2, and Void Essence ×4.
- **Place it** anywhere. It's a 27-slot container; items persist like any other chest.
- **Drop items in, close the lid.** Within a couple seconds, each stack in the Sorting Chest flows into nearby chests that already have at least one stackable copy of that item. Different item types are routed independently — dirt goes to the dirt chest, ore goes to the ore chest, even if they're in the same Sorting Chest.
- **Radius**: 100 blocks from the Sorting Chest. Chests beyond that range are ignored.
- **Full or no-match**: if every candidate chest is full, or no chest in range holds the item, it stays in the Sorting Chest. Add room or designate a new "home" chest and the backlog flows on the next sort pass.
- **Sorting Chests don't cross-sort**: items won't shuffle from one Sorting Chest to another — they only flow into regular containers.
- **Open UI is respected**: the chest won't sort while you're looking at its contents. Close the lid and the pass runs.

## Install

1. Grab the latest `sorting_chest-<version>.jar` from the [releases page](../../releases), or build from source (below).
2. Drop the jar into your Hytale UserData `mods/` directory. That's it — the jar is both the plugin and the asset pack.

The easiest way is to build + install in one shot:

```bash
./gradlew build installLocal
```

`installLocal` auto-detects the UserData directory:

- **Windows**: `%APPDATA%\Hytale\UserData`
- **macOS**: `~/Library/Application Support/Hytale/UserData`
- **Linux**: `~/.config/Hytale/UserData`

Or override: `HYTALE_USERDATA=/custom/path ./gradlew installLocal`.

After install:

```
<UserData>/mods/sorting_chest-<version>.jar    ← single artifact (plugin + assets)
```

Restart the server, load your world, open the Arcanist's Workbench, craft, place. Done.

## Build from source

Requires JDK 25 (auto-provisioned by Gradle's toolchain if not already installed) and internet access to fetch Hytale's Maven artifact.

```bash
git clone https://github.com/Arkevius/Hytale-Sorter-Chest.git
cd Hytale-Sorter-Chest
./gradlew build
```

Output lands at `build/libs/sorting-chest-<version>.jar`.

## How it works

The plugin extends `com.hypixel.hytale.server.core.plugin.JavaPlugin` and runs a 2-second polling task. Each tick, on the world thread, it:

1. Walks the ECS chunk store for every block carrying Hytale's built-in `ItemContainerBlock` component.
2. Identifies which of those are Sorting Chests by resolving the block type at the entity's world position and matching on the base id (including Hytale's state-variant ids like `*Sorting_Chest_State_Definitions_CloseWindow`).
3. For each Sorting Chest whose UI is not currently open and that contains items, iterates its slots one at a time. For each stack, filters the world's other containers to those within 100 blocks that already hold a stackable instance, and hands them to `ItemContainer.moveItemStackFromSlot`.

The 100-block scope uses Hytale's own `ItemContainerBlockSpatialSystem` resource for position lookups — no manual block scanning.

## Project layout

```
src/main/resources/
  manifest.json                                    unified plugin + asset-pack manifest
  Server/
    Item/Items/Sorting_Chest.json                  item + block + inline recipe
    Languages/en-US/server.lang                    translations
src/main/java/dev/sorter/sortingchest/
  SortingChestPlugin.java                          the whole plugin
build.gradle, settings.gradle, gradle.properties
```

The model and textures currently reuse Hytale's Lumberjack Small Chest assets so the block has working visuals. A custom arcane-themed model will ship in a later release.

## Roadmap

- Custom 3D model and texture
- Tiers (higher-radius variants, upgrade recipes)
- Item filters (whitelist / blacklist per chest)
- Void overflow (optional: delete items when no destination exists)
- Priority ordering (flag chests as preferred destinations)

## Compatibility

- Hytale **Update 4** (`2026.03.26-89796e57b` or newer). Earlier server builds won't resolve the Maven artifact.
- Server-side only. Install goes on the host; clients don't need anything.

## License

MIT. See [LICENSE](LICENSE).

## Credits

- [Hytale](https://hytale.com) by Hypixel Studios.
- [Official plugin template](https://github.com/HytaleModding/plugin-template) and the real-world plugins by [Nitrado](https://github.com/nitrado/hytale-plugin-performance-saver) and [CopenJoe](https://github.com/Kaupenjoe/Hytale-Example-Plugin) provided the structural patterns for this mod.
