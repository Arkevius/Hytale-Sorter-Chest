package dev.sorter.sortingchest;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialData;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.component.system.DelayedSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Level;

/**
 * Engine-driven sort pass. Fires once per ChunkStore every
 * {@value #SORT_INTERVAL_SEC} seconds via Hytale's tick dispatch — no
 * manual scheduling, no thread hopping (see CLAUDE.md).
 *
 * Per pass: walk the store's ItemContainerBlock entities, split into
 * source Sorting Chests and candidate targets, and for each closed
 * source with items route per-slot stacks to in-radius containers that
 * already hold a stackable copy of that item.
 */
public final class SortingChestSystem extends DelayedSystem<ChunkStore> {

    private static final float SORT_INTERVAL_SEC = 2.0f;
    private static final double SORT_RADIUS = 100.0;
    private static final double SORT_RADIUS_SQ = SORT_RADIUS * SORT_RADIUS;

    // Migration path for v0.1.x upgrades: chunks saved before the SortingChestBlock
    // component existed don't auto-attach it on deserialization. Detect unmarked
    // container entities whose block type matches and back-attach the marker.
    // Sorting_Chest.json declares State.Definitions, so BlockType.getId() at runtime
    // is always of the form "*Sorting_Chest_State_Definitions_<State>" — prefix match
    // is sufficient (see CLAUDE.md "State-variant block ids"). The bare "Sorting_Chest"
    // id is never returned for a placed block.
    private static final String SORTING_CHEST_STATE_PREFIX = "*Sorting_Chest_State_Definitions_";

    private final SortingChestPlugin plugin;
    private final HytaleLogger logger;
    private final ComponentType<ChunkStore, SortingChestBlock> sortingChestType;
    private final ComponentType<ChunkStore, ItemContainerBlock> itemContainerType;
    private final ResourceType<ChunkStore, SpatialResource<Ref<ChunkStore>, ChunkStore>> spatialType;
    // Positive-only cache: we populate this the first tick `resolveWorldFor`
    // succeeds for a store and never again. A negative resolution (Universe.get()
    // not up yet) is NOT cached, so we re-probe on the next tick until we
    // succeed. That's the deliberate choice: caching a null (or Optional.empty)
    // would be a permanent miss if the first tick for this store happened to
    // race Universe startup, and migration for that store would never run.
    // Universe.getWorlds() is O(number-of-worlds) which is tiny; the re-probe
    // cost during the narrow startup window is negligible (sc-134).
    //
    // WeakHashMap so destroyed stores don't linger; synchronized wrapper because
    // the engine is free to fire delayedTick for different stores on different
    // threads.
    private final Map<Store<ChunkStore>, World> worldByStore =
        Collections.synchronizedMap(new WeakHashMap<>());

    private record Entry(
        Ref<ChunkStore> ref,
        Pos position,
        SimpleItemContainer container,
        boolean isSortingChest) {}

    public SortingChestSystem(
        SortingChestPlugin plugin,
        HytaleLogger logger,
        ComponentType<ChunkStore, SortingChestBlock> sortingChestType
    ) {
        super(SORT_INTERVAL_SEC);
        this.plugin = plugin;
        this.logger = logger;
        this.sortingChestType = sortingChestType;
        BlockModule blockModule = BlockModule.get();
        this.itemContainerType = blockModule.getItemContainerBlockComponentType();
        this.spatialType = blockModule.getItemContainerSpatialResourceType();
    }

    @Override
    public void delayedTick(float dt, int pass, Store<ChunkStore> store) {
        if (plugin.isDisabled()) return;
        try {
            delayedTickImpl(dt, pass, store);
        } catch (Throwable t) {
            plugin.markDisabled(
                "error during sort tick: " + t.getClass().getSimpleName() + " " + t.getMessage(), t);
        }
    }

