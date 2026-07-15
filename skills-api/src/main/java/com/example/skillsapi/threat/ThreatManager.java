package com.example.skillsapi.threat;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks, per mob, how much "threat" each attacker has generated against it
 * - the classic tank/aggro mechanic: a mob should keep fighting whoever's
 * actually been hurting it most (or been directly taunted onto it), not
 * just whichever potential target happens to be nearest this tick the way
 * SummonAiTask's own autonomous aggro works.
 *
 * Threat is generated two ways:
 *   - passively, per point of damage dealt - see ThreatListener's
 *     EntityDamageByEntityEvent handler, which credits whoever actually
 *     landed the hit (unwrapping projectiles back to their shooter)
 *   - explicitly, via a `type: taunt` skill effect (TauntEffect) - the
 *     tank's dedicated "look at me" button, independent of how much damage
 *     it actually deals, or whether it deals any at all
 *
 * Deliberately keyed by UUID rather than holding live Entity references -
 * mobs die/despawn/unload constantly, and a table full of stale Entity
 * objects would be both a memory leak and a footgun. Resolving a UUID back
 * to a live Entity happens lazily, only when something actually needs it
 * (topThreatTarget) - actual pruning of dead entries happens in decay(),
 * called on a slow repeating timer rather than per-event, so its cost is
 * bounded no matter how much combat happens between runs.
 */
public final class ThreatManager {

    // mobId -> (attackerId -> accumulated threat)
    private final Map<UUID, Map<UUID, Double>> tables = new ConcurrentHashMap<>();

    /** Adds threat. A non-positive amount is a no-op - there's no "negative threat" concept here. */
    public void addThreat(UUID mobId, UUID attackerId, double amount) {
        if (amount <= 0) return;
        tables.computeIfAbsent(mobId, id -> new ConcurrentHashMap<>())
                .merge(attackerId, amount, Double::sum);
    }

    /**
     * The live entity currently holding the most threat against this mob,
     * or null if it has no recorded threat at all (letting the caller fall
     * back to vanilla target selection). Skips any recorded attacker that
     * isn't a currently-loaded, valid entity - a logged-out player or a
     * despawned summon doesn't get to "win" threat forever just because
     * nothing else is left to outrank it; full removal of that entry
     * happens in decay(), not here, since scanning-and-mutating on every
     * single target-selection event would be needless overhead.
     */
    public LivingEntity topThreatTarget(Plugin plugin, UUID mobId) {
        Map<UUID, Double> table = tables.get(mobId);
        if (table == null || table.isEmpty()) return null;

        LivingEntity best = null;
        double bestThreat = Double.NEGATIVE_INFINITY;
        for (Map.Entry<UUID, Double> entry : table.entrySet()) {
            Entity candidate = plugin.getServer().getEntity(entry.getKey());
            if (!(candidate instanceof LivingEntity living) || !living.isValid()) continue;
            if (entry.getValue() > bestThreat) {
                bestThreat = entry.getValue();
                best = living;
            }
        }
        return best;
    }

    /** Wipes a mob's whole threat table - call when it dies, so a reused UUID never inherits stale threat. */
    public void clear(UUID mobId) {
        tables.remove(mobId);
    }

    /**
     * Periodic maintenance: multiplies every recorded threat value by
     * `retainFraction` (e.g. 0.95 = lose 5% of its current value each time
     * this runs), so a fight that's moved on doesn't leave a mob
     * permanently locked onto whoever hurt it once, a long time ago. Also
     * drops any attacker entry that's decayed below a negligible floor or
     * is no longer a valid loaded entity, and any mob entry whose table is
     * now empty or who is itself no longer valid. Meant to be called from a
     * slow repeating timer (a few times a minute is plenty), not from
     * combat events directly.
     */
    public void decay(Plugin plugin, double retainFraction) {
        tables.entrySet().removeIf(mobEntry -> {
            Entity mobEntity = plugin.getServer().getEntity(mobEntry.getKey());
            if (!(mobEntity instanceof LivingEntity mob) || !mob.isValid()) return true;

            Map<UUID, Double> table = mobEntry.getValue();
            // A real Iterator here, not table.entrySet().removeIf(...): on
            // ConcurrentHashMap, removeIf hands its predicate throwaway
            // AbstractMap.SimpleImmutableEntry snapshots (fine for reads,
            // but setValue() below would throw UnsupportedOperationException).
            // An Iterator's entries are the live, map-backed kind that
            // actually support setValue/remove during iteration.
            Iterator<Map.Entry<UUID, Double>> attackers = table.entrySet().iterator();
            while (attackers.hasNext()) {
                Map.Entry<UUID, Double> attackerEntry = attackers.next();
                double decayed = attackerEntry.getValue() * retainFraction;
                if (decayed < 0.01) {
                    attackers.remove();
                    continue;
                }
                attackerEntry.setValue(decayed);
                Entity attacker = plugin.getServer().getEntity(attackerEntry.getKey());
                if (!(attacker instanceof LivingEntity living) || !living.isValid()) {
                    attackers.remove();
                }
            }
            return table.isEmpty();
        });
    }
}
