package com.example.skillsapi.status;

import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

import java.util.Map;

/**
 * Locks an entity in place: velocity zeroed every tick, so knockback,
 * momentum, and gravity can't budge it while active. This class only owns
 * the "can't move" part - pair it with on_start/on_tick/on_expire particle
 * hooks in config (ice shard burst, drifting snow, a crack/shatter on
 * expire) for the visual side, same as any other status.
 */
public class FrozenStatusBehavior implements StatusBehavior {

    @Override
    public void onTick(LivingEntity entity, int elapsedTicks, Map<String, Object> state) {
        entity.setVelocity(new Vector(0, 0, 0));
    }
}
