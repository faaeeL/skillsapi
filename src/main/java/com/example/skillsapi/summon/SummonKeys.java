package com.example.skillsapi.summon;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * The tags a spawned mob carries so the rest of the plugin can recognize it
 * as "somebody's summon" later - when it tries to pick a target
 * (SummonTargetListener), when it dies (SummonManager's death cleanup), or
 * when an admin/other system needs to know whose it is. Same idea as
 * SkillItemKeys for bound items: one shared source of truth instead of every
 * reader/writer constructing their own NamespacedKey and risking a typo
 * silently desyncing them.
 */
public final class SummonKeys {

    private SummonKeys() {}

    /** The owner's UUID, stored as a string (PersistentDataType has no native UUID type). */
    public static NamespacedKey owner(Plugin plugin) {
        return new NamespacedKey(plugin, "summon_owner");
    }

    /** Which skill summoned this mob - mostly for debugging/admin tooling, not read by any gameplay logic yet. */
    public static NamespacedKey skillId(Plugin plugin) {
        return new NamespacedKey(plugin, "summon_skill_id");
    }
}
