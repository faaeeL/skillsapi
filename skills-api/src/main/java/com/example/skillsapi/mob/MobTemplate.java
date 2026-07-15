package com.example.skillsapi.mob;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;
import java.util.Map;

/**
 * One `mobs/*.yml` entry: what to spawn (type, equipment, health/armor) and
 * which skills to bind to which trigger. MobSpawner turns this into a real
 * entity; MobInstanceManager tracks it afterward and dispatches triggers.
 */
public class MobTemplate {

    private final String id;
    private final EntityType type;
    private final String displayName; // nullable
    private final double health; // <= 0 = leave at the entity type's vanilla default
    private final Map<EquipmentSlot, Material> equipment; // nullable entries skipped
    private final double armor; // <= 0 = no override
    private final double moveSpeed;
    private final boolean despawn;
    private final List<MobSkillBinding> skills;

    public MobTemplate(String id, EntityType type, String displayName, double health,
                        Map<EquipmentSlot, Material> equipment, double armor, double moveSpeed,
                        boolean despawn, List<MobSkillBinding> skills) {
        this.id = id;
        this.type = type;
        this.displayName = displayName;
        this.health = health;
        this.equipment = equipment;
        this.armor = armor;
        this.moveSpeed = moveSpeed;
        this.despawn = despawn;
        this.skills = skills;
    }

    public String getId() { return id; }
    public EntityType getType() { return type; }
    public String getDisplayName() { return displayName; }
    public double getHealth() { return health; }
    public Map<EquipmentSlot, Material> getEquipment() { return equipment; }
    public double getArmor() { return armor; }
    public double getMoveSpeed() { return moveSpeed; }
    public boolean isDespawn() { return despawn; }
    public List<MobSkillBinding> getSkills() { return skills; }
}
