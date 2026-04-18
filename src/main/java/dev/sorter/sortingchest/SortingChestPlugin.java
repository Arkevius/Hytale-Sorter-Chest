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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class SortingChestPlugin extends JavaPlugin {

    // Prefix shared by the SEVERE log line, the one-shot chat broadcast, and the
    // per-joiner chat message. We append the specific disable reason at runtime
    // so players see WHY instead of just "disabled" (sc-mbv).
    private static final String DISABLED_WARNING_PREFIX =
        "[Sorting Chest] Mod is currently disabled";
    private static final String DISABLED_WARNING_SUFFIX =
        ". Items placed in Sorting Chests will not be redistributed. Check for a mod update.";

    // AtomicBoolean rather than volatile: markDisabled is check-then-set, which isn't
    // atomic under a volatile boolean. Engine may dispatch delayedTick for different
    // stores on different threads; without CAS, two concurrent throws could double-fire
    // the broadcast. compareAndSet is the canonical one-shot guard (sc-5rd).
    private final AtomicBoolean disabled = new AtomicBoolean(false);
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
            if (!disabled.get()) return;
            Holder<EntityStore> holder = event.getHolder();
            if (holder == null) return;
            PlayerRef playerRef = holder.getComponent(PlayerRef.getComponentType());
            if (playerRef == null || !playerRef.isValid()) return;
            playerRef.sendMessage(Message.raw(buildDisabledWarning()));
        });
    }

    public boolean isDisabled() {
        return disabled.get();
    }

    /**
     * Flip the mod into dormant mode and warn every connected player exactly
     * once. Repeat calls are no-ops (compareAndSet guarantees single-fire even
     * under concurrent store ticks), so a repeatedly-crashing tick doesn't
     * spam chat.
     */
    public void markDisabled(String reason, Throwable cause) {
        // Publish the reason BEFORE flipping the flag so a reader that observes
        // disabled==true via the subsequent CAS also sees the reason (CAS has
        // happens-before semantics). Previously the order was reversed and a
        // player joining in the narrow window between flag-flip and reason-write
        // got a reason-free warning.
        disableReason = reason;
        if (!disabled.compareAndSet(false, true)) return;

        if (cause != null) {
            getLogger().at(Level.SEVERE).log("Sorting Chest DISABLED: %s", reason, cause);
        } else {
            getLogger().at(Level.SEVERE).log("Sorting Chest DISABLED: %s", reason);
        }

        try {
            Universe universe = Universe.get();
            if (universe != null) {
                universe.sendMessage(Message.raw(buildDisabledWarning()));
            }
        } catch (Throwable t) {
            // Best-effort; don't let a broadcast failure undo the disable state.
            getLogger().at(Level.WARNING).log("Failed to broadcast disable warning: %s", t);
        }
    }

    /**
     * Assemble the player-facing chat message including the current disable
     * reason (sc-mbv). Called both from the one-shot broadcast and from the
     * per-joiner listener.
     */
    private String buildDisabledWarning() {
        String reason = disableReason;
        if (reason == null || reason.isEmpty()) {
            return DISABLED_WARNING_PREFIX + DISABLED_WARNING_SUFFIX;
        }
        return DISABLED_WARNING_PREFIX + ": " + reason + DISABLED_WARNING_SUFFIX;
    }
}
