package com.example.skillsapi.effect;

import com.example.skillsapi.resource.ResourceManager;
import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;

/** Deducts a resource cost from the caster. The parser puts this first in the effect list. */
public class ResourceConsumeEffect implements SkillEffect {
    private final ResourceManager resourceManager;
    private final String type;
    private final double amount;

    public ResourceConsumeEffect(ResourceManager resourceManager, String type, double amount) {
        this.resourceManager = resourceManager;
        this.type = type;
        this.amount = amount;
    }

    @Override
    public void apply(SkillContext context) {
        resourceManager.consume(context.getCaster(), type, amount);
    }
}
