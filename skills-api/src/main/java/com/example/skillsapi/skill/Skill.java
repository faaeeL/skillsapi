package com.example.skillsapi.skill;

import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A single skill: what it needs to fire (conditions), who it hits (targeter),
 * what it does (effects), and per-caster cooldown tracking.
 */
public class Skill {
    private final String id;
    private final Targeter targeter;
    private final List<Condition> conditions = new ArrayList<>();
    private final List<SkillEffect> effects = new ArrayList<>();
    private final long cooldownMillis;
    private final Map<UUID, Long> lastCastTimes = new HashMap<>();

    // Informational only (used for UI/messages) - the actual gating/spending
    // is done by ResourceCostCondition / ResourceConsumeEffect.
    private String costType;
    private double costAmount;

    // Channeling - all optional, default to the old instant-cast behavior
    // (castTimeMillis == 0 means "resolve immediately", exactly like before).
    private long castTimeMillis = 0;
    private boolean interruptible = true;
    private Telegraph telegraph;

    public Skill(String id, Targeter targeter, long cooldownMillis) {
        this.id = id;
        this.targeter = targeter;
        this.cooldownMillis = cooldownMillis;
    }

    public String getId() { return id; }
    public void addCondition(Condition condition) { conditions.add(condition); }
    public void addEffect(SkillEffect effect) { effects.add(effect); }
    public long getCooldownMillis() { return cooldownMillis; }

    public void setCost(String type, double amount) {
        this.costType = type;
        this.costAmount = amount;
    }

    public String getCostType() { return costType; }
    public double getCostAmount() { return costAmount; }

    public void setCastTime(long castTimeMillis) { this.castTimeMillis = castTimeMillis; }
    public long getCastTimeMillis() { return castTimeMillis; }

    public void setInterruptible(boolean interruptible) { this.interruptible = interruptible; }
    public boolean isInterruptible() { return interruptible; }

    public void setTelegraph(Telegraph telegraph) { this.telegraph = telegraph; }
    public Telegraph getTelegraph() { return telegraph; }

    public boolean isOnCooldown(LivingEntity caster) {
        Long last = lastCastTimes.get(caster.getUniqueId());
        return last != null && System.currentTimeMillis() - last < cooldownMillis;
    }

    public long getRemainingCooldownMillis(LivingEntity caster) {
        Long last = lastCastTimes.get(caster.getUniqueId());
        if (last == null) return 0;
        return Math.max(0, cooldownMillis - (System.currentTimeMillis() - last));
    }

    /**
     * Runs conditions only - no targeting, no effects, no cooldown stamp.
     * CastEngine uses this to decide whether a channel is even worth
     * starting before committing the caster to a windup they can't cancel.
     */
    public boolean testConditions(LivingEntity caster) {
        SkillContext context = new SkillContext(caster, this);
        for (Condition condition : conditions) {
            if (!condition.test(context)) return false;
        }
        return true;
    }

    /**
     * Resolves the skill right now: conditions, targeting, effects, cooldown.
     * Returns false if on cooldown, a condition failed, or the targeter
     * found nobody to hit - so callers can give feedback.
     *
     * For an instant skill (castTimeMillis == 0) this *is* the whole cast.
     * For a channeled skill, this is exactly what CastEngine calls once the
     * windup finishes, so a skill with a cast_time behaves identically to
     * one without - just later, and interruptibly.
     */
    public boolean cast(LivingEntity caster) {
        if (isOnCooldown(caster)) return false;

        SkillContext context = new SkillContext(caster, this);

        for (Condition condition : conditions) {
            if (!condition.test(context)) return false;
        }

        context.setTargets(targeter.getTargets(context));
        if (context.getTargets() == null || context.getTargets().isEmpty()) return false;

        for (SkillEffect effect : effects) {
            effect.apply(context);
        }

        lastCastTimes.put(caster.getUniqueId(), System.currentTimeMillis());
        return true;
    }
}
