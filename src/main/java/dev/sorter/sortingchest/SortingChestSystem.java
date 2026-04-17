package dev.sorter.sortingchest;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialData;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.component.system.DelayedSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final HytaleLogger logger;
    private final ComponentType<ChunkStore, SortingChestBlock> sortingChestType;
    private final ComponentType<ChunkStore, ItemContainerBlock> itemContainerType;

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
}
