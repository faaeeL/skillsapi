package com.example.skillsapi.listener;

import com.example.skillsapi.threat.ThreatManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.plugin.Plugin;

/**
 * The tank/aggro mechanic's two live hooks:
 *   - every hit a Monster takes from a LivingEntity (a player, a player's
 *     summon, a player-shot arrow - see the projectile-shooter unwrap below)
 *     generates threat proportional to the damage dealt.
 *   - whenever that Monster is about to pick a target, its own recorded
 *     threat table overrides vanilla's selection if it disagrees - "keep
 *     fighting whoever's actually hurting you most (or taunted you), not
 *     just whoever happens to be nearest right now."
 *
 * Threat is only ever tracked/enforced for real Monster-type victims -
 * matching the restriction SummonAiTask's own autonomous aggro already
 * uses, so a summoned pet gaining threat against, say, a passive cow never
 * becomes a thing.
 *
 * onTarget runs at EventPriority.NORMAL deliberately, one step below
 * SummonTargetListener's onTarget (EventPriority.HIGH) - threat is a
 * *preference* ("fight whoever's hurting you"), the "never attack your own
 * owner" rule there is a hard safety constraint, and a hard constraint
 * should always get the final say over a preference regardless of which
 * listener happens to be registered first.
 */
public class ThreatListener implements Listener {

    private final Plugin plugin;
    private final ThreatManager threatManager;

    public ThreatListener(Plugin plugin, ThreatManager threatManager) {
        this.plugin = plugin;
        this.threatManager = threatManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Monster victim)) return;

        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter; // credit whoever fired it, not the arrow/trident itself
        }
        if (!(damager instanceof LivingEntity attacker) || damager.equals(victim)) return;

        threatManager.addThreat(victim.getUniqueId(), attacker.getUniqueId(), event.getFinalDamage());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Monster mob)) return;

        LivingEntity topThreat = threatManager.topThreatTarget(plugin, mob.getUniqueId());
        if (topThreat == null) return; // nothing recorded - leave vanilla's pick alone

        LivingEntity proposed = event.getTarget();
        if (proposed != null && proposed.getUniqueId().equals(topThreat.getUniqueId())) return; // already agrees

        event.setTarget(topThreat);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        threatManager.clear(event.getEntity().getUniqueId());
    }
}
