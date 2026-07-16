package com.example.skillsapi.effect;

import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Vanilla `GLOWING` has no color of its own - the outline color an entity
 * glows with is entirely determined by whatever scoreboard team it belongs
 * to (Team#setColor). This effect is what actually makes a colored glow
 * possible: it gets-or-creates a shared team per color on the main
 * scoreboard, adds the target to it for the duration, applies the ordinary
 * GLOWING potion effect on top, and then removes just that target's entry
 * from the team again once the glow itself expires - never deletes the
 * team, since it's shared by every glow effect using that color, on any
 * entity, for as long as the plugin is loaded.
 *
 * skills.yml:
 *   - type: glow
 *     color: RED             # any org.bukkit.ChatColor name, default WHITE
 *     duration_ticks: 100    # default 100 (5s), matches PotionEffectType default
 */
public class GlowEffect implements SkillEffect {

    private static final String TEAM_PREFIX = "skillsapi_glow_";

    private final Plugin plugin;
    private final ChatColor color;
    private final int durationTicks;

    public GlowEffect(Plugin plugin, ChatColor color, int durationTicks) {
        this.plugin = plugin;
        this.color = color;
        this.durationTicks = durationTicks;
    }

    @Override
    public void apply(SkillContext context) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = getOrCreateTeam(scoreboard);

        for (LivingEntity target : context.getTargets()) {
            String entry = entryFor(target);
            team.addEntry(entry);
            target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, durationTicks, 0));

            // Only this target's entry is removed, not the whole team - the
            // team is a shared, permanent resource for this color, reused
            // by every future glow effect (this skill or any other) that
            // asks for the same color.
            Bukkit.getScheduler().runTaskLater(plugin, () -> team.removeEntry(entry), durationTicks);
        }
    }

    private Team getOrCreateTeam(Scoreboard scoreboard) {
        String teamName = TEAM_PREFIX + color.name();
        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
            team.setColor(color);
        }
        return team;
    }

    // Players use their name (vanilla scoreboard convention); non-player
    // entities use their UUID as the entry string - Bukkit's scoreboard
    // API accepts either for team membership, but only the UUID form
    // actually resolves back to a non-player entity for glow-color purposes.
    private String entryFor(LivingEntity entity) {
        return entity instanceof Player player ? player.getName() : entity.getUniqueId().toString();
    }
}
