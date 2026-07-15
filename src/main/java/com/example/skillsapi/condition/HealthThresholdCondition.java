package com.example.skillsapi.condition;

import com.example.skillsapi.skill.Condition;
import com.example.skillsapi.skill.SkillContext;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;

/** Only fires if the caster's health is at or above the given fraction (0.0 - 1.0) of max health. */
public class HealthThresholdCondition implements Condition {
    private final double minHealthPercent;

    public HealthThresholdCondition(double minHealthPercent) {
        this.minHealthPercent = minHealthPercent;
    }

    @Override
    public boolean test(SkillContext context) {
        LivingEntity caster = context.getCaster();
        double percent = caster.getHealth() / caster.getAttribute(Attribute.MAX_HEALTH).getValue();
        return percent >= minHealthPercent;
    }
}
