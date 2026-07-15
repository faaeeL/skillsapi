package com.example.skillsapi.parser;

import com.example.skillsapi.mob.MobSkillBinding;
import com.example.skillsapi.mob.MobTemplate;
import com.example.skillsapi.mob.MobTemplateManager;
import com.example.skillsapi.mob.Trigger;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads `mobs:` sections from mobs/*.yml into MobTemplateManager - same
 * "merge everything across every file into one registry, warn (don't fail)
 * on a duplicate id" shape as SkillConfigParser.loadStatuses/loadSkills.
 */
public final class MobConfigParser {

    private MobConfigParser() {}

    public static void loadMobs(ConfigurationSection mobsSection, MobTemplateManager mobTemplateManager,
                                 Plugin plugin, String sourceLabel) {
        if (mobsSection == null) return;

        for (String id : mobsSection.getKeys(false)) {
            ConfigurationSection section = mobsSection.getConfigurationSection(id);
            if (section == null) continue;

            if (mobTemplateManager.get(id).isPresent()) {
                plugin.getLogger().warning("[" + sourceLabel + "] mob template '" + id
                        + "' duplicates one already loaded from another file - the later one wins.");
            }
            try {
                mobTemplateManager.register(parseMob(id, section.getValues(false)));
            } catch (Exception e) {
                throw new IllegalArgumentException("[" + sourceLabel + "] mob '" + id + "': " + e.getMessage(), e);
            }
        }
    }

    private static MobTemplate parseMob(String id, Map<?, ?> raw) {
        Object typeRaw = raw.get("type");
        if (typeRaw == null) {
            throw new IllegalArgumentException("missing required 'type'");
        }
        EntityType type = EntityType.valueOf(typeRaw.toString().toUpperCase(Locale.ROOT));

        String displayName = raw.get("display_name") != null ? raw.get("display_name").toString() : null;
        double health = toDouble(raw.get("health"), -1);
        double armor = toDouble(raw.get("armor"), -1);
        double moveSpeed = toDouble(raw.get("move_speed"), 1.0);
        boolean despawn = toBool(raw.get("despawn"), false);

        Map<EquipmentSlot, Material> equipment = new EnumMap<>(EquipmentSlot.class);
        Object equipmentRaw = raw.get("equipment");
        if (equipmentRaw instanceof Map<?, ?> equipmentMap) {
            putSlot(equipment, EquipmentSlot.HAND, equipmentMap.get("main_hand"));
            putSlot(equipment, EquipmentSlot.OFF_HAND, equipmentMap.get("off_hand"));
            putSlot(equipment, EquipmentSlot.HEAD, equipmentMap.get("helmet"));
            putSlot(equipment, EquipmentSlot.CHEST, equipmentMap.get("chestplate"));
            putSlot(equipment, EquipmentSlot.LEGS, equipmentMap.get("leggings"));
            putSlot(equipment, EquipmentSlot.FEET, equipmentMap.get("boots"));
        }

        List<MobSkillBinding> skills = new ArrayList<>();
        Object skillsRaw = raw.get("skills");
        if (skillsRaw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> bindingRaw) {
                    skills.add(parseBinding(bindingRaw));
                }
            }
        }

        return new MobTemplate(id, type, displayName, health, equipment, armor, moveSpeed, despawn, skills);
    }

    private static void putSlot(Map<EquipmentSlot, Material> equipment, EquipmentSlot slot, Object materialRaw) {
        if (materialRaw == null) return;
        equipment.put(slot, Material.valueOf(materialRaw.toString().toUpperCase(Locale.ROOT)));
    }

    private static MobSkillBinding parseBinding(Map<?, ?> raw) {
        Object triggerRaw = raw.get("trigger");
        if (triggerRaw == null) {
            throw new IllegalArgumentException("a mob 'skills' entry needs a 'trigger'");
        }
        Trigger trigger = Trigger.valueOf(triggerRaw.toString().toUpperCase(Locale.ROOT));

        Object skillRaw = raw.get("skill");
        if (skillRaw == null) {
            throw new IllegalArgumentException("a mob 'skills' entry needs a 'skill' id");
        }

        return new MobSkillBinding(
                trigger,
                skillRaw.toString(),
                toDouble(raw.get("chance"), 1.0),
                toLong(raw.get("cooldown"), 0),
                toInt(raw.get("interval_ticks"), 100),
                toDouble(raw.get("health_percent"), 0.3)
        );
    }

    private static double toDouble(Object o, double def) {
        return o == null ? def : Double.parseDouble(o.toString());
    }

    private static int toInt(Object o, int def) {
        return o == null ? def : Integer.parseInt(o.toString());
    }

    private static long toLong(Object o, long def) {
        return o == null ? def : Long.parseLong(o.toString());
    }

    private static boolean toBool(Object o, boolean def) {
        return o == null ? def : Boolean.parseBoolean(o.toString());
    }
}
