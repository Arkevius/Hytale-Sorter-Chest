package dev.sorter.sortingchest;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.util.logging.Level;

public final class SortingChestPlugin extends JavaPlugin {

    public SortingChestPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        super.setup();
        ComponentType<ChunkStore, SortingChestBlock> sortingChestType =
            getChunkStoreRegistry().registerComponent(
                SortingChestBlock.class, "Arkevius_SortingChestBlock", SortingChestBlock.CODEC);
        getChunkStoreRegistry().registerSystem(
            new SortingChestSystem(getLogger(), sortingChestType));
    }

    @Override
    protected void start() {
        getLogger().at(Level.INFO).log("SortingChest start");
    }
}
