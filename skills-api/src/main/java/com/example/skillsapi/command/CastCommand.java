package com.example.skillsapi.command;

import com.example.skillsapi.resource.ResourceManager;
import com.example.skillsapi.skill.CastAttemptResult;
import com.example.skillsapi.skill.CastEngine;
import com.example.skillsapi.skill.CastFeedback;
import com.example.skillsapi.skill.Skill;
import com.example.skillsapi.skill.SkillManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;

public class CastCommand implements CommandExecutor {

    private final SkillManager skillManager;
    private final ResourceManager resourceManager;
    private final CastEngine castEngine;

    public CastCommand(SkillManager skillManager, ResourceManager resourceManager, CastEngine castEngine) {
        this.skillManager = skillManager;
        this.resourceManager = resourceManager;
        this.castEngine = castEngine;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can cast skills.");
            return true;
        }
        if (args.length < 1) {
            player.sendMessage("Usage: /cast <skillId>");
            return true;
        }

        Optional<Skill> skillOpt = skillManager.get(args[0]);
        if (skillOpt.isEmpty()) {
            player.sendMessage("Unknown skill: " + args[0]);
            return true;
        }

        Skill skill = skillOpt.get();
        CastAttemptResult result = castEngine.attemptCast(skill, player);
        CastFeedback.send(player, skill, result, resourceManager);
        return true;
    }
}
