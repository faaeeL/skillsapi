package com.example.skillsapi.listener;

import com.example.skillsapi.mob.MobInstanceManager;
import com.example.skillsapi.mob.MobKeys;
import com.example.skillsapi.mob.Trigger;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Bridges vanilla combat events onto MobInstanceManager's trigger dispatch:
 * ON_DAMAGED/ON_LOW_HEALTH when a tracked template mob takes damage,
 * ON_ATTACK when one lands a hit, ON_DEATH (then cleanup) when one dies.
 * ON_SPAWN/ON_TIMER don't need a listener - MobInstanceManager fires those
 * itself from register()/its own scheduled tasks.
 */
public class MobTriggerListener implements Listener {

    private final Plugin plugin;
    private final MobInstanceManager mobInstanceManager;

    public MobTriggerListener(Plugin plugin, MobInstanceManager mobInstanceManager) {
        this.plugin = plugin;
        this.mobInstanceManager = mobInstanceManager;
    }

    @EventHandler
    public void onDamaged(EntityDamageEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (!isTemplateMob(victim)) return;

        mobInstanceManager.dispatch(Trigger.ON_DAMAGED, victim);
        mobInstanceManager.checkLowHealth(victim);
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getDamager() instanceof LivingEntity attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;
        if (!isTemplateMob(attacker)) return;

        mobInstanceManager.dispatch(Trigger.ON_ATTACK, attacker);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!isTemplateMob(entity)) return;

        mobInstanceManager.dispatch(Trigger.ON_DEATH, entity);
        mobInstanceManager.cleanup(entity.getUniqueId());
    }

    private boolean isTemplateMob(LivingEntity entity) {
        return entity.getPersistentDataContainer().has(MobKeys.templateId(plugin), PersistentDataType.STRING);
    }
}
