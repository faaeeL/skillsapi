package com.example.skillsapi.effect;

import com.example.skillsapi.deploy.DeployManager;
import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Looks up the caster's marker under `tag` (armed earlier by a `deploy`
 * effect - possibly from a completely different skill, as long as the tag
 * string matches) and, if one's still armed, consumes it: plays an
 * optional burst at its location and applies its own `effects:` list to
 * whatever's within `radius`. A `detonate` with nothing armed under that
 * tag just fizzles - no error, no effects fired, same convention as a
 * `single` targeter with nothing in range.
 *
 * skills.yml:
 *   - type: detonate
 *     tag: hound_mark
 *     radius: 4                  # who gets hit, centered on the marker's location
 *     particle: FLAME            # optional burst at the marker's location
 *     particle_count: 40
 *     effects:                   # required - applied to everything within radius
 *       - type: damage
 *         amount: 20
 */
public class DetonateEffect implements SkillEffect {

    private final DeployManager deployManager;
    private final String tag;
    private final double radius;
    private final Particle burstParticle;
    private final int burstCount;
    private final List<SkillEffect> effects;

    public DetonateEffect(DeployManager deployManager, String tag, double radius,
                           Particle burstParticle, int burstCount, List<SkillEffect> effects) {
        this.deployManager = deployManager;
        this.tag = tag;
        this.radius = radius;
        this.burstParticle = burstParticle;
        this.burstCount = burstCount;
        this.effects = effects;
    }

    @Override
    public void apply(SkillContext context) {
        LivingEntity caster = context.getCaster();
        Location location = deployManager.consume(caster, tag);
        if (location == null || location.getWorld() == null) return; // nothing armed under this tag - fizzle

        if (burstParticle != null) {
            location.getWorld().spawnParticle(burstParticle, location, burstCount, 0.3, 0.3, 0.3, 0.05);
        }

        List<LivingEntity> hits = location.getWorld().getNearbyLivingEntities(location, radius).stream()
                .filter(e -> !e.equals(caster))
                .collect(Collectors.toList());

        SkillContext hitContext = new SkillContext(caster, context.getSkill());
        hitContext.setTargets(hits);
        for (SkillEffect effect : effects) {
            effect.apply(hitContext);
        }
    }
}
