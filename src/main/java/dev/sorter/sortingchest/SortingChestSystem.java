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
import com.hypixel.hytale.math.vector.Vector3d;
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
    // Blocks declared with State.Definitions get exploded into per-state BlockType
    // entries keyed as "*<Id>_State_Definitions_<State>". Match any state variant.
    private static final String SORTING_CHEST_ID = "Sorting_Chest";
    private static final String SORTING_CHEST_STATE_PREFIX = "*Sorting_Chest_State_Definitions_";

    private final HytaleLogger logger;
    private final ComponentType<ChunkStore, SortingChestBlock> sortingChestType;
    private final ComponentType<ChunkStore, ItemContainerBlock> itemContainerType;
    // Populated lazily the first time a given store ticks. WeakHashMap because Hytale
    // is free to destroy and rebuild a store without notifying us.
    private final Map<Store<ChunkStore>, World> worldByStore = new WeakHashMap<>();

    private record Entry(
        Ref<ChunkStore> ref,
        Vector3d position,
        SimpleItemContainer container,
        boolean isSortingChest) {}

    public SortingChestSystem(
        HytaleLogger logger,
        ComponentType<ChunkStore, SortingChestBlock> sortingChestType
    ) {
        super(SORT_INTERVAL_SEC);
        this.logger = logger;
        this.sortingChestType = sortingChestType;
        this.itemContainerType = BlockModule.get().getItemContainerBlockComponentType();
    }

    @Override
    public void delayedTick(float dt, int pass, Store<ChunkStore> store) {
        if (store.getEntityCountFor(itemContainerType) == 0) return;
        int storeHash = System.identityHashCode(store);

        // We only need the World for block-type lookups during legacy-marker migration.
        // Null is fine — just means we can't migrate on THIS store this tick.
        World world = worldByStore.computeIfAbsent(store, this::resolveWorldFor);

        // SpatialResource is populated by Hytale's ItemContainerBlockSpatialSystem
        // and maps every container-block entity to its world-space Vector3d.
        ResourceType<ChunkStore, SpatialResource<Ref<ChunkStore>, ChunkStore>> spatialType =
            BlockModule.get().getItemContainerSpatialResourceType();
        SpatialResource<Ref<ChunkStore>, ChunkStore> spatial = store.getResource(spatialType);
        Map<Integer, Vector3d> positionsByRefIndex = new HashMap<>();
        if (spatial != null) {
            SpatialData<Ref<ChunkStore>> data = spatial.getSpatialData();
            int n = data.size();
            for (int i = 0; i < n; i++) {
                Ref<ChunkStore> r = data.getData(i);
                if (r == null) continue;
                positionsByRefIndex.put(r.getIndex(), data.getVector(i));
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
                Vector3d pos = (ref != null) ? positionsByRefIndex.get(ref.getIndex()) : null;
                boolean sortingChest = chunk.getComponent(i, sortingChestType) != null;
                if (!sortingChest && world != null && pos != null && ref != null
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
        Vector3d srcPos = src.position();
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
        Map<Vector3d, Integer> movesByDestination = new LinkedHashMap<>();

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

                try {
                    src.container().moveItemStackFromSlot(srcSlot, new ItemContainer[]{ target.container() });
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
        for (Map.Entry<Vector3d, Integer> move : movesByDestination.entrySet()) {
            if (!first) destinations.append(", ");
            destinations.append(formatPos(move.getKey())).append('=').append(move.getValue());
            first = false;
        }
        logger.at(Level.INFO).log(
            "[sort] store=%08x src=%s itemsMoved=%d dest=[%s]",
            storeHash, formatPos(srcPos), itemsMovedTotal, destinations.toString());
    }

    private static String formatPos(Vector3d pos) {
        if (pos == null) return "?";
        return String.format("(%d,%d,%d)",
            (int) Math.floor(pos.getX()),
            (int) Math.floor(pos.getY()),
            (int) Math.floor(pos.getZ()));
    }

    private static boolean withinRadius(Vector3d a, Vector3d b) {
        return a.distanceSquaredTo(b) <= SORT_RADIUS_SQ;
    }

    private static boolean isClosed(ItemContainerBlock icb) {
        return icb.getWindows().isEmpty();
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

    private static boolean isSortingChestBlockAt(World world, Vector3d pos) {
        long key = ChunkUtil.indexChunkFromBlock(pos.getX(), pos.getZ());
        WorldChunk chunk = world.getChunkIfLoaded(key);
        if (chunk == null) return false;
        int lx = ChunkUtil.localCoordinate((long) Math.floor(pos.getX()));
        int ly = (int) Math.floor(pos.getY());
        int lz = ChunkUtil.localCoordinate((long) Math.floor(pos.getZ()));
        BlockType type = chunk.getBlockType(lx, ly, lz);
        if (type == null) return false;
        String id = type.getId();
        return SORTING_CHEST_ID.equals(id) || id.startsWith(SORTING_CHEST_STATE_PREFIX);
    }
}
