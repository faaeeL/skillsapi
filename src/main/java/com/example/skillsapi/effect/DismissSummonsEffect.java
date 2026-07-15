package com.example.skillsapi.effect;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import com.example.skillsapi.summon.SummonManager;
import org.bukkit.entity.LivingEntity;

/**
 * Dismisses every summon the caster currently has tracked (see
 * SummonManager.dismissAll) - the "recall" counterpart to `type: summon`.
 * Typically the sole effect on a low/no-cooldown utility skill (e.g.
 * `targeter: self`), so a necromancer can clear their board before
 * re-summoning instead of waiting out however long duration_ticks was.
 */
public class DismissSummonsEffect implements SkillEffect {

    private final SummonManager summonManager;

    public DismissSummonsEffect(SummonManager summonManager) {
        this.summonManager = summonManager;
    }

    @Override
    public void apply(SkillContext context) {
        LivingEntity caster = context.getCaster();
        summonManager.dismissAll(caster.getUniqueId());
    }
}
