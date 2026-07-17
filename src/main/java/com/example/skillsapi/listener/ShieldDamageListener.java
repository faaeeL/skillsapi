package com.example.skillsapi.listener;

import com.example.skillsapi.status.ShieldStatusBehavior;
import com.example.skillsapi.status.StatusManager;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Map;

/**
 * Any entity with an active "shield" status has incoming damage drained
 * from its absorption pool here instead of applied normally. Runs at HIGH,
 * before CastInterruptListener's MONITOR pass, so a fully-absorbed hit
 * (zeroed out below) doesn't also interrupt whatever the shielded entity
 * is channeling.
 *
 * Whichever skill inflicted the shield status decides what "shield" means
 * via its own on_start/on_expire hooks in skills.yml (a barrier going up,
 * glass shattering when it breaks) - this listener only owns the number
 * going down.
 */
public class ShieldDamageListener implements Listener {

    private final StatusManager statusManager;

    public ShieldDamageListener(StatusManager statusManager) {
        this.statusManager = statusManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity)) return;
        double incoming = event.getFinalDamage();
        if (incoming <= 0) return;

        Map<String, Object> state = statusManager.getState(entity, "shield");
        if (state == null) return;

        Object stored = state.get(ShieldStatusBehavior.ABSORPTION_KEY);
        if (!(stored instanceof Double remaining) || remaining <= 0) return;

        if (incoming <= remaining) {
            state.put(ShieldStatusBehavior.ABSORPTION_KEY, remaining - incoming);
            event.setDamage(0);
        } else {
            double leftover = incoming - remaining;
            state.put(ShieldStatusBehavior.ABSORPTION_KEY, 0.0);
            event.setDamage(leftover);
            // Breaks it now rather than waiting for duration_ticks to run
            // out - fires on_expire (e.g. a shatter effect) immediately.
            statusManager.remove(entity, "shield");
        }
    }
}
