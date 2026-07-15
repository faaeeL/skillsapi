package com.example.skillsapi.skill;

import org.bukkit.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks whatever a given entity is currently channeling, so an interrupt
 * source - taking damage today, but just as easily a movement check, a
 * silence effect, or anything else you add later - can cancel it without
 * needing to know which Skill is involved. CastEngine is the only thing
 * that starts/clears entries; everything else should just read via
 * isCasting/getActive or cancel via interrupt.
 */
public class CastManager {
    private final Map<UUID, ActiveCast> activeCasts = new ConcurrentHashMap<>();

    public boolean isCasting(LivingEntity caster) {
        return activeCasts.containsKey(caster.getUniqueId());
    }

    public ActiveCast getActive(LivingEntity caster) {
        return activeCasts.get(caster.getUniqueId());
    }

    void start(LivingEntity caster, ActiveCast cast) {
        activeCasts.put(caster.getUniqueId(), cast);
    }

    void clear(LivingEntity caster) {
        activeCasts.remove(caster.getUniqueId());
    }

    /**
     * Cancels whatever the entity is channeling, if anything, and if it's
     * flagged interruptible (skills default to interruptible; a boss's
     * hyperarmor ultimate can opt out via {@code interruptible: false}).
     * Returns true if something was actually cancelled.
     */
    public boolean interrupt(LivingEntity caster) {
        ActiveCast cast = activeCasts.get(caster.getUniqueId());
        if (cast == null || !cast.interruptible()) return false;

        cast.resolveTask().cancel();
        if (cast.telegraphTask() != null) cast.telegraphTask().cancel();
        activeCasts.remove(caster.getUniqueId());
        cast.onInterrupt().run();
        return true;
    }
}
