package com.example.skillsapi;

import com.example.skillsapi.command.CastCommand;
import com.example.skillsapi.command.SkillsApiCommand;
import com.example.skillsapi.listener.CastInterruptListener;
import com.example.skillsapi.listener.MobTriggerListener;
import com.example.skillsapi.listener.ShieldDamageListener;
import com.example.skillsapi.listener.SkillItemListener;
import com.example.skillsapi.listener.SummonTargetListener;
import com.example.skillsapi.listener.ThreatListener;
import com.example.skillsapi.mob.MobInstanceManager;
import com.example.skillsapi.mob.MobTemplateManager;
import com.example.skillsapi.parser.MobConfigParser;
import com.example.skillsapi.parser.SkillConfigParser;
import com.example.skillsapi.resource.ResourceManager;
import com.example.skillsapi.skill.CastEngine;
import com.example.skillsapi.skill.CastManager;
import com.example.skillsapi.skill.SkillManager;
import com.example.skillsapi.status.StatusManager;
import com.example.skillsapi.summon.SummonManager;
import com.example.skillsapi.threat.ThreatManager;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class SkillsPlugin extends JavaPlugin {

    private SkillManager skillManager;
    private ResourceManager resourceManager;
    private CastManager castManager;
    private CastEngine castEngine;
    private StatusManager statusManager;
    private SummonManager summonManager;
    private ThreatManager threatManager;
    private MobTemplateManager mobTemplateManager;
    private MobInstanceManager mobInstanceManager;

    @Override
    public void onEnable() {
        ensureFile("resources.yml");
        // A single saveResource() can't pull out a whole folder, so the
        // default skills/*.yml (and mobs/*.yml) files get extracted by hand -
        // once, on first run. Existing files (yours) are never touched or overwritten.
        ensureDirectoryResources("skills", new File(getDataFolder(), "skills"));
        ensureDirectoryResources("mobs", new File(getDataFolder(), "mobs"));

        resourceManager = new ResourceManager();
        castManager = new CastManager();
        castEngine = new CastEngine(this, castManager);
        skillManager = new SkillManager();
        statusManager = new StatusManager(this);
        summonManager = new SummonManager(this);
        threatManager = new ThreatManager();
        mobTemplateManager = new MobTemplateManager();
        mobInstanceManager = new MobInstanceManager(this, skillManager);

        getServer().getPluginManager().registerEvents(new CastInterruptListener(castManager), this);
        getServer().getPluginManager().registerEvents(new ShieldDamageListener(statusManager), this);
        getServer().getPluginManager().registerEvents(
                new SkillItemListener(this, skillManager, resourceManager, castEngine), this);
        getServer().getPluginManager().registerEvents(new SummonTargetListener(this, summonManager), this);
        getServer().getPluginManager().registerEvents(new ThreatListener(this, threatManager), this);
        getServer().getPluginManager().registerEvents(new MobTriggerListener(this, mobInstanceManager), this);

        // Initial load goes through the exact same path /skillsapi reload uses later.
        reloadConfigs(null);
        resourceManager.startRegenTask(this);
        // Threat decay is deliberately its own slow timer, not folded into
        // any per-combat-event hook - see ThreatManager#decay. Every 5s,
        // retain 90% of each recorded threat value (a ~45s half-life-ish
        // falloff), so a fight that's moved on eventually lets a mob's
        // target selection go back to "whoever's actually still hurting it."
        getServer().getScheduler().runTaskTimer(this, () -> threatManager.decay(this, 0.9), 100L, 100L);

        getCommand("cast").setExecutor(new CastCommand(skillManager, resourceManager, castEngine));
        SkillsApiCommand adminCommand = new SkillsApiCommand(this);
        getCommand("skillsapi").setExecutor(adminCommand);
        getCommand("skillsapi").setTabCompleter(adminCommand);
    }

    private void ensureFile(String name) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) saveResource(name, false);
    }

    /** Extracts every file bundled under {@code jarFolder}/ in the plugin jar into {@code targetDir}, skipping anything already there. */
    private void ensureDirectoryResources(String jarFolder, File targetDir) {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            getLogger().warning("Couldn't create " + targetDir + " - default skill files won't be extracted.");
            return;
        }

        String prefix = jarFolder + "/";
        try (JarFile jar = new JarFile(getFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;

                String relative = entry.getName().substring(prefix.length());
                if (relative.isEmpty()) continue;

                File outFile = new File(targetDir, relative);
                if (outFile.exists()) continue; // never clobber something already on disk

                File parent = outFile.getParentFile();
                if (parent != null) parent.mkdirs();
                try (InputStream in = jar.getInputStream(entry); OutputStream out = Files.newOutputStream(outFile.toPath())) {
                    in.transferTo(out);
                }
            }
        } catch (IOException e) {
            getLogger().warning("Couldn't extract default " + jarFolder + "/ files: " + e.getMessage());
        }
    }

    /** Every .yml/.yaml file under a directory, recursively, in a stable (alphabetical, by full path) order. */
    private List<File> collectYamlFiles(File dir) {
        List<File> result = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) return result;
        for (File child : children) {
            if (child.isDirectory()) {
                result.addAll(collectYamlFiles(child));
            } else if (child.getName().endsWith(".yml") || child.getName().endsWith(".yaml")) {
                result.add(child);
            }
        }
        result.sort(Comparator.comparing(File::getPath));
        return result;
    }

    /**
     * Re-reads resources.yml and every file under skills/ straight off disk
     * and rebuilds the SkillManager/ResourceManager/StatusManager
     * definitions from them - no server restart needed. Safe to call at any
     * time (this is exactly what `/skillsapi reload` does).
     *
     * skills/ can hold as many files as you want, in any subfolder
     * structure you want - split by skill type, by content pack, whatever
     * makes sense to you. Every file's `statuses:` section is registered
     * before any file's `skills:` section is parsed, so a skill in one file
     * can reference a status defined in a totally different one. A legacy
     * single skills.yml in the plugin's root data folder (from before this
     * became a directory) is still picked up too, loaded last, so nothing
     * breaks on upgrade - move its content into skills/ whenever you like.
     *
     * Notes on what this does and doesn't do:
     * - Skills removed from disk disappear from the registry; skills
     *   mid-channel keep resolving against the version of the Skill object
     *   they already captured, so an in-progress cast finishes normally.
     *   Same goes for entities with a status already running mid-effect -
     *   it keeps ticking against the Status object it started with.
     * - Resource *definitions* (max/regen) are refreshed for every type
     *   still present in resources.yml; a type removed from the file is
     *   left as-is (any players' current pools for it aren't touched) -
     *   see the note in ResourceManager if you need stricter behavior.
     * - Two skills (or statuses) with the same id in different files: the
     *   later-loaded one wins, and a warning is logged pointing at both
     *   files - it won't fail the whole reload.
     */
    public void reloadConfigs(CommandSender sender) {
        try {
            File resourcesFile = new File(getDataFolder(), "resources.yml");
            YamlConfiguration resourcesConfig = YamlConfiguration.loadConfiguration(resourcesFile);
            ConfigurationSection resourcesSection = resourcesConfig.getConfigurationSection("resources");
            resourceManager.loadFromConfig(resourcesSection);

            List<File> files = collectYamlFiles(new File(getDataFolder(), "skills"));
            File legacyFile = new File(getDataFolder(), "skills.yml");
            if (legacyFile.exists()) files.add(legacyFile); // loaded last, deliberately - see javadoc

            List<YamlConfiguration> loaded = new ArrayList<>(files.size());
            for (File file : files) {
                loaded.add(YamlConfiguration.loadConfiguration(file));
            }

            skillManager.clear();
            statusManager.clearDefinitions();

            for (int i = 0; i < files.size(); i++) {
                SkillConfigParser.loadStatuses(loaded.get(i).getConfigurationSection("statuses"),
                        statusManager, summonManager, threatManager, this, files.get(i).getName());
            }
            for (int i = 0; i < files.size(); i++) {
                SkillConfigParser.loadSkills(loaded.get(i).getConfigurationSection("skills"), skillManager,
                        resourceManager, this, statusManager, summonManager, threatManager, files.get(i).getName());
            }

            List<File> mobFiles = collectYamlFiles(new File(getDataFolder(), "mobs"));
            mobTemplateManager.clear();
            for (File file : mobFiles) {
                YamlConfiguration mobConfig = YamlConfiguration.loadConfiguration(file);
                MobConfigParser.loadMobs(mobConfig.getConfigurationSection("mobs"), mobTemplateManager, this, file.getName());
            }

            int resourceCount = resourcesSection == null ? 0 : resourcesSection.getKeys(false).size();
            String message = "Loaded " + skillManager.getAll().size() + " skill(s) from " + files.size()
                    + " file(s), " + mobTemplateManager.getAll().size() + " mob template(s) from " + mobFiles.size()
                    + " file(s), and " + resourceCount + " resource type(s).";
            getLogger().info(message);
            if (sender != null) sender.sendMessage("[SkillsAPI] " + message);
        } catch (Exception e) {
            String error = "Reload failed: " + e.getMessage();
            getLogger().warning(error);
            if (sender != null) sender.sendMessage("[SkillsAPI] " + error);
        }
    }

    /** Other plugins (or your own listeners) can grab these to cast skills / read resources programmatically. */
    public SkillManager getSkillManager() {
        return skillManager;
    }

    public ResourceManager getResourceManager() {
        return resourceManager;
    }

    /** Go through this (not skill.cast() directly) if you want cast times/interrupts honored. */
    public CastEngine getCastEngine() {
        return castEngine;
    }

    public CastManager getCastManager() {
        return castManager;
    }

    public StatusManager getStatusManager() {
        return statusManager;
    }

    public SummonManager getSummonManager() {
        return summonManager;
    }

    public ThreatManager getThreatManager() {
        return threatManager;
    }

    public MobTemplateManager getMobTemplateManager() {
        return mobTemplateManager;
    }

    public MobInstanceManager getMobInstanceManager() {
        return mobInstanceManager;
    }
}
