package com.example.skillsapi.effect;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Chains several stages of effects one after another instead of firing them
 * all at once - "play this animation, once it ends play the next one, ...,
 * and let the last stage actually deal the damage/heal". Each {@link Step}
 * is just a list of SkillEffects (typically one `shape` animation, optionally
 * alongside a `particle`/`damage`/`heal`) plus how long to wait *after*
 * firing that step before moving on to the next one.
 *
 * This is a plain SkillEffect, so it's usable anywhere the others are:
 * - a skill's top-level `effects:` list - fires once any cast_time/telegraph
 *   windup finishes ("outside the telegraph").
 * - a telegraph's `on_start:` list - fires once, right when the windup
 *   begins, so the windup itself can be a multi-stage evolving tell instead
 *   of a single looping particle ("inside the telegraph").
 * - nested inside a shape's `on_hit:` or a projectile's `effects:`.
 *
 * See SkillConfigParser#parseSequenceEffect for the YAML schema.
 */
public class SequenceEffect implements SkillEffect {

    /**
     * One stage of the chain. {@code delayTicksAfter} is how long to wait,
     * after this step's effects fire, before the next step fires - normally
     * derived automatically from a `shape` step's own duration_ticks (see
     * the parser), so "once that animation ends, the next one appears" just
     * falls out of the shape's existing duration instead of needing the
     * timing duplicated by hand.
     */
    public record Step(List<SkillEffect> effects, int delayTicksAfter) {}

    private final Plugin plugin;
    private final List<Step> steps;

    public SequenceEffect(Plugin plugin, List<Step> steps) {
        this.plugin = plugin;
        this.steps = steps;
    }

    @Override
    public void apply(SkillContext context) {
        runStep(context, 0);
    }

    private void runStep(SkillContext context, int index) {
        if (index >= steps.size()) return;
        if (context.getCaster() == null || !context.getCaster().isValid()) return;

        Step step = steps.get(index);
        for (SkillEffect effect : step.effects()) {
            effect.apply(context);
        }

        int next = index + 1;
        if (next >= steps.size()) return;

        long delay = Math.max(0, step.delayTicksAfter());
        if (delay <= 0) {
            runStep(context, next);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, () -> runStep(context, next), delay);
        }
    }
}
