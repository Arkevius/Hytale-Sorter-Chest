package dev.sorter.sortingchest;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.logging.Level;

public final class SortingChestPlugin extends JavaPlugin {

    // Shown in server chat when the mod is disabled, both as a one-shot broadcast
    // at the moment of failure and to every player who joins thereafter.
    private static final String DISABLED_WARNING =
        "[Sorting Chest] Mod is currently disabled — the server build is not compatible "
            + "with this version of Sorting Chest. Items placed in Sorting Chests will not "
            + "be redistributed. Check for a mod update.";

    private volatile boolean disabled = false;
    private volatile String disableReason = null;

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
            new SortingChestSystem(this, getLogger(), sortingChestType));
    }

    @Override
    protected void start() {
        getLogger().at(Level.INFO).log("SortingChest start");

        // If the reflective shim couldn't resolve SpatialData.getVector on this
        // Hytale build, disable early rather than waiting for the first tick to
        // crash and trigger the catch-all.
        if (!SpatialPositions.isCompatible()) {
            markDisabled("SpatialData.getVector not resolvable: " + SpatialPositions.diagnostic(), null);
        }

        // Late-joiners see the warning on entry even if the disable happened
        // before they connected.
        getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, event -> {
            if (!disabled) return;
            Holder<EntityStore> holder = event.getHolder();
            if (holder == null) return;
            PlayerRef playerRef = holder.getComponent(PlayerRef.getComponentType());
            if (playerRef == null || !playerRef.isValid()) return;
            playerRef.sendMessage(Message.raw(DISABLED_WARNING));
        });
    }

    public boolean isDisabled() {
        return disabled;
    }

    /**
     * Flip the mod into dormant mode and warn every connected player exactly
     * once. Subsequent calls are no-ops so a repeatedly-crashing tick doesn't
     * spam chat.
     */
    public void markDisabled(String reason, Throwable cause) {
        if (disabled) return;
        disabled = true;
        disableReason = reason;

        if (cause != null) {
            getLogger().at(Level.SEVERE).log("Sorting Chest DISABLED: %s", reason, cause);
        } else {
            getLogger().at(Level.SEVERE).log("Sorting Chest DISABLED: %s", reason);
        }

        try {
            Universe universe = Universe.get();
            if (universe != null) {
                universe.sendMessage(Message.raw(DISABLED_WARNING));
            }
        } catch (Throwable t) {
            // Best-effort; don't let a broadcast failure undo the disable state.
            getLogger().at(Level.WARNING).log("Failed to broadcast disable warning: %s", t);
        }
    }
}
