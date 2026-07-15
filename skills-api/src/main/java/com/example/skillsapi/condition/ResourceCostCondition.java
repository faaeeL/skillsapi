package com.example.skillsapi.condition;

import com.example.skillsapi.resource.ResourceManager;
import com.example.skillsapi.skill.Condition;
import com.example.skillsapi.skill.SkillContext;

/**
 * Passes only if the caster currently has enough of the given resource.
 * This only checks - pair it with a ResourceConsumeEffect to actually spend it,
 * so a skill that fails on other conditions never costs mana.
 */
public class ResourceCostCondition implements Condition {
    private final ResourceManager resourceManager;
    private final String type;
    private final double amount;

    public ResourceCostCondition(ResourceManager resourceManager, String type, double amount) {
        this.resourceManager = resourceManager;
        this.type = type;
        this.amount = amount;
    }

    @Override
    public boolean test(SkillContext context) {
        return resourceManager.has(context.getCaster(), type, amount);
    }
}
