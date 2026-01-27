package com.tokebak.SignaturePreservation;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * System that preserves SignatureEnergy (Q meter) between hotbar slot swaps.
 * 
 * By default, Hytale resets SignatureEnergy when switching hotbar slots.
 * This system runs every tick for each player and detects slot changes by
 * comparing the current active slot to the last known slot.
 * 
 * When a slot change is detected:
 * 1. Saves the current SignatureEnergy to the OLD weapon's item metadata
 * 2. Restores SignatureEnergy from the NEW weapon's metadata (if any)
 * 
 * Extends EntityTickingSystem to properly integrate with the ECS system.
 */
public class SignatureEnergyPreservationSystem extends EntityTickingSystem<EntityStore> {
    
    /**
     * Metadata key for storing saved SignatureEnergy on items.
     */
    public static final String META_KEY_SIGNATURE_ENERGY = "SP_SavedSignatureEnergy";
    
    private final SignaturePreservationConfig config;
    
    /**
     * Tracks the last known active hotbar slot per player UUID.
     * Used to detect when the slot has changed.
     */
    private final Map<UUID, Byte> lastActiveSlot = new ConcurrentHashMap<>();
    
    /**
     * Tracks the SignatureEnergy value from the PREVIOUS tick per player UUID.
     * This is crucial because by the time we detect a slot change, the game has
     * already reset the energy. We need the value from BEFORE the reset.
     */
    private final Map<UUID, Float> previousTickEnergy = new ConcurrentHashMap<>();
    
    /**
     * Scheduler for delayed energy restoration.
     * We need to delay restoration to ensure the game's reset has fully completed.
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    
    public SignatureEnergyPreservationSystem(@Nonnull final SignaturePreservationConfig config) {
        this.config = config;
    }
    
    /**
     * Helper to log debug messages only when debug mode is enabled.
     */
    private void debug(@Nonnull final String message) {
        if (this.config.isDebug()) {
            System.out.println("[SIGPREV:DEBUG] " + message);
        }
    }
    
