package com.example.skillsapi.effect;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import org.bukkit.entity.LivingEntity;

public class DamageEffect implements SkillEffect {
    private final double amount;

    public DamageEffect(double amount) {
        this.amount = amount;
    }

    @Override
    public void apply(SkillContext context) {
        for (LivingEntity target : context.getTargets()) {
            target.damage(amount, context.getCaster());
        }
    }
}
