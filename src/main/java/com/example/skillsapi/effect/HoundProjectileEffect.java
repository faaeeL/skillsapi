package com.example.skillsapi.effect;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A homing variant of ProjectileEffect - a "Hound"-style tracker rather
 * than a straight-line bolt. Locks the nearest living entity within
 * lockRadius of the caster at cast time (never re-targets mid-flight, same
 * mark the whole way, matching a Hound's single-target tag-and-chase
 * rather than a seeking swarm), then steers toward that mark's current
 * position every tick.
 *
 * Turning is rate-limited (turnDegreesPerTick) instead of snapping straight
 * at the mark - that's what keeps it dodgeable: a target that breaks line
 * of sight around a corner or cuts a sharp enough angle can still out-turn
 * it, the same way the README notes projectile in general is dodgeable
 * where a hitscan targeter never was. No mark in range at cast time falls
 * back to flying straight in the caster's look direction, same as a plain
 * projectile.
 *
 * Steering itself is a simple lerp-then-renormalize toward the mark's
 * direction each tick, not a true spherical slerp - close enough at the
 * small per-tick turn angles this is meant to be configured with (a few
 * degrees), and much cheaper to compute every tick for every in-flight hound.
 *
 * `count` > 1 releases a pack instead of a single hound: every one locks
 * the *same* mark (found once, up front - a pack doesn't split up onto
 * different targets), fanned out at launch across spread_degrees the same
 * way ProjectileEffect's volley is, then each independently steers and
 * converges on that shared mark from its own starting angle.
 */
public class HoundProjectileEffect implements SkillEffect {

    private final Plugin plugin;
    private final Particle trailParticle;
    private final double speedPerTick;
    private final double maxDistance;
    private final double hitRadius;
    private final double lockRadius;
    private final double turnDegreesPerTick;
    private final boolean collideWithBlocks;
    private final int count;
    private final double spreadDegrees;
    private final List<SkillEffect> onHitEffects;

    public HoundProjectileEffect(Plugin plugin, Particle trailParticle, double speedBlocksPerSecond,
                                  double maxDistance, double hitRadius, double lockRadius,
                                  double turnDegreesPerTick, boolean collideWithBlocks,
                                  int count, double spreadDegrees, List<SkillEffect> onHitEffects) {
        this.plugin = plugin;
        this.trailParticle = trailParticle;
        this.speedPerTick = speedBlocksPerSecond / 20.0;
        this.maxDistance = maxDistance;
        this.hitRadius = hitRadius;
        this.lockRadius = lockRadius;
        this.turnDegreesPerTick = turnDegreesPerTick;
        this.collideWithBlocks = collideWithBlocks;
        this.count = Math.max(1, count);
        this.spreadDegrees = spreadDegrees;
        this.onHitEffects = onHitEffects;
    }

    @Override
    public void apply(SkillContext context) {
        LivingEntity caster = context.getCaster();
        Location origin = caster.getEyeLocation();
        Vector forward = origin.getDirection().normalize();

        // One shared mark for the whole pack - found once, not re-picked per hound.
        LivingEntity mark = findMark(caster, origin);
        double maxTurnRadians = Math.toRadians(turnDegreesPerTick);

        for (int i = 0; i < count; i++) {
            double angleOffset = count == 1 ? 0 : (-spreadDegrees / 2.0) + (spreadDegrees * i / (count - 1));
            Vector heading = rotateAroundVerticalAxis(forward, angleOffset).multiply(speedPerTick);
            launch(caster, context, origin.clone(), heading, mark, maxTurnRadians);
        }
    }

    /** Rotates a direction vector around the world's vertical (Y) axis - a horizontal-only fan, pitch untouched. */
    private static Vector rotateAroundVerticalAxis(Vector direction, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double x = direction.getX() * cos - direction.getZ() * sin;
        double z = direction.getX() * sin + direction.getZ() * cos;
        return new Vector(x, direction.getY(), z);
    }

    private void launch(LivingEntity caster, SkillContext context, Location origin, Vector initialHeading,
                         LivingEntity mark, double maxTurnRadians) {
        new BukkitRunnable() {
            private final Location current = origin;
            private double travelled = 0;
            private final Set<LivingEntity> alreadyHit = new HashSet<>();
            private Vector heading = initialHeading;

            @Override
            public void run() {
                if (!caster.isValid() || travelled >= maxDistance) {
                    cancel();
                    return;
                }

                steerTowardMark();

                current.add(heading);
                travelled += heading.length();

                if (trailParticle != null) {
                    current.getWorld().spawnParticle(trailParticle, current, 2, 0.05, 0.05, 0.05, 0);
                }

                if (collideWithBlocks && current.getBlock().getType().isSolid()) {
                    cancel();
                    return;
                }

                List<LivingEntity> hits = current.getWorld()
                        .getNearbyLivingEntities(current, hitRadius).stream()
                        .filter(e -> !e.equals(caster) && !alreadyHit.contains(e))
                        .collect(Collectors.toList());

                if (!hits.isEmpty()) {
                    alreadyHit.addAll(hits);

                    SkillContext hitContext = new SkillContext(caster, context.getSkill());
                    hitContext.setTargets(hits);
                    for (SkillEffect effect : onHitEffects) {
                        effect.apply(hitContext);
                    }

                    // Single-target tracker: stops on its first real hit
                    // rather than piercing through, unlike ProjectileEffect.
                    cancel();
                }
            }

            /** Bends `heading` toward the mark's current position by at most maxTurnRadians this tick, keeping speed constant. */
            private void steerTowardMark() {
                if (mark == null || !mark.isValid() || mark.isDead()) return;

                Vector toMark = mark.getEyeLocation().toVector().subtract(current.toVector());
                if (toMark.lengthSquared() < 1e-6) return;
                toMark.normalize();

                Vector currentDir = heading.clone().normalize();
                double angleBetween = currentDir.angle(toMark);
                if (angleBetween < 1e-4) return;

                double t = Math.min(1.0, maxTurnRadians / angleBetween);
                Vector blended = currentDir.multiply(1 - t).add(toMark.multiply(t));
                if (blended.lengthSquared() < 1e-9) return; // directions cancelled out - keep last heading

                heading = blended.normalize().multiply(speedPerTick);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Nearest living entity (excluding the caster) within lockRadius of the cast origin - the mark for the whole flight/pack, never reacquired. */
    private LivingEntity findMark(LivingEntity caster, Location origin) {
        return origin.getWorld().getNearbyLivingEntities(origin, lockRadius).stream()
                .filter(e -> !e.equals(caster))
                .min((a, b) -> Double.compare(
                        a.getLocation().distanceSquared(origin),
                        b.getLocation().distanceSquared(origin)))
                .orElse(null);
    }
}
