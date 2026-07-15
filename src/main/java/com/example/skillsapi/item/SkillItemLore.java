package com.example.skillsapi.item;

import org.bukkit.ChatColor;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds/refreshes the lore block describing an item's bound-skill
 * rotation - SkillsApiCommand's bind/unbind and SkillItemListener's cycling
 * both go through this instead of hand-rolling lore lines, so the two can
 * never drift out of sync with what the item would actually do if clicked.
 */
public final class SkillItemLore {

    private SkillItemLore() {}

    public static void apply(ItemMeta meta, BoundSkillList bound) {
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.removeIf(SkillItemLore::isManagedLine);

        if (!bound.isEmpty()) {
            lore.add(ChatColor.GRAY + "Right-click to cast: " + ChatColor.AQUA + bound.selected());

            if (bound.skills().size() > 1) {
                lore.add(ChatColor.GRAY + "Shift+Right-click to cycle ("
                        + ChatColor.AQUA + (bound.selectedIndex() + 1) + "/" + bound.skills().size() + ChatColor.GRAY + ")");
                String rotation = bound.skills().stream()
                        .map(id -> id.equals(bound.selected()) ? ChatColor.AQUA + "[" + id + "]" + ChatColor.DARK_GRAY : id)
                        .collect(Collectors.joining(ChatColor.DARK_GRAY + ", "));
                lore.add(ChatColor.DARK_GRAY + "Bound: " + rotation);
            }
        }

        meta.setLore(lore.isEmpty() ? null : lore);
    }

    private static boolean isManagedLine(String line) {
        String stripped = ChatColor.stripColor(line);
        return stripped.startsWith("Right-click to cast:")
                || stripped.startsWith("Shift+Right-click to cycle")
                || stripped.startsWith("Shift+Right-click to cast:") // old fixed-secondary format, in case a stale line lingers
                || stripped.startsWith("Bound:");
    }
}