    private void delayedTickImpl(float dt, int pass, Store<ChunkStore> store) throws Throwable {
        if (store.getEntityCountFor(itemContainerType) == 0) return;
        int storeHash = System.identityHashCode(store);

        // We only need the World for block-type lookups during legacy-marker migration.
        // Null is fine — just means we can't migrate on THIS store this tick. Cache on
        // success only; re-probe on miss (see comment on worldByStore).
        final World world = lookupWorld(store);

        // Decide up front whether this tick can run the legacy-marker migration.
        // Off-thread delayedTick dispatch would make getChunkIfLoaded deadlock
        // against the store lock held by forEachChunk (see CLAUDE.md), so gate
        // migration on isInThread()+isStarted(). Log once per tick at FINE level
        // if we're skipping so off-thread dispatch is diagnosable without
        // spamming per-entity.
        //
        // Snapshot isStarted / isInThread into locals BEFORE computing canMigrate
        // so the diagnostic log below reports the exact values that drove the
        // decision. Re-reading world.isInThread() inside the log call could show
        // a different (more recent) value if the world's thread state transitions
        // between the snapshot and the log call — confusing in traces.
        final boolean worldStarted = world != null && world.isStarted();
        final boolean worldInThread = worldStarted && world.isInThread();
        final boolean canMigrate = worldInThread;
        if (world != null && !canMigrate) {
            logger.at(Level.FINE).log(
                "[sort] migration skipped this tick: world=%s started=%b inThread=%b store=%08x",
                world.getName(), worldStarted, worldInThread, storeHash);
        }

        // SpatialResource is populated by Hytale's ItemContainerBlockSpatialSystem
        // and maps every container-block entity to its world-space position. The
        // Vector3d type returned by SpatialData.getVector differs across Hytale
        // builds (hytale-native on release, org.joml on pre-release), so we go
        // through SpatialPositions which resolves the right MethodHandle once
        // and returns our neutral Pos.
        SpatialResource<Ref<ChunkStore>, ChunkStore> spatial = store.getResource(spatialType);
        Map<Integer, Pos> positionsByRefIndex = new HashMap<>();
        if (spatial != null) {
            SpatialData<Ref<ChunkStore>> data = spatial.getSpatialData();
            int n = data.size();
            for (int i = 0; i < n; i++) {
                Ref<ChunkStore> r = data.getData(i);
                if (r == null) continue;
                // SpatialPositions.read throws Throwable via MethodHandle.invoke. Let it
                // propagate up to delayedTick's catch-all which handles the disable flow;
                // wrapping here would hide the original exception type in the disable log.
                positionsByRefIndex.put(r.getIndex(), SpatialPositions.read(data, i));
            }
        }

        List<Entry> all = new ArrayList<>();
        List<Entry> sources = new ArrayList<>();

        store.forEachChunk(itemContainerType, (chunk, cmdBuf) -> {
            int n = chunk.size();
            for (int i = 0; i < n; i++) {
                ItemContainerBlock icb = chunk.getComponent(i, itemContainerType);
                if (icb == null) continue;
                SimpleItemContainer c = icb.getItemContainer();
                if (c == null) continue;
                Ref<ChunkStore> ref = chunk.getReferenceTo(i);
                Pos pos = (ref != null) ? positionsByRefIndex.get(ref.getIndex()) : null;
                boolean sortingChest = chunk.getComponent(i, sortingChestType) != null;
                // canMigrate was computed once per-tick above; see its comment for
                // the deadlock-guard reasoning (sc-d06).
                if (!sortingChest && canMigrate && pos != null && ref != null
                    && isSortingChestBlockAt(world, pos)) {
                    cmdBuf.addComponent(ref, sortingChestType);
                    sortingChest = true;
                    logger.at(Level.INFO).log(
                        "[sort] migrated legacy Sorting Chest marker at %s", formatPos(pos));
                }
                Entry entry = new Entry(ref, pos, c, sortingChest);
                all.add(entry);
                if (pos != null && sortingChest && !c.isEmpty() && isClosed(icb)) {
                    sources.add(entry);
                }
            }
        });

        if (sources.isEmpty()) return;

        for (Entry src : sources) {
            sortOneChest(src, all, storeHash);
        }
    }

