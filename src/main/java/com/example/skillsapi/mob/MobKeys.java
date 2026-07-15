package com.example.skillsapi.mob;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** Same idea as SummonKeys/SkillItemKeys - one shared source of truth for the tag(s) a spawned template mob carries. */
public final class MobKeys {

    private MobKeys() {}

    /** Which mob template this entity was spawned from, stored as a string id. */
    public static NamespacedKey templateId(Plugin plugin) {
        return new NamespacedKey(plugin, "mob_template_id");
    }
}
