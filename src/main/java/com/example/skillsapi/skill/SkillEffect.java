package com.example.skillsapi.skill;

/**
 * One "thing that happens" when a skill fires: damage, heal, potion effect,
 * particles, knockback, spawn a projectile, whatever. Skills are just an
 * ordered list of these, so combining behaviors is just adding another effect.
 */
public interface SkillEffect {
    void apply(SkillContext context);
}
