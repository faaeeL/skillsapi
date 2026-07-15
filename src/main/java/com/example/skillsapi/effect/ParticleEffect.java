package com.example.skillsapi.effect;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;

public class ParticleEffect implements SkillEffect {
    private final Particle particle;
    private final int count;
    private final Particle.DustOptions dustOptions; // non-null only when particle == Particle.DUST and a color was configured

    public ParticleEffect(Particle particle, int count) {
        this(particle, count, null, 1.0f);
    }

    public ParticleEffect(Particle particle, int count, Color dustColor, float dustSize) {
        this.particle = particle;
        this.count = count;
        this.dustOptions = (particle == Particle.DUST && dustColor != null)
                ? new Particle.DustOptions(dustColor, dustSize)
                : null;
    }

    @Override
    public void apply(SkillContext context) {
        for (LivingEntity target : context.getTargets()) {
            Location loc = target.getLocation().add(0, 1, 0);
            if (dustOptions != null) {
                target.getWorld().spawnParticle(particle, loc, count, 0.3, 0.3, 0.3, 0, dustOptions);
            } else {
                target.getWorld().spawnParticle(particle, loc, count, 0.3, 0.3, 0.3);
            }
        }
    }
}
