package com.example.skillsapi.listener;

import com.example.skillsapi.item.BoundSkillList;
import com.example.skillsapi.item.SkillItemLore;
import com.example.skillsapi.resource.ResourceManager;
import com.example.skillsapi.skill.CastAttemptResult;
import com.example.skillsapi.skill.CastEngine;
import com.example.skillsapi.skill.CastFeedback;
import com.example.skillsapi.skill.Skill;
import com.example.skillsapi.skill.SkillManager;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.Optional;

/**
 * A second way in to the exact same cast path /cast uses: right-click while
 * holding an item bound to a skill (see SkillsApiCommand's `bind`
 * subcommand) casts it instead of doing whatever right-clicking that item
 * normally does. Goes through CastEngine + CastFeedback just like
 * CastCommand does, so cooldowns, costs, cast times, and the messages for
 * all of it behave identically no matter which trigger the player used.
 *
 * An item can carry any number of bound skills at once (BoundSkillList) -
 * plain right-click casts whichever one is currently selected; shift +
 * right-click doesn't cast anything, it just advances the selection to the
 * next skill in the rotation, wrapping back to the first once it reaches
 * the end. That's the whole difference from a plain single-skill item: one
 * skill bound means shift-right-click just re-selects the same one each
 * time (harmless no-op), so nothing extra is needed to support "only one
 * skill bound" as a special case.
 */
public class SkillItemListener implements Listener {

    private final Plugin plugin;
    private final SkillManager skillManager;
    private final ResourceManager resourceManager;
    private final CastEngine castEngine;

    public SkillItemListener(Plugin plugin, SkillManager skillManager, ResourceManager resourceManager,
                              CastEngine castEngine) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        this.resourceManager = resourceManager;
        this.castEngine = castEngine;
    }

    // Deliberately NOT ignoreCancelled: Bukkit pre-cancels this event when
    // vanilla behavior would be to do nothing - which is exactly what
    // happens on a plain item (no food/potion/block/etc. behavior) clicked
    // in open air. Skipping cancelled events would silently drop every
    // air-click with a skill-bound item and only ever fire on blocks.
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        // Bukkit fires this once per hand for a single physical click; only
        // act on the main-hand copy or a held skill item in the off hand
        // would try to cast twice.
        if (event.getHand() != EquipmentSlot.HAND) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        BoundSkillList bound = BoundSkillList.read(plugin, meta);
        if (bound.isEmpty()) return;

        // This item is spoken for - stop it from placing a block, opening a
        // container, eating, etc. regardless of what happens next (a skill
        // on cooldown, or a plain cycle, still shouldn't let the item fall
        // through to its normal behavior).
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        Player player = event.getPlayer();

        if (player.isSneaking()) {
            BoundSkillList cycled = bound.cycled();
            cycled.write(plugin, meta);
            SkillItemLore.apply(meta, cycled);
            item.setItemMeta(meta);
            player.sendMessage("Selected: " + cycled.selected()
                    + " (" + (cycled.selectedIndex() + 1) + "/" + cycled.skills().size() + ")");
            return;
        }

        String skillId = bound.selected();
        Optional<Skill> skillOpt = skillManager.get(skillId);
        if (skillOpt.isEmpty()) {
            player.sendMessage("This item is bound to an unknown skill: " + skillId);
            return;
        }

        Skill skill = skillOpt.get();
        CastAttemptResult result = castEngine.attemptCast(skill, player);
        CastFeedback.send(player, skill, result, resourceManager);
    }
}
