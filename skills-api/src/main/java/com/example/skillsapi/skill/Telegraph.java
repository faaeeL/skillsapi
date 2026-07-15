package com.example.skillsapi.skill;

import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;

import java.util.List;

/**
 * The "tell" a channeled skill gives off while it winds up. Two independent
 * pieces, either/both of which can be used:
 *   - a looping particle/sound cue at the caster, repeated every
 *     {@code intervalTicks} for the duration of the cast (either particle or
 *     sound can be null if you only want one).
 *   - {@code onStart}: a list of effects fired *once*, the instant the
 *     windup begins - typically a single `sequence` effect so the windup
 *     itself can be a proper multi-stage animation (e.g. runes appearing
 *     one at a time) rather than just one particle repeating in place.
 * Either way, this is what gives an opponent (or the caster themself)
 * something real to read before the skill resolves, instead of it just
 * happening - that "you could have dodged that" feeling is the point. Since
 * onStart fires before the skill has actually resolved, keep it visual
 * (particles/shapes) rather than damage/heal - CastEngine runs it against a
 * self-only context, so a damage/heal effect placed here would land
 * immediately at windup start, defeating the point of telegraphing it.
 */
public record Telegraph(Particle particle, int particleCount, Sound sound, long intervalTicks,
                         List<SkillEffect> onStart) {

    public void play(LivingEntity caster) {
        if (particle != null) {
            caster.getWorld().spawnParticle(particle, caster.getLocation().add(0, 1, 0),
                    particleCount, 0.4, 0.6, 0.4, 0.01);
        }
        if (sound != null) {
            caster.getWorld().playSound(caster.getLocation(), sound, 1f, 1f);
        }
    }
}
