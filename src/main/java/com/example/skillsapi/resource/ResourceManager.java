package com.example.skillsapi.resource;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks arbitrary named resource pools (mana, stamina, rage - whatever you
 * define in resources.yml) per living entity, with passive regen.
 *
 * In-memory only: values reset on restart. If you need persistence, the
 * simplest option is storing `get(entity, type)` in the entity's
 * PersistentDataContainer on PlayerQuitEvent and restoring it on join -
 * this class doesn't do that for you.
 */
public class ResourceManager {

    public record ResourceDefinition(double max, double regenPerSecond) {}

    private final Map<String, ResourceDefinition> definitions = new HashMap<>();
    // entity UUID -> resource type -> current amount
    private final Map<UUID, Map<String, Double>> current = new ConcurrentHashMap<>();

    public void defineResource(String type, double max, double regenPerSecond) {
        definitions.put(type.toLowerCase(), new ResourceDefinition(max, regenPerSecond));
    }

    /** Reads a `resources:` section like:
     *  resources:
     *    mana:
     *      max: 100
     *      regen_per_second: 1.0
     */
    public void loadFromConfig(ConfigurationSection section) {
        if (section == null) return;
        for (String type : section.getKeys(false)) {
            ConfigurationSection typeSection = section.getConfigurationSection(type);
            if (typeSection == null) continue;
            defineResource(
                    type,
                    typeSection.getDouble("max", 100),
                    typeSection.getDouble("regen_per_second", 0)
            );
        }
    }

    public double getMax(String type) {
        ResourceDefinition def = definitions.get(type.toLowerCase());
        return def == null ? 0 : def.max();
    }

    public double get(LivingEntity entity, String type) {
        return current
                .computeIfAbsent(entity.getUniqueId(), id -> new ConcurrentHashMap<>())
                .computeIfAbsent(type.toLowerCase(), this::getMax);
    }

    public void set(LivingEntity entity, String type, double amount) {
        double max = getMax(type);
        double clamped = Math.max(0, Math.min(max, amount));
        current.computeIfAbsent(entity.getUniqueId(), id -> new ConcurrentHashMap<>())
                .put(type.toLowerCase(), clamped);
    }

    public boolean has(LivingEntity entity, String type, double amount) {
        return get(entity, type) >= amount;
    }

    public void consume(LivingEntity entity, String type, double amount) {
        set(entity, type, get(entity, type) - amount);
    }

    public void restore(LivingEntity entity, String type, double amount) {
        set(entity, type, get(entity, type) + amount);
    }

    /** Call once from onEnable to start passive regen for every entity currently tracked. */
    public void startRegenTask(Plugin plugin) {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Map<String, Double> pools : current.values()) {
                    for (Map.Entry<String, Double> entry : pools.entrySet()) {
                        ResourceDefinition def = definitions.get(entry.getKey());
                        if (def == null || def.regenPerSecond() <= 0) continue;
                        entry.setValue(Math.min(def.max(), entry.getValue() + def.regenPerSecond()));
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L); // once per second
    }

    public void clear(LivingEntity entity) {
        current.remove(entity.getUniqueId());
    }
}
