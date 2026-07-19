package com.example.skillsapi.deploy;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backs the `deploy` / `detonate` effect pair: `deploy` calls apply() to
 * arm a marker at some Location under a caster + tag, `detonate` later
 * calls consume() with the same caster + tag to look it up, fire its
 * payload, and remove it in one step - so a mark can only ever be
 * detonated once, and a second detonate with nothing armed just fizzles.
 *
 * This is what turns two otherwise-instant casts into "place it now, set
 * it off on a separate later cast" - a Hound left clinging to a wall
 * before you trigger it, an Asteroid called down and held overhead before
 * you drop it, Shield-style constructs that go up now and get dismissed on
 * command later. Neither `deploy` nor `detonate` care what the other
 * skill's payload looks like - only the tag has to match, so any pair of
 * skills that agree on a tag string can use this.
 *
 * Deliberately not entity-attached (no ArmorStand, no real Bukkit entity) -
 * just a Location remembered per caster+tag, same "simulate the position
 * directly" approach ProjectileEffect and RainEffect already take.
 */
public class DeployManager {

    private record Deployment(Location location, BukkitTask expiryTask) {}

    private final Map<UUID, Map<String, Deployment>> active = new ConcurrentHashMap<>();

    /**
     * Arms a marker at `location` under `caster` + `tag`. Replaces (and
     * cancels the timeout of) anything already armed under the same
     * caster + tag - a second `deploy` with the same tag re-arms rather
     * than stacking a second independent marker.
     *
     * `lifetimeTicks` <= 0 means it never expires on its own - it waits
     * for a `detonate` (or a plugin restart) indefinitely. Otherwise
     * `onExpire` fires once the timer runs out with nothing having
     * detonated it, e.g. to play a fizzle particle at the now-abandoned
     * spot.
     */
    public void deploy(LivingEntity caster, String tag, Location location, int lifetimeTicks,
                        Plugin plugin, Runnable onExpire) {
        Map<String, Deployment> perCaster = active.computeIfAbsent(caster.getUniqueId(), id -> new ConcurrentHashMap<>());

        Deployment existing = perCaster.remove(tag.toLowerCase());
        if (existing != null && existing.expiryTask() != null) existing.expiryTask().cancel();

        BukkitTask expiryTask = lifetimeTicks > 0
                ? plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    Map<String, Deployment> pc = active.get(caster.getUniqueId());
                    if (pc != null) pc.remove(tag.toLowerCase());
                    if (onExpire != null) onExpire.run();
                }, lifetimeTicks)
                : null;

        perCaster.put(tag.toLowerCase(), new Deployment(location.clone(), expiryTask));
    }

    public boolean hasDeployment(LivingEntity caster, String tag) {
        Map<String, Deployment> perCaster = active.get(caster.getUniqueId());
        return perCaster != null && perCaster.containsKey(tag.toLowerCase());
    }

    /** Removes and returns the deployed location (if any) - a `detonate` only ever fires once per `deploy`. Null if nothing's armed under this tag. */
    public Location consume(LivingEntity caster, String tag) {
        Map<String, Deployment> perCaster = active.get(caster.getUniqueId());
        if (perCaster == null) return null;
        Deployment deployment = perCaster.remove(tag.toLowerCase());
        if (deployment == null) return null;
        if (deployment.expiryTask() != null) deployment.expiryTask().cancel();
        return deployment.location();
    }

    /** Cancels and forgets a marker without running any detonate payload - a cleanse/dismiss case, mirrors StatusManager#remove. */
    public void cancel(LivingEntity caster, String tag) {
        Map<String, Deployment> perCaster = active.get(caster.getUniqueId());
        if (perCaster == null) return;
        Deployment deployment = perCaster.remove(tag.toLowerCase());
        if (deployment != null && deployment.expiryTask() != null) deployment.expiryTask().cancel();
    }
}
