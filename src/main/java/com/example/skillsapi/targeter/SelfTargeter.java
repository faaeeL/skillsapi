package com.example.skillsapi.targeter;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.Targeter;
import org.bukkit.entity.LivingEntity;

import java.util.Collections;
import java.util.List;

public class SelfTargeter implements Targeter {
    @Override
    public List<LivingEntity> getTargets(SkillContext context) {
        return Collections.singletonList(context.getCaster());
    }
}
