package com.example.skillsapi.effect;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * `count` independent drops, each scattered to a random point within
 * `radius` of the anchor, spawned `height` blocks up, and falling straight
 * down on its own timer (a random stagger delay per drop, not all at once -
 * that's what actually reads as "rain" instead of "synchronized volley").
 * Each drop is its own miniature simulated projectile: own trail particle,
 * own block-collision check, own small hit_radius + on_hit payload, capped
 * to hit_once per entity like the other travelling effects.
 *
 * Deliberately simpler than `shape`/`projectile`'s anchor system: only
 * `self` (caster's own location) and `target` (crosshair, raytraced up to
 * `range`) are supported, resolved once at cast time - a rain effect is a
 * one-shot burst over a chosen spot, not something that needs to track a
 * moving anchor the way an ongoing beam or a self-following shape does.
 *
 * skills.yml:
 *   - type: rain
 *     anchor: target          # self | target, default target
 *     range: 20                # target only - max raytrace distance
 *     count: 12                 # how many drops, default 10
 *     radius: 4                 # horizontal scatter radius, default 4
 *     height: 12                # spawn height above the anchor, default 12
 *     speed: 20                  # blocks/sec fall speed, default 20
 *     gravity: true               # accelerate as it falls, default true
 *     collide_with_blocks: true    # stop at the ground, default true
 *     particle: DUST
 *     dust_color: {r: 120, g: 170, b: 255}
 *     dust_size: 1.0
 *     stagger_ticks: {min: 0, max: 20}   # random per-drop delay before it starts falling
 *     hit:
 *       radius: 1.0
 *       once: true
 *       effects:
 *         - type: damage
 *           amount: 6
 */
public class RainEffect implements SkillEffect {

    public enum Anchor { SELF, TARGET }

    private final Plugin plugin;
    private final Anchor anchor;
    private final double range;
    private final int count;
    private final double radius;
    private final double height;
    private final double speedBlocksPerSecond;
    private final boolean gravity;
    private final boolean collideWithBlocks;
    private final Particle trailParticle;
    private final Particle.DustOptions dustOptions;
    private final int staggerMinTicks;
    private final int staggerMaxTicks;
    private final double hitRadius;
    private final boolean hitOnce;
    private final List<SkillEffect> onHitEffects;

    public RainEffect(Plugin plugin, Anchor anchor, double range, int count, double radius, double height,
                       double speedBlocksPerSecond, boolean gravity, boolean collideWithBlocks,
                       Particle trailParticle, Color dustColor, float dustSize,
                       int staggerMinTicks, int staggerMaxTicks,
                       double hitRadius, boolean hitOnce, List<SkillEffect> onHitEffects) {
        this.plugin = plugin;
        this.anchor = anchor;
        this.range = range;
        this.count = count;
        this.radius = radius;
        this.height = height;
        this.speedBlocksPerSecond = speedBlocksPerSecond;
        this.gravity = gravity;
        this.collideWithBlocks = collideWithBlocks;
        this.trailParticle = trailParticle;
        this.dustOptions = (trailParticle == Particle.DUST && dustColor != null)
                ? new Particle.DustOptions(dustColor, dustSize) : null;
        this.staggerMinTicks = Math.max(0, staggerMinTicks);
        this.staggerMaxTicks = Math.max(this.staggerMinTicks, staggerMaxTicks);
        this.hitRadius = hitRadius;
        this.hitOnce = hitOnce;
        this.onHitEffects = onHitEffects;
    }

    @Override
    public void apply(SkillContext context) {
        LivingEntity caster = context.getCaster();
        Location origin = resolveOrigin(caster);
        if (origin == null) return; // target anchor with nothing in range - fizzle, same convention as `single`

        World world = origin.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int i = 0; i < count; i++) {
            // sqrt(random) rather than a plain random distance -> uniform
            // density across the disk's *area*, not bunched toward the
            // center the way a plain linear random radius would be.
            double angle = random.nextDouble(0, Math.PI * 2);
            double dist = Math.sqrt(random.nextDouble()) * radius;
            double dropX = origin.getX() + Math.cos(angle) * dist;
            double dropZ = origin.getZ() + Math.sin(angle) * dist;
            Location start = new Location(world, dropX, origin.getY() + height, dropZ);

            int delay = staggerMaxTicks > staggerMinTicks
                    ? random.nextInt(staggerMinTicks, staggerMaxTicks + 1) : staggerMinTicks;

            plugin.getServer().getScheduler().runTaskLater(plugin, () -> spawnDrop(caster, context, start), delay);
        }
    }

    private void spawnDrop(LivingEntity caster, SkillContext context, Location start) {
        if (!caster.isValid()) return;

        new BukkitRunnable() {
            private final Location current = start.clone();
            private final Vector velocity = new Vector(0, -speedBlocksPerSecond / 20.0, 0);
            private final Set<LivingEntity> alreadyHit = hitOnce ? new HashSet<>() : null;
            private double fallen = 0;

            @Override
            public void run() {
                if (!caster.isValid()) {
                    cancel();
                    return;
                }

                double stepLength = velocity.length();
                if (collideWithBlocks && stepLength > 0) {
                    RayTraceResult hit = current.getWorld().rayTraceBlocks(
                            current, velocity.clone().normalize(), stepLength, FluidCollisionMode.NEVER, false);
                    if (hit != null) {
                        cancel();
                        return;
                    }
                }

                current.add(velocity);
                fallen += stepLength;
                if (gravity) velocity.setY(velocity.getY() - 0.02);

                if (trailParticle != null) {
                    if (dustOptions != null) {
                        current.getWorld().spawnParticle(trailParticle, current, 1, 0, 0, 0, 0, dustOptions);
                    } else {
                        current.getWorld().spawnParticle(trailParticle, current, 2, 0.05, 0.05, 0.05, 0);
                    }
                }

                // Sanity cap so a drop that somehow never collides (void,
                // a raised anchor with no ground under it, etc.) doesn't
                // fall forever - a few times the spawn height is generous
                // room for gravity-driven overshoot beyond a plain height/speed estimate.
                if (fallen > height * 4 + 64) {
                    cancel();
                    return;
                }

                List<LivingEntity> hits = current.getWorld().getNearbyLivingEntities(current, hitRadius).stream()
                        .filter(e -> !e.equals(caster) && (alreadyHit == null || !alreadyHit.contains(e)))
                        .collect(Collectors.toList());

                if (!hits.isEmpty()) {
                    if (alreadyHit != null) alreadyHit.addAll(hits);

                    SkillContext hitContext = new SkillContext(caster, context.getSkill());
                    hitContext.setTargets(hits);
                    for (SkillEffect effect : onHitEffects) {
                        effect.apply(hitContext);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private Location resolveOrigin(LivingEntity caster) {
        if (anchor == Anchor.SELF) {
            return caster.getLocation();
        }

        Location eye = caster.getEyeLocation();
        RayTraceResult hit = caster.getWorld().rayTraceBlocks(eye, eye.getDirection(), range,
                FluidCollisionMode.NEVER, true);
        if (hit != null && hit.getHitPosition() != null) {
            return hit.getHitPosition().toLocation(caster.getWorld());
        }
        return eye.clone().add(eye.getDirection().multiply(range));
    }
}
