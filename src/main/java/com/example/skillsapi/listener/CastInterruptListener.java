package com.example.skillsapi.listener;

import com.example.skillsapi.skill.CastManager;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Taking damage cancels whatever you're channeling, provided the skill was
 * marked interruptible (the default). A boss's hyperarmor ultimate opts out
 * with {@code interruptible: false} in skills.yml - no code change needed.
 *
 * Runs at MONITOR so it only reacts to damage that actually went through
 * (other plugins had their say on cancelling/reducing it first).
 */
public class CastInterruptListener implements Listener {

    private final CastManager castManager;

    public CastInterruptListener(CastManager castManager) {
        this.castManager = castManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getFinalDamage() <= 0) return;
        if (!(event.getEntity() instanceof LivingEntity entity)) return;
        castManager.interrupt(entity);
    }
}
