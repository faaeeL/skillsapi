package com.example.skillsapi.effect;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class PotionEffectApply implements SkillEffect {
    private final PotionEffectType type;
    private final int durationTicks;
    private final int amplifier;

    public PotionEffectApply(PotionEffectType type, int durationTicks, int amplifier) {
        this.type = type;
        this.durationTicks = durationTicks;
        this.amplifier = amplifier;
    }

    @Override
    public void apply(SkillContext context) {
        for (LivingEntity target : context.getTargets()) {
            target.addPotionEffect(new PotionEffect(type, durationTicks, amplifier));
        }
    }
}
