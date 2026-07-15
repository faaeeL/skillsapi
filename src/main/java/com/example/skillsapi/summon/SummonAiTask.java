package com.example.skillsapi.summon;

import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Comparator;

/**
 * The "acts like a loyal minion" behavior for a summoned Mob: every
 * intervalTicks, picks a fight with the nearest valid target within
 * aggroRadius if it isn't already fighting something legitimate, and
 * otherwise walks back toward its owner once it's wandered further than
 * followRadius away. SummonTargetListener's onTarget veto is the first line
 * of defense against a minion ever attacking its own owner/a sibling, but
 * that veto only fires for target changes that actually dispatch
 * EntityTargetLivingEntityEvent - which vanilla "hurt by" revenge AI can
 * bypass on some mobs/versions by setting a target straight through NMS.
 * {@link #isValidTarget} is this class's own backstop for that: every tick,
 * it re-checks whatever target the minion currently has (not just brand new
 * picks), and clears anything that shouldn't be there instead of trusting
 * "non-null and alive" to mean "legitimate."
 *
 * "Valid target" is any live, loaded LivingEntity that isn't a Player,
 * isn't an ArmorStand (Bukkit models those as LivingEntity for legacy
 * reasons, but they're scenery), isn't this minion's own owner, and isn't
 * itself somebody's tracked summon - not just Monster, so a minion will
 * happily pick a fight with a passive mob (a cow you're farming, say)
 * exactly as readily as a zombie. That last exclusion is regardless of
 * *whose* summon, not just this minion's own owner's - without it, two
 * players' minion armies (or two of the same player's) would turn on each
 * other as soon as one ran out of other things to fight, which reads as a
 * bug, not a feature.
 *
 * That periodic scan is autonomous, proximity-only behavior - it has no
 * idea what the owner themselves is doing in combat. {@link #tryAssist}
 * is the reactive counterpart, called by SummonTargetListener the instant
 * the owner attacks something, so a minion can jump in immediately instead
 * of waiting for its next scan to maybe stumble onto the same target.
 */
public class SummonAiTask extends BukkitRunnable {

    private final Plugin plugin;
    private final LivingEntity owner;
    private final Mob minion;
    private final double aggroRadius;
    private final double followRadius;
    private final double moveSpeed;

    public SummonAiTask(Plugin plugin, LivingEntity owner, Mob minion, double aggroRadius, double followRadius,
                         double moveSpeed) {
        this.plugin = plugin;
        this.owner = owner;
        this.minion = minion;
        this.aggroRadius = aggroRadius;
        this.followRadius = followRadius;
        this.moveSpeed = moveSpeed;
    }

    public BukkitTask start(Plugin plugin, int intervalTicks) {
        int interval = Math.max(1, intervalTicks);
        return runTaskTimer(plugin, interval, interval);
    }

    @Override
    public void run() {
        if (!minion.isValid() || !owner.isValid()) {
            cancel();
            return;
        }

        LivingEntity currentTarget = minion.getTarget();
        if (currentTarget != null) {
            if (currentTarget.isValid() && isValidTarget(currentTarget)) {
                return; // already fighting something legitimate - leave it alone
            }
            // Invalid (owner, a sibling/other summon, or just gone) - most
            // likely acquired through a path that never went through
            // SummonTargetListener's onTarget veto at all. Bukkit doesn't
            // reliably fire EntityTargetLivingEntityEvent for every way a
            // Mob's target can change - vanilla "hurt by" revenge AI in
            // particular can set one straight through NMS without ever
            // dispatching that event - so the veto alone can miss this.
            // Clearing it here is the periodic backstop: even if a bad
            // target slips through once, it only sticks until this task's
            // next tick instead of indefinitely, and clearing it (instead
            // of just returning) lets the scan below immediately try to
            // find this minion something legitimate to do this same tick.
            minion.setTarget(null);
        }

        if (aggroRadius > 0) {
            LivingEntity nearestTarget = minion.getWorld()
                    .getNearbyEntitiesByType(LivingEntity.class, minion.getLocation(), aggroRadius).stream()
                    .filter(LivingEntity::isValid)
                    .filter(this::isValidTarget)
                    .min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(minion.getLocation())))
                    .orElse(null);

            if (nearestTarget != null) {
                minion.setTarget(nearestTarget);
                return;
            }
        }

        if (followRadius > 0 && minion.getLocation().distanceSquared(owner.getLocation()) > followRadius * followRadius) {
            minion.getPathfinder().moveTo(owner.getLocation(), moveSpeed);
        }
    }

    /**
     * Anything this minion is allowed to fight: not a Player, not an
     * ArmorStand (Bukkit models those as LivingEntity for legacy reasons,
     * but they're scenery), not its own owner, and not itself somebody's
     * tracked summon - regardless of *whose* summon, not just this minion's
     * own owner's. Without that last check two players' minion armies (or
     * two of the same player's) would turn on each other the moment one ran
     * out of other things to fight, which reads as a bug, not a feature.
     * Shared between picking a brand new target (the scan below) and
     * re-validating whatever target this minion already has (run()'s top) -
     * a target that wouldn't have been picked fresh right now shouldn't
     * keep being fought just because it was acquired a moment ago.
     */
    private boolean isValidTarget(LivingEntity entity) {
        if (entity instanceof Player || entity instanceof ArmorStand) return false;
        if (entity.getUniqueId().equals(owner.getUniqueId())) return false;
        return !entity.getPersistentDataContainer().has(SummonKeys.owner(plugin), PersistentDataType.STRING);
    }

    /**
     * Called reactively (from SummonTargetListener, off an
     * EntityDamageByEntityEvent) the moment the owner attacks - or is
     * attacked by - something, instead of waiting for this task's own next
     * periodic scan to maybe notice. That periodic scan alone is why
     * summons previously ignored a mob their owner had just hit: it only
     * ever looks for the *nearest* hostile Monster within aggroRadius, which
     * has no relationship to whichever specific mob the owner is actually
     * fighting - a closer, unrelated monster could keep winning that check
     * forever, or the owner's target could simply be outside whatever radius
     * happened to be configured at the moment this last ran.
     *
     * Only takes over if this minion isn't already fighting something else
     * (an owner starting a second fight shouldn't yank a minion off a target
     * it's already committed to), and only within this minion's own
     * aggroRadius, so a minion left behind far away doesn't instantly
     * teleport-aggro across the map the moment its owner throws a punch
     * somewhere distant - "assist," not "omniscient bodyguard."
     */
    public void tryAssist(LivingEntity victim) {
        if (!minion.isValid() || !victim.isValid()) return;
        if (minion.getTarget() != null && minion.getTarget().isValid()) return;
        if (aggroRadius <= 0) return;
        if (minion.getLocation().distanceSquared(victim.getLocation()) > aggroRadius * aggroRadius) return;
        if (!isValidTarget(victim)) return;
        minion.setTarget(victim);
    }
}
