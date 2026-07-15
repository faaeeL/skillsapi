package com.example.skillsapi.effect;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

public class KnockbackEffect implements SkillEffect {
    private final double strength;

    public KnockbackEffect(double strength) {
        this.strength = strength;
    }

    @Override
    public void apply(SkillContext context) {
        LivingEntity caster = context.getCaster();
        for (LivingEntity target : context.getTargets()) {
            if (target.equals(caster)) continue;
            Vector direction = target.getLocation().toVector()
                    .subtract(caster.getLocation().toVector())
                    .normalize()
                    .multiply(strength);
            direction.setY(Math.max(direction.getY(), 0.3));
            target.setVelocity(target.getVelocity().add(direction));
        }
    }
}
