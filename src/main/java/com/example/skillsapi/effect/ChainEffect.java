package com.example.skillsapi.effect;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fires `effects:` against the skill's current target(s), then repeatedly
 * jumps to the next-nearest living entity within `bounce_radius` of
 * whichever entity it just hit, up to `bounces` more times - a chain
 * lightning / ricochet pattern. Each entity can only be hit once per cast
 * (the chain can't double back on itself or ping-pong between two
 * entities forever). `falloff` (if set) multiplies `DamageEffect`'s
 * DAMAGE_SCALE_KEY a little further with each bounce, so a `damage` effect
 * in `effects:` hits softer the further the chain travels, without needing
 * a bespoke "scaled damage" effect of its own - any other effect type in
 * the list is unaffected by falloff, since there's no generic notion of
 * "amount" to scale for e.g. a status or particle burst.
 *
 * Deliberately does NOT do its own faction/allegiance filtering - see the
 * broader "no faction-aware targeting" gap noted when a sentry mob was
 * discussed; a chain fired near the caster's own summons will bounce to
 * them too, same caveat as `radius`/`cone` targeters already have.
 *
 * skills.yml:
 *   - type: chain
 *     bounces: 3              # how many *additional* jumps after the initial hit, default 3
 *     bounce_radius: 6         # how far to search for the next target from wherever the chain currently is, default 6
 *     falloff: 1.0               # damage multiplier per bounce - 1.0 (default) = no falloff, 0.8 = 20% less each jump
 *     delay_ticks: 2              # ticks between each bounce, default 0 (all bounces resolve on the same tick)
 *     include_caster: false        # can the chain bounce back and hit the caster themselves, default false
 *     effects:
 *       - type: damage
 *         amount: 8
 */
public class ChainEffect implements SkillEffect {

    private final Plugin plugin;
    private final int bounces;
    private final double bounceRadius;
    private final double falloff;
    private final int delayTicks;
    private final boolean includeCaster;
    private final List<SkillEffect> effects;

    public ChainEffect(Plugin plugin, int bounces, double bounceRadius, double falloff,
                        int delayTicks, boolean includeCaster, List<SkillEffect> effects) {
        this.plugin = plugin;
        this.bounces = Math.max(0, bounces);
        this.bounceRadius = bounceRadius;
        this.falloff = falloff;
        this.delayTicks = Math.max(0, delayTicks);
        this.includeCaster = includeCaster;
        this.effects = effects;
    }

    @Override
    public void apply(SkillContext context) {
        List<LivingEntity> initialTargets = context.getTargets();
        if (initialTargets == null || initialTargets.isEmpty()) return;

        // Only the first initial target actually starts a chain - if the
        // skill's own targeter already selected several entities (e.g.
        // `radius`), each of those would otherwise spawn its own
        // independent chain, which reads as one big simultaneous nova
        // rather than a single lightning bolt hopping between targets.
        LivingEntity first = initialTargets.get(0);
        Set<LivingEntity> alreadyHit = new HashSet<>();
        alreadyHit.add(first);
        if (!includeCaster) alreadyHit.add(context.getCaster());

        fireAt(context, first, 0, alreadyHit);
    }

    private void fireAt(SkillContext context, LivingEntity target, int bounceIndex, Set<LivingEntity> alreadyHit) {
        if (!target.isValid()) return;

        double scale = Math.pow(falloff, bounceIndex);
        SkillContext hitContext = new SkillContext(context.getCaster(), context.getSkill());
        hitContext.setTargets(List.of(target));
        hitContext.put(DamageEffect.DAMAGE_SCALE_KEY, scale);
        for (SkillEffect effect : effects) {
            effect.apply(hitContext);
        }

        if (bounceIndex >= bounces) return;

        LivingEntity next = findNextTarget(target, alreadyHit);
        if (next == null) return;
        alreadyHit.add(next);

        if (delayTicks <= 0) {
            fireAt(context, next, bounceIndex + 1, alreadyHit);
        } else {
            new BukkitRunnable() {
                @Override
                public void run() {
                    fireAt(context, next, bounceIndex + 1, alreadyHit);
                }
            }.runTaskLater(plugin, delayTicks);
        }
    }

    private LivingEntity findNextTarget(LivingEntity from, Set<LivingEntity> alreadyHit) {
        Location origin = from.getLocation();
        LivingEntity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (Entity nearby : from.getWorld().getNearbyEntities(origin, bounceRadius, bounceRadius, bounceRadius)) {
            if (!(nearby instanceof LivingEntity candidate)) continue;
            if (alreadyHit.contains(candidate)) continue;

            double distSq = candidate.getLocation().distanceSquared(origin);
            if (distSq <= bounceRadius * bounceRadius && distSq < nearestDistSq) {
                nearest = candidate;
                nearestDistSq = distSq;
            }
        }
        return nearest;
    }
}
