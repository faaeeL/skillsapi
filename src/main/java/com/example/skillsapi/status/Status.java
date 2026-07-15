package com.example.skillsapi.status;

import com.example.skillsapi.skill.SkillEffect;

import java.util.List;

/**
 * The *definition* of a status effect - dash, frozen, whatever comes next
 * (burning, marked, rooted...). {@link StatusManager} tracks the running
 * *instances* of these per entity; this is just the template.
 *
 * Two independent ways a status can do things, and you can use either or
 * both together:
 *   - {@code behavior}: Java-side continuous game logic that isn't just
 *     "play some particles" - locking movement, setting velocity every
 *     tick, whatever needs real code (see DashStatusBehavior/
 *     FrozenStatusBehavior). Nullable - a purely cosmetic status (a lingering
 *     particle aura with no gameplay effect) can leave this null.
 *   - {@code onStart}/{@code onTick}/{@code onExpire}: plain SkillEffect
 *     lists, fired once at the start, every {@code tickIntervalTicks} while
 *     active, and once when it ends. This is what actually solves "I want to
 *     stack particles freely" - any effect this framework already knows
 *     about (particle, shape, sequence, another status...) can go here,
 *     instead of being hardcoded fields on a dedicated effect class.
 */
public record Status(
        String id,
        int durationTicks,        // -1 = indefinite, lasts until StatusManager.remove() is called
        int tickIntervalTicks,
        boolean refreshable,       // reapplying while already active restarts it (default true)
        StatusBehavior behavior,   // nullable
        List<SkillEffect> onStart,
        List<SkillEffect> onTick,
        List<SkillEffect> onExpire
) {}
