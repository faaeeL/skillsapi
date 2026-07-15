package com.example.skillsapi.skill;

import org.bukkit.scheduler.BukkitTask;

/**
 * Bookkeeping for one in-progress channel: the scheduled task that will
 * resolve the skill, the (optional) scheduled task looping its telegraph,
 * whether taking damage should cancel it, and what to run if it does.
 */
public record ActiveCast(
        Skill skill,
        BukkitTask resolveTask,
        BukkitTask telegraphTask,
        boolean interruptible,
        Runnable onInterrupt
) {}
