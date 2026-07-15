package com.example.skillsapi.skill;

import org.bukkit.entity.LivingEntity;

import java.util.List;

/**
 * Decides who a skill affects: self, single target, radius (AOE), cone, etc.
 * Runs once per cast, result gets stored on the SkillContext for every effect to use.
 */
public interface Targeter {
    List<LivingEntity> getTargets(SkillContext context);
}
