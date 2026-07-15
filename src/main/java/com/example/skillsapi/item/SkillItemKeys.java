package com.example.skillsapi.item;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * The single source of truth for the PersistentDataContainer keys an item
 * uses to remember its bound skills. BoundSkillList (writes/reads them) and
 * SkillItemListener (acts on them) both go through this instead of each
 * constructing their own NamespacedKey, so a typo in one place can't
 * silently desync them.
 *
 * An item carries an ordered rotation of any number of skills (boundSkillList
 * + boundSkillIndex) - right-click casts whichever one is currently
 * selected, shift+right-click advances the selection. The old two-fixed-
 * slot format (Slot.PRIMARY/SECONDARY, one skill per trigger) is kept here
 * purely so BoundSkillList can still read items bound before rotation
 * support existed - nothing writes those keys anymore.
 */
public final class SkillItemKeys {

    private SkillItemKeys() {}

    public enum Slot {
        /** Legacy-only: kept as the original "bound_skill" key name so pre-rotation binds can still be read. */
        PRIMARY("bound_skill", "Right-click"),
        /** Legacy-only: shift + right-click's old fixed second skill. */
        SECONDARY("bound_skill_secondary", "Shift+Right-click");

        private final String keyName;
        private final String triggerLabel;

        Slot(String keyName, String triggerLabel) {
            this.keyName = keyName;
            this.triggerLabel = triggerLabel;
        }

        public String triggerLabel() {
            return triggerLabel;
        }
    }

    public static NamespacedKey boundSkill(Plugin plugin, Slot slot) {
        return new NamespacedKey(plugin, slot.keyName);
    }

    /** Comma-joined skill ids making up the rotation - see BoundSkillList. */
    public static NamespacedKey boundSkillList(Plugin plugin) {
        return new NamespacedKey(plugin, "bound_skills");
    }

    /** Which index into boundSkillList is currently selected. */
    public static NamespacedKey boundSkillIndex(Plugin plugin) {
        return new NamespacedKey(plugin, "bound_skill_index");
    }
}
