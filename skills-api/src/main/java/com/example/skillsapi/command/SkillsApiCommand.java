package com.example.skillsapi.command;

import com.example.skillsapi.SkillsPlugin;
import com.example.skillsapi.item.BoundSkillList;
import com.example.skillsapi.item.SkillItemLore;
import com.example.skillsapi.mob.MobSpawner;
import com.example.skillsapi.mob.MobTemplate;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import com.example.skillsapi.skill.Skill;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * `/skillsapi reload` - re-reads skills.yml and resources.yml from disk and
 * rebuilds the skill/resource registries in place. No server restart, no
 * `/reload` (which would reload every plugin and is generally discouraged).
 * Just edit the files in plugins/SkillsAPI/ and run this.
 *
 * `/skillsapi bind <skillId>` - adds a skill to the held item's rotation
 * (see BoundSkillList) instead of doing whatever right-clicking it normally
 * does. Right-click casts whichever skill is currently selected;
 * shift+right-click cycles to the next one (see SkillItemListener). Binding
 * a skill that's already on the item is a no-op. `/skillsapi unbind
 * <skillId>` removes just that one from the rotation; `/skillsapi unbind`
 * with no id clears the whole thing.
 */
public class SkillsApiCommand implements CommandExecutor, TabCompleter {

    private final SkillsPlugin plugin;

    public SkillsApiCommand(SkillsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("Usage: /skillsapi <reload|bind|unbind|spawnmob>");
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "bind" -> handleBind(sender, args);
            case "unbind" -> handleUnbind(sender, args);
            case "spawnmob" -> handleSpawnMob(sender, args);
            default -> {
                sender.sendMessage("Usage: /skillsapi <reload|bind|unbind|spawnmob>");
                yield true;
            }
        };
    }

    private boolean handleSpawnMob(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can spawn a mob template.");
            return true;
        }
        if (!player.hasPermission("skillsapi.spawnmob")) {
            player.sendMessage("You don't have permission to do that.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage("Usage: /skillsapi spawnmob <templateId>");
            return true;
        }

        String templateId = args[1];
        Optional<MobTemplate> templateOpt = plugin.getMobTemplateManager().get(templateId);
        if (templateOpt.isEmpty()) {
            player.sendMessage("Unknown mob template: " + templateId);
            return true;
        }

        Location spawnLocation = player.getLocation().add(player.getLocation().getDirection().normalize().multiply(3));
        MobSpawner.spawn(templateOpt.get(), spawnLocation, plugin.getMobInstanceManager());
        player.sendMessage("Spawned " + templateId + ".");
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("skillsapi.reload")) {
            sender.sendMessage("You don't have permission to do that.");
            return true;
        }
        plugin.reloadConfigs(sender);
        return true;
    }

    private boolean handleBind(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can bind a skill to an item.");
            return true;
        }
        if (!player.hasPermission("skillsapi.bind")) {
            player.sendMessage("You don't have permission to do that.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage("Usage: /skillsapi bind <skillId>");
            return true;
        }

        String skillId = args[1];
        Optional<Skill> skillOpt = plugin.getSkillManager().get(skillId);
        if (skillOpt.isEmpty()) {
            player.sendMessage("Unknown skill: " + skillId);
            return true;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            player.sendMessage("Hold the item you want to bind first.");
            return true;
        }

        ItemMeta meta = item.getItemMeta();
        BoundSkillList current = BoundSkillList.read(plugin, meta);
        if (current.contains(skillId)) {
            player.sendMessage(skillId + " is already bound to this item.");
            return true;
        }

        BoundSkillList updated = current.withAdded(skillId);
        updated.write(plugin, meta);
        SkillItemLore.apply(meta, updated);
        item.setItemMeta(meta);

        player.sendMessage("Bound " + skillId + " to your held item ("
                + updated.skills().size() + (updated.skills().size() == 1 ? " skill" : " skills") + " total"
                + (updated.skills().size() > 1 ? " - shift+right-click to cycle" : "") + ").");
        return true;
    }

    private boolean handleUnbind(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can unbind an item.");
            return true;
        }
        if (!player.hasPermission("skillsapi.bind")) {
            player.sendMessage("You don't have permission to do that.");
            return true;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            player.sendMessage("Your held item isn't bound to a skill.");
            return true;
        }

        BoundSkillList current = BoundSkillList.read(plugin, meta);
        if (current.isEmpty()) {
            player.sendMessage("Your held item isn't bound to a skill.");
            return true;
        }

        BoundSkillList updated;
        String message;
        if (args.length >= 2) {
            String skillId = args[1];
            if (!current.contains(skillId)) {
                player.sendMessage(skillId + " isn't bound to your held item.");
                return true;
            }
            updated = current.withRemoved(skillId);
            message = "Unbound " + skillId + " from your held item"
                    + (updated.isEmpty() ? "." : " (" + updated.skills().size() + " remaining).");
        } else {
            // No id given - clear the whole rotation, same as unbind always did before it could hold more than one skill.
            updated = BoundSkillList.empty();
            message = "Unbound your held item (" + current.skills().size() + " skill"
                    + (current.skills().size() == 1 ? "" : "s") + " removed).";
        }

        updated.write(plugin, meta);
        SkillItemLore.apply(meta, updated);
        item.setItemMeta(meta);
        player.sendMessage(message);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) return List.of("reload", "bind", "unbind", "spawnmob");
        if (args.length == 2 && args[0].equalsIgnoreCase("bind")) {
            return plugin.getSkillManager().getAll().values().stream().map(Skill::getId).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("unbind") && sender instanceof Player player) {
            ItemStack item = player.getInventory().getItemInMainHand();
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return Collections.emptyList();
            return BoundSkillList.read(plugin, meta).skills();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawnmob")) {
            return new ArrayList<>(plugin.getMobTemplateManager().getAll().keySet());
        }
        return Collections.emptyList();
    }
}
