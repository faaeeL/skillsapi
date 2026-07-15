package com.example.skillsapi.item;

import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads/writes an item's bound-skill rotation: an ordered list of skill ids
 * plus which one is currently selected. Right-click casts the selected one;
 * shift+right-click advances the selection instead of casting anything (see
 * SkillItemListener). Stored as a single comma-joined STRING (skill ids
 * can't contain commas - they're YAML map keys) plus an INTEGER index,
 * rather than PersistentDataType.LIST, to match the plain STRING-keyed
 * storage every other PDC tag in this plugin already uses.
 *
 * Falls back to the old two-fixed-slot format (SkillItemKeys.Slot.PRIMARY/
 * SECONDARY) for anything bound before rotation support existed, so
 * upgrading the plugin doesn't strand already-bound items. That fallback is
 * read-only: calling write() always saves in the new format and clears the
 * old keys, so an item only ever needs migrating once, the first time it's
 * touched by bind/unbind/a cycle.
 */
public final class BoundSkillList {

    private final List<String> skills;
    private final int selectedIndex;

    private BoundSkillList(List<String> skills, int selectedIndex) {
        this.skills = skills;
        this.selectedIndex = skills.isEmpty() ? 0 : Math.floorMod(selectedIndex, skills.size());
    }

    public static BoundSkillList empty() {
        return new BoundSkillList(new ArrayList<>(), 0);
    }

    public static BoundSkillList read(Plugin plugin, ItemMeta meta) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String joined = pdc.get(SkillItemKeys.boundSkillList(plugin), PersistentDataType.STRING);

        List<String> skills;
        if (joined != null) {
            skills = joined.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(joined.split(",")));
        } else {
            skills = new ArrayList<>();
            String primary = pdc.get(SkillItemKeys.boundSkill(plugin, SkillItemKeys.Slot.PRIMARY), PersistentDataType.STRING);
            String secondary = pdc.get(SkillItemKeys.boundSkill(plugin, SkillItemKeys.Slot.SECONDARY), PersistentDataType.STRING);
            if (primary != null) skills.add(primary);
            if (secondary != null && !secondary.equals(primary)) skills.add(secondary);
        }

        Integer index = pdc.get(SkillItemKeys.boundSkillIndex(plugin), PersistentDataType.INTEGER);
        return new BoundSkillList(skills, index != null ? index : 0);
    }

    public void write(Plugin plugin, ItemMeta meta) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(SkillItemKeys.boundSkillList(plugin), PersistentDataType.STRING, String.join(",", skills));
        pdc.set(SkillItemKeys.boundSkillIndex(plugin), PersistentDataType.INTEGER, selectedIndex);
        // Never written going forward, only ever read (see the class doc) -
        // clear them so a stale legacy value can't shadow the real list.
        pdc.remove(SkillItemKeys.boundSkill(plugin, SkillItemKeys.Slot.PRIMARY));
        pdc.remove(SkillItemKeys.boundSkill(plugin, SkillItemKeys.Slot.SECONDARY));
    }

    public boolean isEmpty() {
        return skills.isEmpty();
    }

    public List<String> skills() {
        return Collections.unmodifiableList(skills);
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    /** The currently selected skill id, or null if nothing's bound. */
    public String selected() {
        return skills.isEmpty() ? null : skills.get(selectedIndex);
    }

    public boolean contains(String skillId) {
        return skills.contains(skillId);
    }

    /** Appends a skill to the rotation. No-op (returns this unchanged) if it's already bound. */
    public BoundSkillList withAdded(String skillId) {
        if (skills.contains(skillId)) return this;
        List<String> updated = new ArrayList<>(skills);
        updated.add(skillId);
        return new BoundSkillList(updated, selectedIndex);
    }

    /** Removes one skill from the rotation and resets selection to the start - a stale numeric index could otherwise land on an unrelated skill once the list has shifted. */
    public BoundSkillList withRemoved(String skillId) {
        List<String> updated = new ArrayList<>(skills);
        updated.remove(skillId);
        return new BoundSkillList(updated, 0);
    }

    /** Advances to the next skill in the rotation, wrapping around. A single-skill (or empty) list is unaffected. */
    public BoundSkillList cycled() {
        return skills.isEmpty() ? this : new BoundSkillList(new ArrayList<>(skills), selectedIndex + 1);
    }
}
