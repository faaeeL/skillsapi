package com.example.skillsapi.mob;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class MobTemplateManager {
    private final Map<String, MobTemplate> templates = new HashMap<>();

    public void register(MobTemplate template) {
        templates.put(template.getId().toLowerCase(), template);
    }

    public Optional<MobTemplate> get(String id) {
        return Optional.ofNullable(templates.get(id.toLowerCase()));
    }

    public Map<String, MobTemplate> getAll() {
        return templates;
    }

    /** Wipes the registry so a reload can rebuild it from scratch. */
    public void clear() {
        templates.clear();
    }
}
