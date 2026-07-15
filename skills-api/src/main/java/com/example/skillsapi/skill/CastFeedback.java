package com.example.skillsapi.skill;

import com.example.skillsapi.resource.ResourceManager;
import org.bukkit.entity.Player;

/**
 * Turns a {@link CastAttemptResult} into the same player-facing messages
 * regardless of what triggered the attempt. Originally lived inline in
 * CastCommand; pulled out once a second trigger (right-clicking a
 * skill-bound item, see SkillItemListener) needed to report identical
 * cooldown/cost/fizzle text without copy-pasting the switch.
 */
public final class CastFeedback {

    private CastFeedback() {}

    public static void send(Player player, Skill skill, CastAttemptResult result, ResourceManager resourceManager) {
        switch (result) {
            case RESOLVED_INSTANTLY -> {
                if (skill.getCostType() != null) {
                    double remaining = resourceManager.get(player, skill.getCostType());
                    double max = resourceManager.getMax(skill.getCostType());
                    player.sendMessage(skill.getId() + " cast! " + skill.getCostType() + ": "
                            + (int) remaining + "/" + (int) max);
                } else {
                    player.sendMessage(skill.getId() + " cast!");
                }
            }
            case CHANNEL_STARTED -> {
                // CastEngine already sent the "Casting..." message and will
                // send a follow-up (fizzle/interrupt/nothing on success) once
                // the windup resolves - nothing more to do here.
            }
            case ALREADY_CASTING -> player.sendMessage("You're already casting something.");
            case ON_COOLDOWN -> {
                long remaining = skill.getRemainingCooldownMillis(player) / 1000;
                player.sendMessage(skill.getId() + " is on cooldown for " + remaining + "s.");
            }
            case CONDITION_FAILED -> {
                boolean lacksResource = skill.getCostType() != null
                        && !resourceManager.has(player, skill.getCostType(), skill.getCostAmount());
                if (lacksResource) {
                    player.sendMessage("Not enough " + skill.getCostType() + " to cast " + skill.getId()
                            + " (need " + (int) skill.getCostAmount() + ").");
                } else {
                    player.sendMessage("Could not cast " + skill.getId() + " (a condition failed).");
                }
            }
            case NO_TARGET -> player.sendMessage("Could not cast " + skill.getId() + " (no valid target).");
        }
    }
}
