package com.example.skillsapi.effect;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import com.example.skillsapi.threat.ThreatManager;
import org.bukkit.entity.LivingEntity;

/**
 * The tank's dedicated "look at me" button: adds a large flat amount of
 * threat from the caster against every current target, independent of how
 * much (if any) damage this particular skill deals. That's the point -
 * ordinary attacks generate threat proportional to damage dealt (see
 * ThreatListener), which a lightly-armored damage-dealer will often out-pace
 * a tank on; `taunt` lets the tank guarantee top threat on demand regardless
 * of their own damage output.
 *
 * skills.yml:
 *   - type: taunt
 *     amount: 1000   # default 1000 - deliberately far above what a single
 *                     # hit's damage-based threat would ever add, so a taunt
 *                     # reliably overrides whatever a squishier damage-dealer
 *                     # has already built up
 *
 * Silently a no-op against a target with no ThreatManager-relevant use for
 * it (only Monster-type entities are ever consulted for target selection -
 * see ThreatListener) - harmless to include on a skill that also hits
 * players/non-Monster entities, it just never influences anything for them.
 */
public class TauntEffect implements SkillEffect {

    private final ThreatManager threatManager;
    private final double amount;

    public TauntEffect(ThreatManager threatManager, double amount) {
        this.threatManager = threatManager;
        this.amount = amount;
    }

    @Override
    public void apply(SkillContext context) {
        LivingEntity caster = context.getCaster();
        for (LivingEntity target : context.getTargets()) {
            threatManager.addThreat(target.getUniqueId(), caster.getUniqueId(), amount);
        }
    }
}
