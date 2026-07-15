package com.example.skillsapi.effect;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;

public class HealEffect implements SkillEffect {
    private final double amount;

    public HealEffect(double amount) {
        this.amount = amount;
    }

    @Override
    public void apply(SkillContext context) {
        for (LivingEntity target : context.getTargets()) {
            double max = target.getAttribute(Attribute.MAX_HEALTH).getValue();
            target.setHealth(Math.min(max, target.getHealth() + amount));
        }
    }
}
