package com.example.skillsapi.skill;

/**
 * A gate that must pass before the skill fires: health threshold, mana cost,
 * required item in hand, world/time check, class requirement, etc.
 */
public interface Condition {
    boolean test(SkillContext context);
}
