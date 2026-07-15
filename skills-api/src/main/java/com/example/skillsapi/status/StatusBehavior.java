package com.example.skillsapi.status;

import org.bukkit.entity.LivingEntity;

import java.util.Map;

/**
 * Java-side continuous behavior for a Status, driven every tick by
 * StatusManager alongside its onStart/onTick/onExpire SkillEffect hooks.
 *
 * A single Status (and the StatusBehavior it holds) is a shared *definition*
 * reused by every entity/cast that triggers it - not a fresh instance per
 * use - so never stash per-application data in a field on the behavior
 * object itself (two entities with the status active at once would clobber
 * each other's state). {@code state} is a scratch map created fresh per
 * application for exactly this: put whatever this instance needs to
 * remember between calls in there instead.
 */
public interface StatusBehavior {
    default void onStart(LivingEntity entity, Map<String, Object> state) {}
    default void onTick(LivingEntity entity, int elapsedTicks, Map<String, Object> state) {}
    default void onExpire(LivingEntity entity, Map<String, Object> state) {}
}