    private void sortOneChest(Entry src, List<Entry> all, int storeHash) {
        Pos srcPos = src.position();
        short srcCap = src.container().getCapacity();

        List<Entry> candidates = all.stream()
            .filter(t -> t != src)
            .filter(t -> !t.isSortingChest())
            .filter(t -> t.position() != null && withinRadius(srcPos, t.position()))
            .toList();
        if (candidates.isEmpty()) return;

        int itemsMovedTotal = 0;
        // { destination position -> items deposited there this pass }. LinkedHashMap so
        // log order matches routing order (first-fit), not hash iteration order.
        Map<Pos, Integer> movesByDestination = new LinkedHashMap<>();
        // Reused across all moveItemStackFromSlot calls for this source. The API takes
        // an ItemContainer[] but we always pass exactly one target at a time so we can
        // attribute per-destination deltas.
        ItemContainer[] singleTarget = new ItemContainer[1];

        for (short srcSlot = 0; srcSlot < srcCap; srcSlot++) {
            ItemStack before = src.container().getItemStack(srcSlot);
            if (before == null || before.isEmpty()) continue;

            List<Entry> slotTargets = candidates.stream()
                .filter(t -> t.container().containsItemStacksStackableWith(before))
                .toList();
            if (slotTargets.isEmpty()) continue;

            // Route per-target so we can attribute how much each destination received.
            // moveItemStackFromSlot with an array would distribute, but hides the split.
            for (Entry target : slotTargets) {
                ItemStack slotBefore = src.container().getItemStack(srcSlot);
                if (slotBefore == null || slotBefore.isEmpty()) break;
                int beforeQty = slotBefore.getQuantity();

                singleTarget[0] = target.container();
                try {
                    src.container().moveItemStackFromSlot(srcSlot, singleTarget);
                } catch (Throwable t) {
                    logger.at(Level.WARNING).log(
                        "moveItemStackFromSlot failed for slot %d -> %s: %s",
                        (int) srcSlot, formatPos(target.position()), t);
                    continue;
                }

                ItemStack slotAfter = src.container().getItemStack(srcSlot);
                int afterQty = (slotAfter == null || slotAfter.isEmpty()) ? 0 : slotAfter.getQuantity();
                int delta = beforeQty - afterQty;
                if (delta > 0) {
                    movesByDestination.merge(target.position(), delta, Integer::sum);
                    itemsMovedTotal += delta;
                }
            }
        }

        if (itemsMovedTotal == 0) return;

        StringBuilder destinations = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Pos, Integer> move : movesByDestination.entrySet()) {
            if (!first) destinations.append(", ");
            destinations.append(formatPos(move.getKey())).append('=').append(move.getValue());
            first = false;
        }
        logger.at(Level.INFO).log(
            "[sort] store=%08x src=%s itemsMoved=%d dest=[%s]",
            storeHash, formatPos(srcPos), itemsMovedTotal, destinations.toString());
    }

    private static String formatPos(Pos pos) {
        if (pos == null) return "?";
        return String.format("(%d,%d,%d)",
            (int) Math.floor(pos.x()),
            (int) Math.floor(pos.y()),
            (int) Math.floor(pos.z()));
    }

    private static boolean withinRadius(Pos a, Pos b) {
        return a.distanceSquaredTo(b) <= SORT_RADIUS_SQ;
    }

    private static boolean isClosed(ItemContainerBlock icb) {
        return icb.getWindows().isEmpty();
    }

    /**
     * Return the World that owns {@code store}, using the cache on hit and falling
     * back to a live {@link #resolveWorldFor(Store)} probe on miss. Positive
     * resolutions are cached; negative ones are NOT, so we re-probe next tick until
     * Universe startup completes.
     *
     * Implemented via {@code computeIfAbsent} rather than a manual
     * {@code get}/{@code put} pair: on a synchronized-map wrapper, {@code computeIfAbsent}
     * holds the map lock across the compute and per the {@code Map} contract
     * skips the insert when the mapping function returns {@code null}. That's
     * exactly the positive-only atomic behavior we want, with no TOCTOU gap.
     */
    private World lookupWorld(Store<ChunkStore> store) {
        return worldByStore.computeIfAbsent(store, this::resolveWorldFor);
    }

    /**
     * Best-effort lookup of the World that owns a given ChunkStore. Used only for
     * legacy-marker migration; if null, migration is skipped and we fall back to
     * the JSON-declared auto-attach path that covers fresh placements.
     */
    private World resolveWorldFor(Store<ChunkStore> store) {
        Universe universe = Universe.get();
        if (universe == null) return null;
        for (World world : universe.getWorlds().values()) {
            if (world.getChunkStore().getStore() == store) {
                return world;
            }
        }
        return null;
    }

    private static boolean isSortingChestBlockAt(World world, Pos pos) {
        long key = ChunkUtil.indexChunkFromBlock(pos.x(), pos.z());
        WorldChunk chunk = world.getChunkIfLoaded(key);
        if (chunk == null) return false;
        int lx = ChunkUtil.localCoordinate((long) Math.floor(pos.x()));
        int ly = (int) Math.floor(pos.y());
        int lz = ChunkUtil.localCoordinate((long) Math.floor(pos.z()));
        BlockType type = chunk.getBlockType(lx, ly, lz);
        if (type == null) return false;
        return type.getId().startsWith(SORTING_CHEST_STATE_PREFIX);
    }
}
