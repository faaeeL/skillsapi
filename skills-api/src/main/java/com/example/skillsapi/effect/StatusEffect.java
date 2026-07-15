package com.example.skillsapi.effect;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import com.example.skillsapi.status.Status;
import com.example.skillsapi.status.StatusManager;
import org.bukkit.entity.LivingEntity;

/**
 * Applies a Status to every entity in context.getTargets() - same pattern
 * as DamageEffect/ParticleEffect, so this works anywhere any other effect
 * does: a skill's own top-level `effects:`, a projectile's/shape's `on_hit`,
 * a sequence step, or even another status's own onStart/onTick/onExpire.
 */
public class StatusEffect implements SkillEffect {

    private final StatusManager statusManager;
    private final Status status;

    public StatusEffect(StatusManager statusManager, Status status) {
        this.statusManager = statusManager;
        this.status = status;
    }

    @Override
    public void apply(SkillContext context) {
        if (context.getTargets() == null) return;
        for (LivingEntity target : context.getTargets()) {
            statusManager.apply(target, status);
        }
    }
}
