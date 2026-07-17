package com.example.skillsapi.status;

import org.bukkit.entity.LivingEntity;

import java.util.Map;

/**
 * A shield status doesn't move anything, so it has no onTick logic of its
 * own - unlike dash/frozen. Its only job is seeding "absorption_remaining"
 * in the shared state map at cast time; the actual damage-blocking happens
 * in ShieldDamageListener, which reads and drains that same map through
 * StatusManager#getState every time the shielded entity takes damage.
 *
 * Splitting it this way (rather than putting the block logic here) is
 * necessary, not just style: StatusBehavior only gets called on a fixed
 * per-tick timer, but damage happens on its own schedule via
 * EntityDamageEvent, so something has to listen for that event instead.
 */
public class ShieldStatusBehavior implements StatusBehavior {

    public static final String ABSORPTION_KEY = "absorption_remaining";

    private final double absorption;

    public ShieldStatusBehavior(double absorption) {
        this.absorption = absorption;
    }

    @Override
    public void onStart(LivingEntity entity, Map<String, Object> state) {
        state.put(ABSORPTION_KEY, absorption);
    }
}
