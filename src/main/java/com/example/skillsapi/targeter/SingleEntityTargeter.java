package com.example.skillsapi.targeter;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.Targeter;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.List;

/** Whatever living entity the caster is looking at, up to `range` blocks away. */
public class SingleEntityTargeter implements Targeter {
    private final double range;

    public SingleEntityTargeter(double range) {
        this.range = range;
    }

    @Override
    public List<LivingEntity> getTargets(SkillContext context) {
        LivingEntity caster = context.getCaster();
        RayTraceResult result = caster.getWorld().rayTraceEntities(
                caster.getEyeLocation(),
                caster.getEyeLocation().getDirection(),
                range,
                entity -> entity instanceof LivingEntity && !entity.equals(caster)
        );

        List<LivingEntity> targets = new ArrayList<>();
        if (result != null && result.getHitEntity() instanceof LivingEntity target) {
            targets.add(target);
        }
        return targets;
    }
}
