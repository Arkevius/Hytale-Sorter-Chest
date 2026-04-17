package dev.sorter.sortingchest;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialData;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class SortingChestPlugin extends JavaPlugin {

    private static final String SORTING_CHEST_ID = "Sorting_Chest";
    // Blocks declared with State.Definitions in the asset JSON are exploded
    // into per-state BlockType entries keyed as "*<Id>_State_Definitions_<State>"
    // (e.g. OpenWindow, CloseWindow). Match any state variant of our block.
    private static final String SORTING_CHEST_STATE_PREFIX = "*Sorting_Chest_State_Definitions_";
    private static final double SORT_RADIUS = 100.0;
    private static final double SORT_RADIUS_SQ = SORT_RADIUS * SORT_RADIUS;

    private record Entry(
        Ref<ChunkStore> ref,
        Vector3d position,
        SimpleItemContainer container,
        String blockId) {}

    public SortingChestPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void start() {
        getLogger().at(Level.INFO).log("SortingChest start");

        @SuppressWarnings("unchecked")
        ScheduledFuture<Void> task = (ScheduledFuture<Void>) HytaleServer.SCHEDULED_EXECUTOR.scheduleWithFixedDelay(
            this::sortTick, 2L, 2L, TimeUnit.SECONDS);
        getTaskRegistry().registerTask(task);
    }

    private void sortTick() {
        Universe universe = Universe.get();
        if (universe == null) return;
        for (World world : universe.getWorlds().values()) {
            world.execute(() -> sortWorld(world));
        }
    }

    private void sortWorld(World world) {
        Store<ChunkStore> store = world.getChunkStore().getStore();
        ComponentType<ChunkStore, ItemContainerBlock> containerType =
            BlockModule.get().getItemContainerBlockComponentType();

        if (store.getEntityCountFor(containerType) == 0) return;

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

        store.forEachChunk(containerType, (chunk, cmdBuf) -> {
            int n = chunk.size();
            for (int i = 0; i < n; i++) {
                ItemContainerBlock icb = chunk.getComponent(i, containerType);
                if (icb == null) continue;
                SimpleItemContainer c = icb.getItemContainer();
                if (c == null) continue;
                Ref<ChunkStore> ref = chunk.getReferenceTo(i);
                Vector3d pos = (ref != null) ? positionsByRefIndex.get(ref.getIndex()) : null;
                String blockId = (pos != null) ? resolveBlockId(world, pos) : null;
                Entry entry = new Entry(ref, pos, c, blockId);
                all.add(entry);
                if (pos != null
                    && isSortingChest(blockId)
                    && !c.isEmpty()
                    && isClosed(icb)) {
                    sources.add(entry);
                }
            }
        });

        if (sources.isEmpty()) return;

        for (Entry src : sources) {
            sortOneChest(src, all, world);
        }
    }

    private void sortOneChest(Entry src, List<Entry> all, World world) {
        Vector3d srcPos = src.position();
        short srcCap = src.container().getCapacity();
        int itemsMoved = 0;

        List<Entry> candidates = all.stream()
            .filter(t -> t != src)
            .filter(t -> !isSortingChest(t.blockId()))
            .filter(t -> t.position() != null && withinRadius(srcPos, t.position()))
            .toList();
        if (candidates.isEmpty()) return;

        for (short srcSlot = 0; srcSlot < srcCap; srcSlot++) {
            ItemStack before = src.container().getItemStack(srcSlot);
            if (before == null || before.isEmpty()) continue;

            ItemContainer[] targets = candidates.stream()
                .filter(t -> t.container().containsItemStacksStackableWith(before))
                .map(Entry::container)
                .toArray(ItemContainer[]::new);
            if (targets.length == 0) continue;

            int beforeQty = before.getQuantity();
            try {
                src.container().moveItemStackFromSlot(srcSlot, targets);
            } catch (Throwable t) {
                getLogger().at(Level.WARNING).log(
                    "moveItemStackFromSlot failed for slot %d: %s", (int) srcSlot, t);
                continue;
            }
            ItemStack after = src.container().getItemStack(srcSlot);
            int afterQty = (after == null || after.isEmpty()) ? 0 : after.getQuantity();
            itemsMoved += (beforeQty - afterQty);
        }

        if (itemsMoved > 0) {
            getLogger().at(Level.INFO).log(
                "[sort] SortingChest: world='%s' itemsMoved=%d",
                world.getName(), itemsMoved);
        }
    }

    private static boolean withinRadius(Vector3d a, Vector3d b) {
        return a.distanceSquaredTo(b) <= SORT_RADIUS_SQ;
    }

    private static boolean isSortingChest(String blockId) {
        return blockId != null
            && (SORTING_CHEST_ID.equals(blockId)
                || blockId.startsWith(SORTING_CHEST_STATE_PREFIX));
    }

    private static String resolveBlockId(World world, Vector3d pos) {
        long key = ChunkUtil.indexChunkFromBlock(pos.getX(), pos.getZ());
        WorldChunk chunk = world.getChunkIfLoaded(key);
        if (chunk == null) return null;
        int lx = ChunkUtil.localCoordinate((long) Math.floor(pos.getX()));
        int ly = (int) Math.floor(pos.getY());
        int lz = ChunkUtil.localCoordinate((long) Math.floor(pos.getZ()));
        BlockType type = chunk.getBlockType(lx, ly, lz);
        return (type == null) ? null : type.getId();
    }

    private static boolean isClosed(ItemContainerBlock icb) {
        return icb.getWindows().isEmpty();
    }
}
