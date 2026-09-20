package dev.ivoshow.chunkfarm.farm;

import dev.ivoshow.chunkfarm.crop.CropType;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/** Per-farm configuration: which crops, which layers, and what to do when full. */
public final class FarmSettings {

    private final EnumSet<CropType> enabled = EnumSet.noneOf(CropType.class);
    private int minY;
    private int maxY;
    private boolean autoReplant = true;
    private boolean active = true;
    private OverflowPolicy overflow = OverflowPolicy.PAUSE;
    private DeliveryMode delivery = DeliveryMode.WAREHOUSE;

    public FarmSettings(int minY, int maxY, Collection<CropType> enabled) {
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);
        this.enabled.addAll(enabled);
    }

    public boolean isEnabled(CropType type) {
        return this.enabled.contains(type);
    }

    /** @return the new state */
    public boolean toggle(CropType type) {
        if (this.enabled.remove(type)) {
            return false;
        }
        this.enabled.add(type);
        return true;
    }

    public void setEnabled(CropType type, boolean value) {
        if (value) {
            this.enabled.add(type);
        } else {
            this.enabled.remove(type);
        }
    }

    public Set<CropType> enabledCrops() {
        return EnumSet.copyOf(this.enabled);
    }

    public boolean hasAnyCrop() {
        return !this.enabled.isEmpty();
    }

    public int minY() {
        return this.minY;
    }

    public int maxY() {
        return this.maxY;
    }

    public int layerCount() {
        return this.maxY - this.minY + 1;
    }

    /** Callers are responsible for clamping to the world and to the configured max span. */
    public void setBounds(int minY, int maxY) {
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);
    }

    public boolean autoReplant() {
        return this.autoReplant;
    }

    public void setAutoReplant(boolean autoReplant) {
        this.autoReplant = autoReplant;
    }

    public boolean active() {
        return this.active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public DeliveryMode delivery() {
        return this.delivery;
    }

    public void setDelivery(DeliveryMode delivery) {
        this.delivery = delivery;
    }

    /** True when the overflow policy is relevant at all. */
    public boolean usesWarehouse() {
        return this.delivery == DeliveryMode.WAREHOUSE;
    }

    public OverflowPolicy overflow() {
        return this.overflow;
    }

    public void setOverflow(OverflowPolicy overflow) {
        this.overflow = overflow;
    }
}
