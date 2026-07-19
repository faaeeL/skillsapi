package com.example.skillsapi.effect;

import com.example.skillsapi.deploy.DeployManager;
import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;

import java.util.List;

/**
 * Arms a marker now instead of resolving instantly - the "place it, trigger
 * it later" half of the deploy/detonate pair. See DeployManager's own doc
 * comment for the full picture; this effect just resolves *where* the
 * marker goes (self's own position, or a raytraced cursor point) and hands
 * that Location off to it.
 *
 * skills.yml:
 *   - type: deploy
 *     tag: hound_mark            # required - a later `detonate` references the same tag
 *     anchor: cursor              # self | cursor, default cursor
 *     range: 20                   # cursor only
 *     lifetime_ticks: 200         # default 200. 0 or negative = never expires on its own, waits for detonate indefinitely
 *     marker_particle: SOUL       # optional ambient loop at the marker while armed
 *     marker_interval_ticks: 10
 *     marker_count: 3
 *     on_deploy:                  # optional - any effect list, fires once immediately, targeting the caster
 *       - type: particle
 *         particle: SMOKE
 *         count: 10
 *
 * Re-deploying the same tag before it's detonated replaces the old marker
 * (and its timeout) rather than stacking a second one - see
 * DeployManager#deploy.
 */
public class DeployEffect implements SkillEffect {

    private final Plugin plugin;
    private final DeployManager deployManager;
    private final String tag;
    private final boolean anchorCursor;
    private final double range;
    private final int lifetimeTicks;
    private final Particle markerParticle;
    private final int markerIntervalTicks;
    private final int markerCount;
    private final List<SkillEffect> onDeploy;

    public DeployEffect(Plugin plugin, DeployManager deployManager, String tag, boolean anchorCursor, double range,
                         int lifetimeTicks, Particle markerParticle, int markerIntervalTicks, int markerCount,
                         List<SkillEffect> onDeploy) {
        this.plugin = plugin;
        this.deployManager = deployManager;
        this.tag = tag;
        this.anchorCursor = anchorCursor;
        this.range = range;
        this.lifetimeTicks = lifetimeTicks;
        this.markerParticle = markerParticle;
        this.markerIntervalTicks = Math.max(1, markerIntervalTicks);
        this.markerCount = markerCount;
        this.onDeploy = onDeploy;
    }

    @Override
    public void apply(SkillContext context) {
        LivingEntity caster = context.getCaster();
        Location location = anchorCursor ? raytraceCrosshair(caster) : caster.getLocation();

        deployManager.deploy(caster, tag, location, lifetimeTicks, plugin, () -> {
            // Timed out with nobody detonating it - a quiet fizzle burst so it doesn't just silently vanish.
            if (markerParticle != null && location.getWorld() != null) {
                location.getWorld().spawnParticle(markerParticle, location, markerCount * 2, 0.2, 0.2, 0.2, 0.02);
            }
        });

        if (onDeploy != null && !onDeploy.isEmpty()) {
            SkillContext deployContext = new SkillContext(caster, context.getSkill());
            deployContext.setTargets(List.of(caster));
            for (SkillEffect effect : onDeploy) {
                effect.apply(deployContext);
            }
        }

        if (markerParticle != null) {
            new BukkitRunnable() {
                private int elapsed = 0;

                @Override
                public void run() {
                    // hasDeployment goes false the instant a detonate consumes it (or the timeout above fires) -
                    // either way, stop looping the ambient marker right then rather than on our own separate clock.
                    if (!deployManager.hasDeployment(caster, tag) || (lifetimeTicks > 0 && elapsed >= lifetimeTicks)) {
                        cancel();
                        return;
                    }
                    if (location.getWorld() != null) {
                        location.getWorld().spawnParticle(markerParticle, location, markerCount, 0.1, 0.1, 0.1, 0);
                    }
                    elapsed += markerIntervalTicks;
                }
            }.runTaskTimer(plugin, 0L, markerIntervalTicks);
        }
    }

    private Location raytraceCrosshair(LivingEntity caster) {
        Location eye = caster.getEyeLocation();
        RayTraceResult hit = caster.getWorld().rayTraceBlocks(eye, eye.getDirection(), range,
                FluidCollisionMode.NEVER, true);
        if (hit != null && hit.getHitPosition() != null) {
            return hit.getHitPosition().toLocation(caster.getWorld());
        }
        return eye.clone().add(eye.getDirection().multiply(range));
    }
}
