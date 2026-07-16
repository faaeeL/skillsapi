package com.example.skillsapi.effect;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import org.bukkit.entity.LivingEntity;

public class DamageEffect implements SkillEffect {

    // Optional multiplier another effect can stash on the SkillContext
    // before this one runs - e.g. `chain`'s falloff, reducing damage a
    // little more with each bounce. Not set by anything by default, so
    // ordinary damage effects are entirely unaffected; this is just a hook
    // for effects that wrap/chain others to influence without needing a
    // bespoke "scaled damage" effect type of their own.
    public static final String DAMAGE_SCALE_KEY = "damage_scale_multiplier";

    private final double amount;

    public DamageEffect(double amount) {
        this.amount = amount;
    }

    @Override
    public void apply(SkillContext context) {
        Object scaleRaw = context.get(DAMAGE_SCALE_KEY);
        double scale = scaleRaw instanceof Number n ? n.doubleValue() : 1.0;
        for (LivingEntity target : context.getTargets()) {
            target.damage(amount * scale, context.getCaster());
        }
    }
}
