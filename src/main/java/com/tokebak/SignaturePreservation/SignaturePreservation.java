package com.tokebak.SignaturePreservation;

import com.hypixel.hytale.component.system.ISystem;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.Config;

import javax.annotation.Nonnull;

/**
 * Signature Preservation - A Hytale mod that preserves SignatureEnergy (Q meter)
 * when switching hotbar slots.
 * 
 * By default, Hytale resets SignatureEnergy when you change your active hotbar slot.
 * This mod saves the energy to the weapon's metadata when you swap away, and restores
 * it when you swap back, allowing you to maintain progress on multiple weapons.
 */
public class SignaturePreservation extends JavaPlugin {

    private final Config<SignaturePreservationConfig> config;

    public SignaturePreservation(@Nonnull final JavaPluginInit init) {
        super(init);
        this.config = this.withConfig("SignaturePreservationConfig", SignaturePreservationConfig.CODEC);
    }

    @Override
    protected void setup() {
        super.setup();

        // Save config to disk (creates default config file if it doesn't exist)
        this.config.save();

        final SignaturePreservationConfig cfg = this.config.get();

        // Register the SignatureEnergy preservation system
        final SignatureEnergyPreservationSystem system = new SignatureEnergyPreservationSystem(cfg);
        this.getEntityStoreRegistry().registerSystem(system);

        System.out.println("[SP] ========================================");
        System.out.println("[SP] Signature Preservation mod loaded!");
        System.out.println("[SP] Config: enabled=" + cfg.isEnabled() + ", debug=" + cfg.isDebug());
        System.out.println("[SP] ========================================");
    }
}
