package com.example.skillsapi.summon;

import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the mobs a `type: summon` effect has spawned: which owner they
 * belong to (enforcing a per-owner cap before a new one is even spawned),
 * and despawning them once their lifespan runs out, they're dismissed on
 * purpose, or room needs to be made for a new one. Doesn't touch combat AI
 * at all - see SummonAiTask (follow/aggro) and SummonTargetListener
 * ("don't attack your own summoner") for that half of things.
 */
public class SummonManager {

    public enum CapBehavior { REFUSE, DISMISS_OLDEST }

    private record Instance(BukkitTask lifespanTask, BukkitTask aiSchedule, SummonAiTask aiBehavior) {}

    private final Plugin plugin;
    // LinkedHashMap (not a plain Map) specifically so iteration order is
    // insertion order - DISMISS_OLDEST depends on the first key really being
    // the longest-standing summon.
    private final Map<UUID, LinkedHashMap<UUID, Instance>> activeByOwner = new ConcurrentHashMap<>();

    public SummonManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public int activeCount(UUID owner) {
        LinkedHashMap<UUID, Instance> perOwner = activeByOwner.get(owner);
        return perOwner == null ? 0 : perOwner.size();
    }

    /**
     * Called *before* spawning a new mob, not after: makes room (or refuses)
     * so the cap is honored before an entity even exists, instead of
     * spawning speculatively and having to undo it.
     * Returns true if it's fine to go ahead and spawn now.
     */
    public boolean makeRoom(UUID ownerId, int maxActive, CapBehavior onCapExceeded) {
        if (maxActive <= 0) return true; // 0 or negative = uncapped
        LinkedHashMap<UUID, Instance> perOwner = activeByOwner.get(ownerId);
        if (perOwner == null || perOwner.size() < maxActive) return true;
        if (onCapExceeded == CapBehavior.REFUSE) return false;

        UUID oldest = perOwner.keySet().iterator().next(); // insertion order - see the field comment
        dismiss(oldest, ownerId);
        return true;
    }

    /**
     * Starts tracking a freshly-spawned mob: tags it with its owner/skill,
     * schedules its despawn if durationTicks >= 0 (a negative value means
     * "lasts until it dies or is dismissed"), and - if it's a Mob capable of
     * having AI at all - starts its follow/aggro behavior.
     */
    public void register(LivingEntity owner, LivingEntity minion, String skillId, int durationTicks,
                          double aggroRadius, double followRadius, double moveSpeed, int aiIntervalTicks) {
        minion.getPersistentDataContainer().set(SummonKeys.owner(plugin), PersistentDataType.STRING, owner.getUniqueId().toString());
        minion.getPersistentDataContainer().set(SummonKeys.skillId(plugin), PersistentDataType.STRING, skillId);

        UUID ownerId = owner.getUniqueId();
        UUID minionId = minion.getUniqueId();

        BukkitTask lifespanTask = durationTicks >= 0
                ? plugin.getServer().getScheduler().runTaskLater(plugin, () -> dismiss(minionId, ownerId), durationTicks)
                : null;

        SummonAiTask aiBehavior = null;
        BukkitTask aiSchedule = null;
        if (minion instanceof Mob mob) {
            aiBehavior = new SummonAiTask(plugin, owner, mob, aggroRadius, followRadius, moveSpeed);
            aiSchedule = aiBehavior.start(plugin, aiIntervalTicks);
        }

        activeByOwner.computeIfAbsent(ownerId, id -> new LinkedHashMap<>()).put(minionId, new Instance(lifespanTask, aiSchedule, aiBehavior));
    }

    /**
     * Ends a summon early: its lifespan running out, an owner dismissing it
     * on purpose, or DISMISS_OLDEST making room for a new one. No-op if
     * it's already gone - e.g. it died naturally in combat and
     * onNaturalDeath already cleaned it up before this ever ran.
     */
    public void dismiss(UUID minionId, UUID ownerId) {
        LinkedHashMap<UUID, Instance> perOwner = activeByOwner.get(ownerId);
        if (perOwner == null) return;
        Instance instance = perOwner.remove(minionId);
        if (instance == null) return;
        if (perOwner.isEmpty()) activeByOwner.remove(ownerId);

        if (instance.lifespanTask() != null) instance.lifespanTask().cancel();
        if (instance.aiSchedule() != null) instance.aiSchedule().cancel();

        Entity entity = plugin.getServer().getEntity(minionId);
        if (entity instanceof LivingEntity minion && minion.isValid()) {
            minion.getWorld().spawnParticle(Particle.POOF, minion.getLocation().add(0, 1, 0), 12, 0.3, 0.4, 0.3, 0.02);
            minion.remove();
        }
    }

    /** All of one owner's currently-tracked summons dismissed at once - a "dismiss" skill/command, or cleanup on owner logout. */
    public void dismissAll(UUID ownerId) {
        LinkedHashMap<UUID, Instance> perOwner = activeByOwner.get(ownerId);
        if (perOwner == null) return;
        List<UUID> minionIds = new ArrayList<>(perOwner.keySet()); // copy - dismiss() mutates perOwner mid-loop otherwise
        for (UUID minionId : minionIds) {
            dismiss(minionId, ownerId);
        }
    }

    /**
     * Called from SummonTargetListener's EntityDeathEvent handler when a
     * tracked summon dies in ordinary combat (not via dismiss()) - pure
     * bookkeeping cleanup, since the entity is already gone there's nothing
     * left to remove()/poof.
     */
    public void onNaturalDeath(UUID ownerId, UUID minionId) {
        LinkedHashMap<UUID, Instance> perOwner = activeByOwner.get(ownerId);
        if (perOwner == null) return;
        Instance instance = perOwner.remove(minionId);
        if (instance == null) return;
        if (perOwner.isEmpty()) activeByOwner.remove(ownerId);
        if (instance.lifespanTask() != null) instance.lifespanTask().cancel();
        if (instance.aiSchedule() != null) instance.aiSchedule().cancel();
    }

    /**
     * Called from SummonTargetListener off an EntityDamageByEntityEvent the
     * moment the owner attacks something - gives every one of that owner's
     * currently-idle minions an immediate chance to jump in and help,
     * rather than relying solely on each minion's own periodic "nearest
     * hostile" scan, which has no idea what the owner is actually fighting
     * and previously meant a summon could go the whole fight ignoring the
     * one mob its owner was actually hitting. See SummonAiTask#tryAssist for
     * the per-minion range/already-fighting checks this defers to.
     */
    public void notifyOwnerAttacked(UUID ownerId, LivingEntity victim) {
        LinkedHashMap<UUID, Instance> perOwner = activeByOwner.get(ownerId);
        if (perOwner == null) return;
        for (Instance instance : perOwner.values()) {
            if (instance.aiBehavior() != null) {
                instance.aiBehavior().tryAssist(victim);
            }
        }
    }
}
