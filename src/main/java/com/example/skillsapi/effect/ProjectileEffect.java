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
 * A simulated projectile: moves a point through the world tick by tick
 * (not a real Bukkit entity), tracing a particle trail as it goes. Because
 * it travels instead of resolving instantly, it can be seen coming and
 * dodged by just not being where it ends up - the point of picking this
 * over SingleEntityTargeter's instant hitscan.
 *
 * On hitting an entity (or a block, or running out of range) it hands off
 * to its own nested `effects:` list, applied to whatever it hit. That list
 * is just an ordinary SkillEffect list - parsed the same way a skill's own
 * top-level effects are - so a projectile's payload can be damage +
 * knockback + particles, or even another projectile, or anything else this
 * framework already knows how to do.
 *
 * `count` > 1 fires a volley instead of a single bolt: each one is its own
 * fully independent simulated point (own trail, own pierce budget, own
 * hits), fanned out horizontally across `spread_degrees` around the
 * caster's look direction rather than all overlapping on one line. `count`
 * projectiles evenly split that fan (count: 1 ignores spread_degrees
 * entirely and fires straight, same as before this field existed).
 */
public class ProjectileEffect implements SkillEffect {

    private final Plugin plugin;
    private final Particle trailParticle;
    private final double speedPerTick;
    private final double maxDistance;
    private final double hitRadius;
    private final int pierce;
    private final boolean gravity;
    private final boolean collideWithBlocks;
    private final int count;
    private final double spreadDegrees;
    private final List<SkillEffect> onHitEffects;

    public ProjectileEffect(Plugin plugin, Particle trailParticle, double speedBlocksPerSecond,
                             double maxDistance, double hitRadius, int pierce, boolean gravity,
                             boolean collideWithBlocks, int count, double spreadDegrees,
                             List<SkillEffect> onHitEffects) {
        this.plugin = plugin;
        this.trailParticle = trailParticle;
        // ticks are the unit everything actually moves in (20/sec)
        this.speedPerTick = speedBlocksPerSecond / 20.0;
        this.maxDistance = maxDistance;
        this.hitRadius = hitRadius;
        this.pierce = Math.max(1, pierce);
        this.gravity = gravity;
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

        for (int i = 0; i < count; i++) {
            // count: 1 -> angleOffset 0, straight down the look direction.
            // count: N>1 -> evenly spaced across spread_degrees, centered
            // on the look direction (e.g. 3 shots / 30 degrees = -15/0/+15).
            double angleOffset = count == 1 ? 0 : (-spreadDegrees / 2.0) + (spreadDegrees * i / (count - 1));
            Vector velocity = rotateAroundVerticalAxis(forward, angleOffset).multiply(speedPerTick);
            launch(caster, context, origin.clone(), velocity);
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

    private void launch(LivingEntity caster, SkillContext context, Location origin, Vector velocity) {
        new BukkitRunnable() {
            private final Location current = origin;
            private double travelled = 0;
            private final Set<LivingEntity> alreadyHit = new HashSet<>();
            private int hitsLeft = pierce;

            @Override
            public void run() {
                if (!caster.isValid() || travelled >= maxDistance || hitsLeft <= 0) {
                    cancel();
                    return;
                }

                current.add(velocity);
                travelled += velocity.length();
                if (gravity) velocity.setY(velocity.getY() - 0.02);

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
                    hitsLeft -= hits.size();

                    SkillContext hitContext = new SkillContext(caster, context.getSkill());
                    hitContext.setTargets(hits);
                    for (SkillEffect effect : onHitEffects) {
                        effect.apply(hitContext);
                    }

                    if (hitsLeft <= 0) {
                        cancel();
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
