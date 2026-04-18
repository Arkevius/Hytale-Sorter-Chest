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

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class SortingChestPlugin extends JavaPlugin {

    // Prefix shared by the SEVERE log line, the one-shot chat broadcast, and the
    // per-joiner chat message. We append the specific disable reason at runtime
    // so players see WHY instead of just "disabled" (sc-mbv).
    private static final String DISABLED_WARNING_PREFIX =
        "[Sorting Chest] Mod is currently disabled";
    private static final String DISABLED_WARNING_SUFFIX =
        ". Items placed in Sorting Chests will not be redistributed. Check for a mod update.";
    // Sentinel for the "disabled but caller gave null/empty reason" case. We need
    // the AtomicReference to be non-null to signal the disabled state; this value
    // substitutes in place of a caller-supplied reason.
    private static final String DEFAULT_REASON = "(no reason supplied)";

    // Disable state is a single AtomicReference<String>:
    //   - null  → enabled (never disabled)
    //   - non-null → disabled, value is the reason that caused it
    // compareAndSet(null, reason) atomically publishes BOTH the flag and the
    // reason in one step. Previous shape (AtomicBoolean flag + separate String
    // reason field) had a two-writer race: two concurrent markDisabled calls
    // could both write to the reason field before either reached the CAS, so
    // whichever thread won the CAS might broadcast the OTHER thread's reason.
    // Collapsing them eliminates that race entirely. (sc-5rd residual fix.)
    private final AtomicReference<String> disableReason = new AtomicReference<>(null);

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
            if (!isDisabled()) return;
            Holder<EntityStore> holder = event.getHolder();
            if (holder == null) return;
            PlayerRef playerRef = holder.getComponent(PlayerRef.getComponentType());
            if (playerRef == null || !playerRef.isValid()) return;
            playerRef.sendMessage(Message.raw(buildDisabledWarning()));
        });
    }

    public boolean isDisabled() {
        return disableReason.get() != null;
    }

    /**
     * Flip the mod into dormant mode and warn every connected player exactly
     * once. Repeat calls are no-ops (compareAndSet guarantees single-fire even
     * under concurrent store ticks), so a repeatedly-crashing tick doesn't
     * spam chat.
     */
    public void markDisabled(String reason, Throwable cause) {
        String stamped = (reason == null || reason.isEmpty()) ? DEFAULT_REASON : reason;
        // Single atomic publish: if this CAS succeeds, no other thread will ever
        // see a different reason associated with this disable event.
        if (!disableReason.compareAndSet(null, stamped)) return;

        if (cause != null) {
            getLogger().at(Level.SEVERE).log("Sorting Chest DISABLED: %s", stamped, cause);
        } else {
            getLogger().at(Level.SEVERE).log("Sorting Chest DISABLED: %s", stamped);
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
        String reason = disableReason.get();
        if (reason == null) {
            // Reader observed isDisabled()==true but the CAS state has been cleared
            // (not possible in the current code path — we never reset — but defend
            // anyway in case future code adds a reopen/reenable flow).
            return DISABLED_WARNING_PREFIX + DISABLED_WARNING_SUFFIX;
        }
        return DISABLED_WARNING_PREFIX + ": " + reason + DISABLED_WARNING_SUFFIX;
    }
}
