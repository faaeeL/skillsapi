package com.example.skillsapi.skill;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class SkillManager {
    private final Map<String, Skill> skills = new HashMap<>();

    public void register(Skill skill) {
        skills.put(skill.getId().toLowerCase(), skill);
    }

    public Optional<Skill> get(String id) {
        return Optional.ofNullable(skills.get(id.toLowerCase()));
    }

    public Map<String, Skill> getAll() {
        return skills;
    }

    /** Wipes the registry so a reload can rebuild it from scratch (skills removed from config disappear too). */
    public void clear() {
        skills.clear();
    }
}
