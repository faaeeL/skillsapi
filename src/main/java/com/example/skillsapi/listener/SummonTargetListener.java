package com.example.skillsapi.listener;

import com.example.skillsapi.summon.SummonKeys;
import com.example.skillsapi.summon.SummonManager;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Five jobs, all keyed off the SummonKeys.owner tag SummonManager.register
 * stamps onto every spawned minion:
 *   - veto a minion targeting its own owner (or another minion belonging to
 *     that same owner) - SummonAiTask picks *what* to fight, this is what
 *     stops "friendly fire" from ever sticking regardless of what it picks.
 *   - block a summon's damage against its own owner (or a sibling summon)
 *     outright, as a hard backstop below the targeting veto above - see
 *     onSummonDamage's own doc for why the veto alone isn't actually enough.
 *   - block the reverse direction too: the owner's own damage against their
 *     own summon - see onOwnerDamageOwnSummon's doc. Doesn't touch a summon
 *     hitting *another player's* summon, or a player hitting someone else's
 *     summon - only "you" and "your own minion," in either direction.
 *   - the moment a player damages a fightable LivingEntity, let that
 *     player's own summons react immediately (SummonManager.notifyOwnerAttacked)
 *     instead of relying solely on each minion's own periodic proximity scan -
 *     which has no idea what the owner is actually fighting and could leave a
 *     summon ignoring the exact mob its owner just hit.
 *   - notice when a tracked minion dies in ordinary combat (not via
 *     SummonManager.dismiss()) and clean up its bookkeeping - otherwise its
 *     cap slot and lifespan task would leak.
 *
 * onTarget runs at EventPriority.HIGH deliberately - one step above
 * ThreatListener's own onTarget (NORMAL), which overrides vanilla target
 * selection based on threat/aggro. That override is a *preference*; this
 * veto is a hard safety constraint ("never attack your own owner/sibling"),
 * and a hard constraint should always get the final say, regardless of
 * which listener happens to be registered first.
 */
public class SummonTargetListener implements Listener {

    private final Plugin plugin;
    private final SummonManager summonManager;

    public SummonTargetListener(Plugin plugin, SummonManager summonManager) {
        this.plugin = plugin;
        this.summonManager = summonManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        String ownerRaw = event.getEntity().getPersistentDataContainer()
                .get(SummonKeys.owner(plugin), PersistentDataType.STRING);
        if (ownerRaw == null) return; // not a tracked summon - nothing to enforce

        LivingEntity proposedTarget = event.getTarget();
        if (proposedTarget == null) return;

        UUID ownerId = UUID.fromString(ownerRaw);
        if (proposedTarget.getUniqueId().equals(ownerId)) {
            event.setCancelled(true);
            return;
        }

        String targetOwnerRaw = proposedTarget.getPersistentDataContainer()
                .get(SummonKeys.owner(plugin), PersistentDataType.STRING);
        if (ownerRaw.equals(targetOwnerRaw)) {
            event.setCancelled(true); // another summon belonging to the same owner - not a valid target either
        }
    }

    /**
     * Reactive assist: fires on every hit, not just the first, since a
     * minion might have been idle at the time of an earlier hit (out of
     * aggroRadius, say) and only be in range by the time a later hit lands.
     * SummonAiTask#tryAssist is cheap and already re-checks "already fighting
     * something" + range itself, so there's no need to debounce this.
     *
     * Any LivingEntity the owner hits is a valid assist target - not just
     * Monster - so a minion helps out against a cow you're farming just as
     * readily as a zombie. Players are excluded (this isn't a PvP-assist
     * feature, at least not without asking for one), and so are ArmorStands
     * (Bukkit models them as LivingEntity for legacy reasons, but they're
     * scenery, not something to fight).
     */
    @EventHandler(ignoreCancelled = true)
    public void onOwnerAttack(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (victim instanceof Player || victim instanceof ArmorStand) return;

        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (!(damager instanceof Player player)) return;

        summonManager.notifyOwnerAttacked(player.getUniqueId(), victim);
    }

    /**
     * The targeting veto above (onTarget) only stops a summon from ever
     * *choosing* its owner/a sibling as a target - but plenty of vanilla
     * mobs retaliate against whoever just hurt them (Zombie/Skeleton-style
     * "revenge" AI) through an internal path that doesn't reliably fire
     * EntityTargetLivingEntityEvent at all on every version/mob, meaning a
     * summon could still land a hit on its own owner the instant the owner
     * attacked *it*, even with the veto above in place and working exactly
     * as intended. This is the actual fix for that: block the damage
     * itself, unconditionally, whenever a tracked summon is the damager and
     * the victim is that summon's own owner or another summon sharing the
     * same owner - the bug this closes is strictly "my summon hurts me
     * back." Owner-on-summon damage (the other direction) is a separate
     * rule, handled by onOwnerDamageOwnSummon below.
     * Unwraps a projectile back to its shooter first (same as
     * onOwnerAttack) - a bow-equipped summon's arrow is what actually deals
     * the damage, not the Mob itself.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSummonDamage(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (!(damager instanceof LivingEntity attacker)) return;

        String attackerOwnerRaw = attacker.getPersistentDataContainer()
                .get(SummonKeys.owner(plugin), PersistentDataType.STRING);
        if (attackerOwnerRaw == null) return; // the damager isn't a tracked summon - nothing to enforce

        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        if (victim.getUniqueId().toString().equals(attackerOwnerRaw)) {
            event.setCancelled(true);
            return;
        }

        String victimOwnerRaw = victim.getPersistentDataContainer()
                .get(SummonKeys.owner(plugin), PersistentDataType.STRING);
        if (attackerOwnerRaw.equals(victimOwnerRaw)) {
            event.setCancelled(true); // a sibling summon under the same owner
        }
    }

    /**
     * The mirror image of onSummonDamage: that one stops your summon from
     * hurting you, this one stops you from hurting your own summon. Only
     * blocks a player damaging a summon whose owner tag is that exact
     * player - hitting someone else's summon (or another player's summon
     * hitting yours) is untouched, and so is anything non-Player damaging a
     * summon (fall damage, fire, another mob, etc.) - this is specifically
     * "you vs. your own minion," not general damage immunity for summons.
     * Same projectile-unwrapping as onSummonDamage/onOwnerAttack, so a bow
     * shot at your own summon is blocked the same as a melee hit would be.
     *
     * Deliberately EventPriority.LOWEST, not HIGH like the other two
     * backstops above - this one has to run *before* onOwnerAttack
     * (NORMAL), not after. onOwnerAttack calls notifyOwnerAttacked
     * regardless of whether the hit itself gets cancelled later (it checks
     * `ignoreCancelled = true`, which only skips already-cancelled events at
     * the point *it* runs - Bukkit fires LOWEST before NORMAL before HIGH,
     * so a HIGH-priority cancellation here would happen too late and the
     * owner's other summons would still get notified to "assist" against
     * their own sibling, even though the hit itself never actually landed.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOwnerDamageOwnSummon(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (!(damager instanceof Player player)) return;

        String victimOwnerRaw = event.getEntity().getPersistentDataContainer()
                .get(SummonKeys.owner(plugin), PersistentDataType.STRING);
        if (victimOwnerRaw == null) return; // not a tracked summon at all

        if (victimOwnerRaw.equals(player.getUniqueId().toString())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        String ownerRaw = event.getEntity().getPersistentDataContainer()
                .get(SummonKeys.owner(plugin), PersistentDataType.STRING);
        if (ownerRaw == null) return;

        summonManager.onNaturalDeath(UUID.fromString(ownerRaw), event.getEntity().getUniqueId());
    }
}
