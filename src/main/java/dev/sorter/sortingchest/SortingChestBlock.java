package dev.sorter.sortingchest;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/**
 * Marker component. Attached to every Sorting Chest entity via the JSON
 * BlockEntity.Components declaration. Lets sort code identify sources by
 * component presence instead of block-id string matching.
 */
public final class SortingChestBlock implements Component<ChunkStore> {

    public static final BuilderCodec<SortingChestBlock> CODEC =
        BuilderCodec.builder(SortingChestBlock.class, SortingChestBlock::new).build();

    private SortingChestBlock() {}

    @Override
    public Component<ChunkStore> clone() {
        return new SortingChestBlock();
    }
}
