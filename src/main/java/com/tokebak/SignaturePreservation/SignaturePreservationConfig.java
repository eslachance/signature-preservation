package com.tokebak.SignaturePreservation;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import javax.annotation.Nonnull;

/**
 * Configuration for the Signature Preservation mod.
 */
public class SignaturePreservationConfig {

    public static final BuilderCodec<SignaturePreservationConfig> CODEC = BuilderCodec
            .builder(SignaturePreservationConfig.class, SignaturePreservationConfig::new)
            .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (config, value) -> config.enabled = value,
                    config -> config.enabled)
            .add()
            .append(new KeyedCodec<>("Debug", Codec.BOOLEAN),
                    (config, value) -> config.debug = value,
                    config -> config.debug)
            .add()
            .append(new KeyedCodec<>("RestoreDelayMs", Codec.LONG),
                    (config, value) -> config.restoreDelayMs = value,
                    config -> config.restoreDelayMs)
            .add()
            .build();

    /**
     * Whether SignatureEnergy preservation is enabled.
     */
    private boolean enabled = true;

    /**
     * Whether to output debug logs.
     */
    private boolean debug = false;

    /**
     * Delay in milliseconds before restoring SignatureEnergy after a slot change.
     * This ensures the game's internal reset has completed first.
     */
    private long restoreDelayMs = 100L;

    public SignaturePreservationConfig() {
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDebug() {
        return this.debug;
    }

    public void setDebug(final boolean debug) {
        this.debug = debug;
    }

    public long getRestoreDelayMs() {
        return this.restoreDelayMs;
    }

    public void setRestoreDelayMs(final long restoreDelayMs) {
        this.restoreDelayMs = restoreDelayMs;
    }

    @Nonnull
    @Override
    public String toString() {
        return "SignaturePreservationConfig{" +
                "enabled=" + enabled +
                ", debug=" + debug +
                ", restoreDelayMs=" + restoreDelayMs +
                '}';
    }
}
