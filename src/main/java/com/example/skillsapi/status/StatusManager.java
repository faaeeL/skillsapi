package com.example.skillsapi.status;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two jobs:
 *   - a registry of named, reusable Status *definitions* (parsed from the
 *     top-level `statuses:` section in skills.yml, e.g. "frozen" - so any
 *     number of future ice skills can inflict the same definition by id
 *     instead of repeating its config everywhere), and
 *   - tracking the running *instances* of statuses currently active on
 *     specific entities, driving their per-tick behavior + effect hooks.
 */
public class StatusManager {

    private record Instance(Status status, BukkitTask task, Map<String, Object> state) {}

    private final Plugin plugin;
    private final Map<String, Status> definitions = new HashMap<>();
    private final Map<UUID, Map<String, Instance>> active = new ConcurrentHashMap<>();

    public StatusManager(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Registers a reusable status under an id, so `type: status, status: <id>` can reference it from any skill. */
    public void registerDefinition(String id, Status status) {
        definitions.put(id.toLowerCase(), status);
    }

    public Status getDefinition(String id) {
        return definitions.get(id.toLowerCase());
    }

    /** Wipes the registry so a reload can rebuild it from scratch. Doesn't touch currently-running instances. */
    public void clearDefinitions() {
        definitions.clear();
    }

    public boolean hasStatus(LivingEntity entity, String statusId) {
        Map<String, Instance> perEntity = active.get(entity.getUniqueId());
        return perEntity != null && perEntity.containsKey(statusId.toLowerCase());
    }

    /** Applies (or, if already active and refreshable, restarts) a status on an entity. */
    public void apply(LivingEntity entity, Status status) {
        String key = status.id().toLowerCase();
        Map<String, Instance> perEntity = active.computeIfAbsent(entity.getUniqueId(), id -> new ConcurrentHashMap<>());

        Instance existing = perEntity.remove(key);
        if (existing != null) {
            if (!status.refreshable()) {
                perEntity.put(key, existing); // put it back - we're not touching it
                return;
            }
            existing.task().cancel();
        }

        Map<String, Object> state = new HashMap<>();
        if (status.behavior() != null) status.behavior().onStart(entity, state);
        runEffects(status.onStart(), entity);

        BukkitTask task = new BukkitRunnable() {
            private int elapsed = 0;

            @Override
            public void run() {
                boolean expired = !entity.isValid()
                        || (status.durationTicks() >= 0 && elapsed >= status.durationTicks());
                if (expired) {
                    finish(entity, status, state);
                    cancel();
                    return;
                }

                if (status.behavior() != null) status.behavior().onTick(entity, elapsed, state);
                if (elapsed % status.tickIntervalTicks() == 0) {
                    runEffects(status.onTick(), entity);
                }
                elapsed++;
            }
        }.runTaskTimer(plugin, 0L, 1L);

        perEntity.put(key, new Instance(status, task, state));
    }

    /** Ends a status early (a cleanse effect, death, whatever) - runs its onExpire hooks same as a natural timeout would. No-op if not active. */
    public void remove(LivingEntity entity, String statusId) {
        Map<String, Instance> perEntity = active.get(entity.getUniqueId());
        if (perEntity == null) return;
        Instance instance = perEntity.remove(statusId.toLowerCase());
        if (instance == null) return;
        instance.task().cancel();
        finishWithoutRemoving(entity, instance.status(), instance.state());
    }

    private void finish(LivingEntity entity, Status status, Map<String, Object> state) {
        Map<String, Instance> perEntity = active.get(entity.getUniqueId());
        if (perEntity != null) perEntity.remove(status.id().toLowerCase());
        finishWithoutRemoving(entity, status, state);
    }

    private void finishWithoutRemoving(LivingEntity entity, Status status, Map<String, Object> state) {
        if (status.behavior() != null) status.behavior().onExpire(entity, state);
        if (entity.isValid()) runEffects(status.onExpire(), entity);
    }

    private void runEffects(List<SkillEffect> effects, LivingEntity entity) {
        if (effects == null || effects.isEmpty() || !entity.isValid()) return;
        // The affected entity is its own caster/target here, so a `shape`
        // with anchor: self (or a plain `particle`) centers on whoever the
        // status is actually on - not whoever originally cast the skill
        // that inflicted it (e.g. an ice bolt's caster vs. the target it froze).
        SkillContext context = new SkillContext(entity, null);
        context.setTargets(List.of(entity));
        for (SkillEffect effect : effects) {
            effect.apply(context);
        }
    }
}
