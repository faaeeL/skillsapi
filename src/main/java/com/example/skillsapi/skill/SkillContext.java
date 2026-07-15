package com.example.skillsapi.skill;

import org.bukkit.entity.LivingEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a Targeter/Condition/Effect needs to do its job for a single cast.
 * Extend this (or stuff more into `data`) if your effects need extra context,
 * e.g. the item used to cast, a charge level, a combo counter, etc.
 */
public class SkillContext {
    private final LivingEntity caster;
    private final Skill skill;
    private List<LivingEntity> targets;
    private final Map<String, Object> data = new HashMap<>();

    public SkillContext(LivingEntity caster, Skill skill) {
        this.caster = caster;
        this.skill = skill;
    }

    public LivingEntity getCaster() { return caster; }
    public Skill getSkill() { return skill; }
    public List<LivingEntity> getTargets() { return targets; }
    public void setTargets(List<LivingEntity> targets) { this.targets = targets; }

    public void put(String key, Object value) { data.put(key, value); }
    public Object get(String key) { return data.get(key); }
}
