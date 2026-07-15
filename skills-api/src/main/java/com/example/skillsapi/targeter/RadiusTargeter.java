package com.example.skillsapi.targeter;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.Targeter;
import org.bukkit.entity.LivingEntity;

import java.util.List;
import java.util.stream.Collectors;

/** Everyone within `radius` blocks of the caster. */
public class RadiusTargeter implements Targeter {
    private final double radius;
    private final boolean includeSelf;

    public RadiusTargeter(double radius, boolean includeSelf) {
        this.radius = radius;
        this.includeSelf = includeSelf;
    }

    @Override
    public List<LivingEntity> getTargets(SkillContext context) {
        LivingEntity caster = context.getCaster();
        return caster.getWorld().getNearbyLivingEntities(caster.getLocation(), radius).stream()
                .filter(e -> includeSelf || !e.equals(caster))
                .collect(Collectors.toList());
    }
}
