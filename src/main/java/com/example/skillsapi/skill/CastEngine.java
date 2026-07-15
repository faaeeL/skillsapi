package com.example.skillsapi.skill;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/**
 * Turns a Skill's definition into an actual cast attempt: instant resolution
 * for castTimeMillis == 0 (unchanged old behavior), or a tracked channel -
 * telegraph loop + delayed resolve, cancellable via CastManager - for
 * anything with a windup.
 *
 * Use this instead of calling Skill.cast() directly whenever you want cast
 * times, interrupts, and "already casting" handling for free. Skill.cast()
 * itself stays a plain synchronous resolve (see its javadoc), so any
 * existing direct caller - like the one in the README - keeps working
 * unchanged; it just won't get windups.
 */
public class CastEngine {

    private final Plugin plugin;
    private final CastManager castManager;

    public CastEngine(Plugin plugin, CastManager castManager) {
        this.plugin = plugin;
        this.castManager = castManager;
    }

    public CastAttemptResult attemptCast(Skill skill, LivingEntity caster) {
        if (castManager.isCasting(caster)) return CastAttemptResult.ALREADY_CASTING;
        if (skill.isOnCooldown(caster)) return CastAttemptResult.ON_COOLDOWN;
        if (!skill.testConditions(caster)) return CastAttemptResult.CONDITION_FAILED;

        if (skill.getCastTimeMillis() <= 0) {
            return skill.cast(caster) ? CastAttemptResult.RESOLVED_INSTANTLY : CastAttemptResult.NO_TARGET;
        }

        beginChannel(skill, caster);
        return CastAttemptResult.CHANNEL_STARTED;
    }

    private void beginChannel(Skill skill, LivingEntity caster) {
        Telegraph telegraph = skill.getTelegraph();
        BukkitTask telegraphTask = telegraph == null ? null : new BukkitRunnable() {
            @Override
            public void run() {
                if (!caster.isValid()) {
                    cancel();
                    return;
                }
                telegraph.play(caster);
            }
        }.runTaskTimer(plugin, 0L, Math.max(1L, telegraph.intervalTicks()));

        if (caster instanceof Player player) {
            player.sendMessage("Casting " + skill.getId() + "...");
        }

        // The windup's own one-time visual (e.g. a chained multi-stage
        // animation) - self-only context since the skill hasn't resolved
        // yet and there's no real target list to act on.
        if (telegraph != null && telegraph.onStart() != null && !telegraph.onStart().isEmpty()) {
            SkillContext telegraphContext = new SkillContext(caster, skill);
            telegraphContext.setTargets(List.of(caster));
            for (SkillEffect effect : telegraph.onStart()) {
                effect.apply(telegraphContext);
            }
        }

        BukkitTask resolveTask = new BukkitRunnable() {
            @Override
            public void run() {
                castManager.clear(caster);
                if (telegraphTask != null) telegraphTask.cancel();
                if (!caster.isValid()) return;

                boolean resolved = skill.cast(caster);
                if (!resolved && caster instanceof Player player) {
                    player.sendMessage(skill.getId() + " fizzled - no valid target.");
                }
            }
        }.runTaskLater(plugin, Math.max(1L, skill.getCastTimeMillis() / 50L));

        castManager.start(caster, new ActiveCast(
                skill,
                resolveTask,
                telegraphTask,
                skill.isInterruptible(),
                () -> {
                    if (caster instanceof Player player) {
                        player.sendMessage(skill.getId() + " was interrupted!");
                    }
                }
        ));
    }
}
