package com.example.skillsapi.mob;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * Turns a MobTemplate into a real Bukkit entity at a location: vanilla spawn,
 * then display name/health/armor/equipment/move-speed/despawn overrides on
 * top, then hands it to MobInstanceManager to tag + start tracking (which is
 * what actually fires ON_SPAWN and schedules ON_TIMER bindings).
 */
public final class MobSpawner {

    private MobSpawner() {}

    public static LivingEntity spawn(MobTemplate template, Location location, MobInstanceManager instanceManager) {
        LivingEntity entity = (LivingEntity) location.getWorld().spawnEntity(location, template.getType());

        if (template.getDisplayName() != null && !template.getDisplayName().isEmpty()) {
            entity.setCustomName(template.getDisplayName());
            entity.setCustomNameVisible(true);
        }

        if (template.getHealth() > 0 && entity.getAttribute(Attribute.MAX_HEALTH) != null) {
            entity.getAttribute(Attribute.MAX_HEALTH).setBaseValue(template.getHealth());
            entity.setHealth(template.getHealth());
        }

        if (template.getArmor() > 0 && entity.getAttribute(Attribute.ARMOR) != null) {
            entity.getAttribute(Attribute.ARMOR).setBaseValue(template.getArmor());
        }

        if (entity.getAttribute(Attribute.MOVEMENT_SPEED) != null) {
            entity.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(
                    entity.getAttribute(Attribute.MOVEMENT_SPEED).getBaseValue() * template.getMoveSpeed());
        }

        entity.setRemoveWhenFarAway(template.isDespawn());
        entity.setPersistent(true); // survives a chunk unload regardless of despawn - despawn only controls the far-away vanilla check

        EntityEquipment equipment = entity.getEquipment();
        if (equipment != null) {
            for (Map.Entry<EquipmentSlot, Material> entry : template.getEquipment().entrySet()) {
                if (entry.getValue() == null) continue;
                ItemStack item = new ItemStack(entry.getValue());
                equipment.setItem(entry.getKey(), item);
                equipment.setDropChance(entry.getKey(), 0f);
            }
        }

        instanceManager.register(entity, template);
        return entity;
    }
}