    /**
     * Query that determines which entities this system ticks for.
     * We want to tick for all Player entities.
     */
    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }
    
    /**
     * Called every tick for each player entity.
     * Checks if the active hotbar slot has changed and handles energy preservation.
     */
    @Override
    public void tick(
            final float dt,
            final int index,
            @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull final Store<EntityStore> store,
            @Nonnull final CommandBuffer<EntityStore> commandBuffer
    ) {
        // Skip if feature is disabled
        if (!this.config.isEnabled()) {
            return;
        }
        
        // Get the entity reference
        final Ref<EntityStore> entityRef = archetypeChunk.getReferenceTo(index);
        if (entityRef == null || !entityRef.isValid()) {
            return;
        }
        
        // Get Player component
        final Player player = (Player) archetypeChunk.getComponent(index, Player.getComponentType());
        if (player == null) {
            return;
        }
        
        final Inventory inventory = player.getInventory();
        if (inventory == null) {
            return;
        }
        
        // Get player UUID for tracking
        final UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return;
        }
        
        // Get current active slot
        final byte currentSlot = inventory.getActiveHotbarSlot();
        
        // Read current SignatureEnergy (we track this every tick)
        final float currentEnergy = this.getSignatureEnergy(entityRef, store);
        
        // Get last known slot (or initialize if first time)
        final Byte lastSlotObj = this.lastActiveSlot.get(playerUuid);
        if (lastSlotObj == null) {
            // First time seeing this player - just record their current slot and energy
            this.lastActiveSlot.put(playerUuid, currentSlot);
            this.previousTickEnergy.put(playerUuid, currentEnergy);
            this.debug(String.format("Player first tick - initial slot: %d, energy: %.1f", currentSlot, currentEnergy));
            return;
        }
        
        final byte previousSlot = lastSlotObj;
        
        // Check if slot changed
        if (currentSlot == previousSlot) {
            // No slot change - just update the tracked energy for next tick
            this.previousTickEnergy.put(playerUuid, currentEnergy);
            return;
        }
        
        // Slot changed! Get the energy from BEFORE the reset (previous tick's value)
        final Float energyBeforeReset = this.previousTickEnergy.get(playerUuid);
        final float savedEnergy = energyBeforeReset != null ? energyBeforeReset : 0f;
        
        // Update tracking for next tick
        this.lastActiveSlot.put(playerUuid, currentSlot);
        this.previousTickEnergy.put(playerUuid, currentEnergy);
        
        this.debug(String.format("Hotbar slot change detected: %d -> %d (energy before reset: %.1f)",
                previousSlot, currentSlot, savedEnergy));
        
        // Handle the slot change with the pre-reset energy value
        this.handleSlotChange(entityRef, store, inventory, previousSlot, currentSlot, savedEnergy);
    }
    
    /**
     * Handle a detected hotbar slot change.
     */
    private void handleSlotChange(
            @Nonnull final Ref<EntityStore> entityRef,
            @Nonnull final Store<EntityStore> store,
            @Nonnull final Inventory inventory,
            final byte previousSlot,
            final byte currentSlot,
            final float energyBeforeReset
    ) {
        // Get the items in old and new slots
        final ItemContainer hotbar = inventory.getHotbar();
        final ItemStack oldItem = hotbar.getItemStack((short) previousSlot);
        final ItemStack newItem = hotbar.getItemStack((short) currentSlot);
        
        this.debug(String.format("Hotbar swap: slot %d -> %d | Energy before reset: %.1f",
                previousSlot, currentSlot, energyBeforeReset));
        this.debug(String.format("Old item: %s | New item: %s",
                oldItem != null ? oldItem.getItem().getId() : "null",
                newItem != null ? newItem.getItem().getId() : "null"));
        
        // Save the pre-reset SignatureEnergy to the OLD item's metadata (if it's a weapon)
        final boolean oldIsWeapon = oldItem != null && !oldItem.isEmpty() && this.isWeapon(oldItem);
        this.debug(String.format("Old item is weapon: %b", oldIsWeapon));
        
        if (oldIsWeapon) {
            if (energyBeforeReset > 0) {
                final ItemStack updatedOldItem = this.saveSignatureEnergy(oldItem, energyBeforeReset);
                hotbar.setItemStackForSlot((short) previousSlot, updatedOldItem);
                this.debug(String.format("SAVED SignatureEnergy %.1f to old weapon in slot %d",
                        energyBeforeReset, previousSlot));
            } else {
                this.debug("Skipping save - energy before reset is 0");
            }
        }
        
        // Check if the NEW item has saved SignatureEnergy to restore
        final boolean newIsWeapon = newItem != null && !newItem.isEmpty() && this.isWeapon(newItem);
        this.debug(String.format("New item is weapon: %b", newIsWeapon));
        
        if (newIsWeapon) {
            final Float savedEnergy = this.getSavedSignatureEnergy(newItem);
            this.debug(String.format("Saved energy in new weapon metadata: %s", savedEnergy));
            
            if (savedEnergy != null && savedEnergy > 0) {
                // Clear the saved energy from the new item's metadata
                final ItemStack updatedNewItem = this.clearSavedSignatureEnergy(newItem);
                hotbar.setItemStackForSlot((short) currentSlot, updatedNewItem);
                this.debug("Cleared saved energy from new weapon metadata");
                
                // Schedule the restore with a delay to ensure the game's reset has completed
                final World world = ((EntityStore) store.getExternalData()).getWorld();
                final float energyToRestore = savedEnergy;
                final long delayMs = this.config.getRestoreDelayMs();
                
                this.debug(String.format("Scheduling restore of %.1f energy in %dms", energyToRestore, delayMs));
                
                this.scheduler.schedule(() -> {
                    this.debug(String.format("Scheduler fired - about to restore %.1f", energyToRestore));
                    // Execute on the world thread for proper sync
                    world.execute(() -> {
                        this.debug(String.format("world.execute() running - restoring %.1f", energyToRestore));
                        this.setSignatureEnergy(entityRef, store, energyToRestore);
                        this.debug(String.format("RESTORED SignatureEnergy %.1f for weapon in slot %d (after %dms delay)",
                                energyToRestore, currentSlot, delayMs));
                    });
                }, delayMs, TimeUnit.MILLISECONDS);
            } else {
                this.debug("No saved energy to restore (null or 0)");
            }
        } else {
            this.debug("New item is not a weapon - no restore needed");
        }
    }
    
    /**
     * Check if an item is a weapon that can have SignatureEnergy.
     */
    private boolean isWeapon(@Nullable final ItemStack item) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        // Weapons have the getWeapon() property set
        return item.getItem().getWeapon() != null;
    }
    
    /**
     * Clean up tracking data when a player disconnects.
     */
    public void cleanupPlayer(@Nonnull final UUID playerUuid) {
        this.lastActiveSlot.remove(playerUuid);
        this.previousTickEnergy.remove(playerUuid);
    }
    
    // ==================== SIGNATURE ENERGY HELPERS ====================
    
    /**
     * Get the current SignatureEnergy value for an entity.
     */
    @SuppressWarnings("unchecked")
    private float getSignatureEnergy(
            @Nonnull final Ref<EntityStore> entityRef,
            @Nonnull final Store<EntityStore> store
    ) {
        final int signatureEnergyIndex = EntityStatType.getAssetMap().getIndex("SignatureEnergy");
        if (signatureEnergyIndex == Integer.MIN_VALUE) {
            return 0f;
        }
        
        final EntityStatMap statMap = (EntityStatMap) store.getComponent(
                (Ref) entityRef,
                EntityStatMap.getComponentType()
        );
        
        if (statMap == null) {
            return 0f;
        }
        
        final var statValue = statMap.get(signatureEnergyIndex);
        return statValue != null ? statValue.get() : 0f;
    }
    
    /**
     * Set the SignatureEnergy value for an entity.
     */
    @SuppressWarnings("unchecked")
    private void setSignatureEnergy(
            @Nonnull final Ref<EntityStore> entityRef,
            @Nonnull final Store<EntityStore> store,
            final float value
    ) {
        final int signatureEnergyIndex = EntityStatType.getAssetMap().getIndex("SignatureEnergy");
        if (signatureEnergyIndex == Integer.MIN_VALUE) {
            this.debug("setSignatureEnergy FAILED: SignatureEnergy stat not found!");
            return;
        }
        
        if (!entityRef.isValid()) {
            this.debug("setSignatureEnergy FAILED: entityRef is no longer valid!");
            return;
        }
        
        final EntityStatMap statMap = (EntityStatMap) store.getComponent(
                (Ref) entityRef,
                EntityStatMap.getComponentType()
        );
        
        if (statMap == null) {
            this.debug("setSignatureEnergy FAILED: statMap is null!");
            return;
        }
        
        statMap.setStatValue(signatureEnergyIndex, value);
        
        // Verify it was set
        if (this.config.isDebug()) {
            final var verify = statMap.get(signatureEnergyIndex);
            final float verifyValue = verify != null ? verify.get() : -1f;
            this.debug(String.format("setSignatureEnergy: set %.1f, verify read back: %.1f", value, verifyValue));
        }
    }
    
    // ==================== ITEM METADATA HELPERS ====================
    
    /**
     * Save SignatureEnergy to an item's metadata.
     */
    @Nonnull
    private ItemStack saveSignatureEnergy(@Nonnull final ItemStack item, final float energy) {
        return item.withMetadata(META_KEY_SIGNATURE_ENERGY, Codec.FLOAT, energy);
    }
    
    /**
     * Get the saved SignatureEnergy from an item's metadata.
     */
    @Nullable
    private Float getSavedSignatureEnergy(@Nonnull final ItemStack item) {
        return (Float) item.getFromMetadataOrNull(META_KEY_SIGNATURE_ENERGY, Codec.FLOAT);
    }
    
    /**
     * Clear saved SignatureEnergy from an item's metadata.
     */
    @Nonnull
    private ItemStack clearSavedSignatureEnergy(@Nonnull final ItemStack item) {
        return item.withMetadata(META_KEY_SIGNATURE_ENERGY, Codec.FLOAT, 0f);
    }
}
