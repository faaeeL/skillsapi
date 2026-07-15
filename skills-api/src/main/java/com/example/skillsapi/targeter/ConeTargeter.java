package com.example.skillsapi.targeter;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.Targeter;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.stream.Collectors;

/** Everyone within `range` blocks and inside a cone of `angleDegrees` in front of the caster. */
public class ConeTargeter implements Targeter {
    private final double range;
    private final double angleDegrees;

    public ConeTargeter(double range, double angleDegrees) {
        this.range = range;
        this.angleDegrees = angleDegrees;
    }

    @Override
    public List<LivingEntity> getTargets(SkillContext context) {
        LivingEntity caster = context.getCaster();
        Vector facing = caster.getLocation().getDirection().normalize();

        return caster.getWorld().getNearbyLivingEntities(caster.getLocation(), range).stream()
                .filter(e -> !e.equals(caster))
                .filter(e -> {
                    Vector toTarget = e.getLocation().toVector().subtract(caster.getLocation().toVector());
                    if (toTarget.lengthSquared() == 0) return false;
                    toTarget.normalize();
                    double angle = Math.toDegrees(Math.acos(facing.dot(toTarget)));
                    return angle <= angleDegrees / 2.0;
                })
                .collect(Collectors.toList());
    }
}
