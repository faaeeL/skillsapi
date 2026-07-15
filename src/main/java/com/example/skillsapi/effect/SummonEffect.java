package com.example.skillsapi.effect;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import com.example.skillsapi.summon.SummonManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Spawns one or more owned mobs that fight for the caster - the necromancer/
 * summoner building block. Deliberately built on real Bukkit entities with
 * their own vanilla AI (not a fake/simulated mob the way ProjectileEffect's
 * "projectile" is a simulated point) - a summon needs pathfinding, combat,
 * and death handling that vanilla mobs already do correctly, and re-deriving
 * that badly would be a worse minion than just using one.
 *
 * This class only spawns/configures/registers - the *behavior* lives
 * elsewhere on purpose:
 *   - SummonManager: cap enforcement, lifespan, dismiss/cleanup bookkeeping.
 *   - SummonAiTask: the per-tick "fight the nearest hostile, follow the
 *     owner when idle" loop.
 *   - SummonTargetListener: vetoes a summon ever targeting its own owner
 *     (or a sibling summon), and cleans up SummonManager's bookkeeping when
 *     one dies in ordinary combat instead of being dismissed.
 */
public class SummonEffect implements SkillEffect {

    private final SummonManager summonManager;
    private final EntityType entityType;
    private final int count;
    private final double spawnScatterRadius;
    private final String customName;
    private final double maxHealth; // <= 0 means "leave it at the entity type's vanilla default"
    private final Material mainHand; // nullable
    private final Material helmet; // nullable

    private final int durationTicks; // -1 = until it dies or is dismissed
    private final int maxActive; // <= 0 = uncapped
    private final SummonManager.CapBehavior onCapExceeded;

    private final double aggroRadius;
    private final double followRadius;
    private final double moveSpeed;
    private final int aiIntervalTicks;

    private final List<SkillEffect> onSummonEffects;

    public SummonEffect(SummonManager summonManager, EntityType entityType, int count, double spawnScatterRadius,
                         String customName, double maxHealth, Material mainHand, Material helmet, int durationTicks,
                         int maxActive, SummonManager.CapBehavior onCapExceeded, double aggroRadius,
                         double followRadius, double moveSpeed, int aiIntervalTicks,
                         List<SkillEffect> onSummonEffects) {
        this.summonManager = summonManager;
        this.entityType = entityType;
        this.count = Math.max(1, count);
        this.spawnScatterRadius = spawnScatterRadius;
        this.customName = customName;
        this.maxHealth = maxHealth;
        this.mainHand = mainHand;
        this.helmet = helmet;
        this.durationTicks = durationTicks;
        this.maxActive = maxActive;
        this.onCapExceeded = onCapExceeded;
        this.aggroRadius = aggroRadius;
        this.followRadius = followRadius;
        this.moveSpeed = moveSpeed;
        this.aiIntervalTicks = aiIntervalTicks;
        this.onSummonEffects = onSummonEffects;
    }

    @Override
    public void apply(SkillContext context) {
        LivingEntity owner = context.getCaster();
        if (!owner.isValid() || owner.getWorld() == null) return;

        String skillId = context.getSkill() != null ? context.getSkill().getId() : "unknown";

        for (int i = 0; i < count; i++) {
            if (!summonManager.makeRoom(owner.getUniqueId(), maxActive, onCapExceeded)) {
                if (owner instanceof Player player) {
                    player.sendMessage("You already have the maximum number of " + skillId + " summons active.");
                }
                break; // one refusal message for the whole cast, not one per remaining minion
            }

            LivingEntity minion = spawnMinion(owner);
            summonManager.register(owner, minion, skillId, durationTicks,
                    aggroRadius, followRadius, moveSpeed, aiIntervalTicks);

            if (onSummonEffects != null && !onSummonEffects.isEmpty()) {
                SkillContext summonContext = new SkillContext(owner, context.getSkill());
                summonContext.setTargets(List.of(minion));
                for (SkillEffect effect : onSummonEffects) {
                    effect.apply(summonContext);
                }
            }
        }
    }

    private LivingEntity spawnMinion(LivingEntity owner) {
        Location spawnLocation = randomNearbyLocation(owner.getLocation(), spawnScatterRadius);
        LivingEntity minion = (LivingEntity) owner.getWorld().spawnEntity(spawnLocation, entityType);

        if (customName != null && !customName.isEmpty()) {
            minion.setCustomName(customName);
            minion.setCustomNameVisible(true);
        }

        if (maxHealth > 0 && minion.getAttribute(Attribute.MAX_HEALTH) != null) {
            minion.getAttribute(Attribute.MAX_HEALTH).setBaseValue(maxHealth);
            minion.setHealth(maxHealth);
        }

        EntityEquipment equipment = minion.getEquipment();
        if (equipment != null) {
            // Drop chance 0 on anything we hand it: a temporary summon
            // shouldn't be a backdoor way to farm free gear off its corpse.
            if (mainHand != null) {
                equipment.setItemInMainHand(new ItemStack(mainHand));
                equipment.setItemInMainHandDropChance(0f);
            }
            if (helmet != null) {
                equipment.setHelmet(new ItemStack(helmet));
                equipment.setHelmetDropChance(0f);
            }
        }

        return minion;
    }

    private Location randomNearbyLocation(Location center, double radius) {
        if (radius <= 0) return center.clone();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble(0, Math.PI * 2);
        double distance = random.nextDouble(0, radius);
        return center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
    }
}
