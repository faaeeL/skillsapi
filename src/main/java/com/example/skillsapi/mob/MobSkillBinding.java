package com.example.skillsapi.mob;

/**
 * One `skills:` entry on a mob template - "when trigger fires, roll chance,
 * check this binding's own cooldown, then cast skillId with the mob as
 * caster." intervalTicks only means anything for ON_TIMER; healthPercent
 * only for ON_LOW_HEALTH.
 */
public record MobSkillBinding(
        Trigger trigger,
        String skillId,
        double chance,
        long cooldownMillis,
        int intervalTicks,
        double healthPercent
) {}
