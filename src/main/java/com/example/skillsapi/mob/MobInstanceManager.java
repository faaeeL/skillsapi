package com.example.skillsapi.mob;

import com.example.skillsapi.skill.Skill;
import com.example.skillsapi.skill.SkillManager;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One entry per currently-alive mob spawned from a template: which template
 * it came from, that template's ON_TIMER tasks, each binding's own
 * per-instance cooldown, and which ON_LOW_HEALTH bindings have already
 * fired (each one only ever fires once per mob's life).
 *
 * MobSpawner registers an instance right after spawning; MobTriggerListener
 * calls dispatch()/checkLowHealth() off the relevant Bukkit events; either a
 * natural death or a manual removal should call cleanup() so timer tasks
 * don't keep running against a dead entity.
 */
public class MobInstanceManager {

    private record Instance(
            MobTemplate template,
            List<BukkitTask> timerTasks,
            Map<MobSkillBinding, Long> lastCastTimes,
            Set<MobSkillBinding> lowHealthFired
    ) {}

    private final Plugin plugin;
    private final SkillManager skillManager;
    private final Map<UUID, Instance> active = new ConcurrentHashMap<>();

    public MobInstanceManager(Plugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
    }

    /** Starts tracking a freshly-spawned template mob: schedules its ON_TIMER bindings, then fires ON_SPAWN. */
    public void register(LivingEntity entity, MobTemplate template) {
        entity.getPersistentDataContainer().set(MobKeys.templateId(plugin), PersistentDataType.STRING, template.getId());

        Instance instance = new Instance(template, new java.util.ArrayList<>(), new HashMap<>(), new HashSet<>());
        active.put(entity.getUniqueId(), instance);

        for (MobSkillBinding binding : template.getSkills()) {
            if (binding.trigger() != Trigger.ON_TIMER) continue;
            int interval = Math.max(1, binding.intervalTicks());
            BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                if (!entity.isValid()) return;
                tryFire(entity, instance, binding);
            }, interval, interval);
            instance.timerTasks().add(task);
        }

        dispatch(Trigger.ON_SPAWN, entity);
    }

    /** Fires every binding on this entity's template matching `trigger` (chance + per-binding cooldown permitting). No-op if the entity isn't a tracked template mob. */
    public void dispatch(Trigger trigger, LivingEntity entity) {
        Instance instance = active.get(entity.getUniqueId());
        if (instance == null) return;

        for (MobSkillBinding binding : instance.template().getSkills()) {
            if (binding.trigger() != trigger) continue;
            tryFire(entity, instance, binding);
        }
    }

    /** ON_LOW_HEALTH check - call after any damage to a tracked entity. Fires at most once per binding per mob. */
    public void checkLowHealth(LivingEntity entity) {
        Instance instance = active.get(entity.getUniqueId());
        if (instance == null) return;

        double maxHealth = entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
                ? entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue() : entity.getHealth();
        if (maxHealth <= 0) return;
        double percent = entity.getHealth() / maxHealth;

        for (MobSkillBinding binding : instance.template().getSkills()) {
            if (binding.trigger() != Trigger.ON_LOW_HEALTH) continue;
            if (instance.lowHealthFired().contains(binding)) continue;
            if (percent > binding.healthPercent()) continue;

            instance.lowHealthFired().add(binding);
            tryFire(entity, instance, binding);
        }
    }

    /** Cancels this mob's ON_TIMER tasks and stops tracking it - call on death or any other removal. */
    public void cleanup(UUID entityId) {
        Instance instance = active.remove(entityId);
        if (instance == null) return;
        for (BukkitTask task : instance.timerTasks()) {
            task.cancel();
        }
    }

    public boolean isTracked(UUID entityId) {
        return active.containsKey(entityId);
    }

    private void tryFire(LivingEntity entity, Instance instance, MobSkillBinding binding) {
        if (binding.chance() < 1.0 && ThreadLocalRandom.current().nextDouble() >= binding.chance()) return;

        Long last = instance.lastCastTimes().get(binding);
        if (last != null && System.currentTimeMillis() - last < binding.cooldownMillis()) return;

        skillManager.get(binding.skillId()).ifPresent(skill -> {
            if (castAsMob(skill, entity)) {
                instance.lastCastTimes().put(binding, System.currentTimeMillis());
            }
        });
    }

    private boolean castAsMob(Skill skill, LivingEntity entity) {
        return entity.isValid() && skill.cast(entity);
    }
}
