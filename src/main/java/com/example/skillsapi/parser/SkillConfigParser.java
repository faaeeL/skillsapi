package com.example.skillsapi.parser;

import com.example.skillsapi.condition.HealthThresholdCondition;
import com.example.skillsapi.condition.ResourceCostCondition;
import com.example.skillsapi.effect.*;
import com.example.skillsapi.resource.ResourceManager;
import com.example.skillsapi.shape.ShapeGenerator;
import com.example.skillsapi.shape.ShapeLayer;
import com.example.skillsapi.shape.ShapeType;
import com.example.skillsapi.skill.Skill;
import com.example.skillsapi.skill.SkillEffect;
import com.example.skillsapi.skill.SkillManager;
import com.example.skillsapi.skill.Targeter;
import com.example.skillsapi.skill.Telegraph;
import com.example.skillsapi.status.DashStatusBehavior;
import com.example.skillsapi.status.FrozenStatusBehavior;
import com.example.skillsapi.status.Status;
import com.example.skillsapi.status.StatusBehavior;
import com.example.skillsapi.status.StatusManager;
import com.example.skillsapi.summon.SummonManager;
import com.example.skillsapi.targeter.*;
import com.example.skillsapi.threat.ThreatManager;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a `skills:` section from YAML and turns each entry into a Skill made
 * of the built-in effects/targeters. Add a `case` to parseEffect/parseTargeter
 * whenever you add a new effect or targeter class, and it's usable from config.
 */
public class SkillConfigParser {

    /**
     * Registers every status definition in one file's `statuses:` section.
     * Called once per file, for *every* file, before loadSkills is called
     * for *any* file - so a skill in one file can reference (`type: status,
     * status: <id>`) a status defined in a completely different file,
     * regardless of which order the files happen to load in.
     *
     * sourceLabel is just for error/warning messages (typically the
     * filename) - doesn't affect parsing.
     */
    public static void loadStatuses(ConfigurationSection statusesSection, StatusManager statusManager,
                                     SummonManager summonManager, ThreatManager threatManager, Plugin plugin, String sourceLabel) {
        if (statusesSection == null) return;

        for (String id : statusesSection.getKeys(false)) {
            ConfigurationSection section = statusesSection.getConfigurationSection(id);
            if (section == null) continue;
            if (statusManager.getDefinition(id) != null) {
                plugin.getLogger().warning("[" + sourceLabel + "] status '" + id
                        + "' overwrites one already defined in another file.");
            }
            try {
                statusManager.registerDefinition(id, parseStatus(section.getValues(false), id, plugin, statusManager, summonManager, threatManager));
            } catch (Exception e) {
                throw new IllegalArgumentException("[" + sourceLabel + "] status '" + id + "': " + e.getMessage(), e);
            }
        }
    }

    /** Registers every skill in one file's `skills:` section. See loadStatuses for why statuses are a separate, earlier pass. */
    public static void loadSkills(ConfigurationSection skillsSection, SkillManager manager,
                                   ResourceManager resourceManager, Plugin plugin, StatusManager statusManager,
                                   SummonManager summonManager, ThreatManager threatManager, String sourceLabel) {
        if (skillsSection == null) return;

        for (String id : skillsSection.getKeys(false)) {
            ConfigurationSection section = skillsSection.getConfigurationSection(id);
            if (section == null) continue;
            if (manager.get(id).isPresent()) {
                plugin.getLogger().warning("[" + sourceLabel + "] skill '" + id
                        + "' overwrites one already defined in another file.");
            }
            try {
                manager.register(parseSkill(id, section, resourceManager, plugin, statusManager, summonManager, threatManager));
            } catch (Exception e) {
                throw new IllegalArgumentException("[" + sourceLabel + "] skill '" + id + "': " + e.getMessage(), e);
            }
        }
    }

    private static Skill parseSkill(String id, ConfigurationSection section, ResourceManager resourceManager,
                                     Plugin plugin, StatusManager statusManager, SummonManager summonManager, ThreatManager threatManager) {
        long cooldown = section.getLong("cooldown", 0);
        Targeter targeter = parseTargeter(section);
        Skill skill = new Skill(id, targeter, cooldown);

        skill.setCastTime(section.getLong("cast_time", 0));
        skill.setInterruptible(section.getBoolean("interruptible", true));

        ConfigurationSection telegraphSection = section.getConfigurationSection("telegraph");
        if (telegraphSection != null) {
            Particle particle = telegraphSection.contains("particle")
                    ? Particle.valueOf(telegraphSection.getString("particle"))
                    : null;
            Sound sound = telegraphSection.contains("sound")
                    ? resolveSound(telegraphSection.getString("sound"))
                    : null;
            List<SkillEffect> telegraphOnStart = new ArrayList<>();
            for (Map<?, ?> onStartRaw : telegraphSection.getMapList("on_start")) {
                SkillEffect effect = parseEffect(onStartRaw, plugin, statusManager, summonManager, threatManager);
                if (effect != null) telegraphOnStart.add(effect);
            }

            skill.setTelegraph(new Telegraph(
                    particle,
                    telegraphSection.getInt("particle_count", 6),
                    sound,
                    telegraphSection.getLong("interval_ticks", 4),
                    telegraphOnStart
            ));
        }

        if (section.contains("min_health_percent")) {
            skill.addCondition(new HealthThresholdCondition(section.getDouble("min_health_percent")));
        }

        ConfigurationSection cost = section.getConfigurationSection("cost");
        if (cost != null) {
            String type = cost.getString("type", "mana");
            double amount = cost.getDouble("amount", 0);
            skill.setCost(type, amount);
            // Condition gates the cast; effect (added below, ahead of the
            // rest) actually spends it, only on a successful cast.
            skill.addCondition(new ResourceCostCondition(resourceManager, type, amount));
            skill.addEffect(new ResourceConsumeEffect(resourceManager, type, amount));
        }

        List<Map<?, ?>> effectMaps = section.getMapList("effects");
        for (Map<?, ?> raw : effectMaps) {
            SkillEffect effect = parseEffect(raw, plugin, statusManager, summonManager, threatManager);
            if (effect != null) skill.addEffect(effect);
        }

        return skill;
    }

    private static Targeter parseTargeter(ConfigurationSection section) {
        String type = section.getString("targeter", "self");
        return switch (type.toLowerCase()) {
            case "single" -> new SingleEntityTargeter(section.getDouble("range", 20));
            case "radius" -> new RadiusTargeter(section.getDouble("radius", 5), section.getBoolean("include_self", false));
            case "cone" -> new ConeTargeter(section.getDouble("range", 5), section.getDouble("angle", 90));
            default -> new SelfTargeter();
        };
    }

    private static SkillEffect parseEffect(Map<?, ?> raw, Plugin plugin, StatusManager statusManager, SummonManager summonManager, ThreatManager threatManager) {
        Object typeObj = raw.get("type");
        if (typeObj == null) return null;

        return switch (typeObj.toString().toLowerCase()) {
            case "damage" -> new DamageEffect(toDouble(raw.get("amount"), 1));
            case "heal" -> new HealEffect(toDouble(raw.get("amount"), 1));
            case "particle" -> {
                Color dustColor = null;
                if (raw.get("color") instanceof Map<?, ?> colorRaw) {
                    dustColor = Color.fromRGB(
                            clampByte(toInt(colorRaw.get("r"), 255)),
                            clampByte(toInt(colorRaw.get("g"), 255)),
                            clampByte(toInt(colorRaw.get("b"), 255))
                    );
                }
                yield new ParticleEffect(
                        Particle.valueOf(raw.get("particle").toString()),
                        toInt(raw.get("count"), 10),
                        dustColor,
                        (float) toDouble(raw.get("dust_size"), 1.0)
                );
            }
            case "potion" -> new PotionEffectApply(
                    PotionEffectType.getByName(raw.get("effect").toString()),
                    toInt(raw.get("duration_ticks"), 100),
                    toInt(raw.get("amplifier"), 0)
            );
            case "glow" -> new GlowEffect(
                    plugin,
                    ChatColor.valueOf(raw.get("color") == null ? "WHITE" : raw.get("color").toString().toUpperCase(Locale.ROOT)),
                    toInt(raw.get("duration_ticks"), 100)
            );
            case "knockback" -> new KnockbackEffect(toDouble(raw.get("strength"), 1));
            case "status" -> parseStatusEffect(raw, plugin, statusManager, summonManager, threatManager);
            case "projectile" -> parseProjectile(raw, plugin, statusManager, summonManager, threatManager);
            case "shape" -> parseShapeEffect(raw, plugin, statusManager, summonManager, threatManager);
            case "sequence" -> parseSequenceEffect(raw, plugin, statusManager, summonManager, threatManager);
            case "summon" -> parseSummon(raw, plugin, statusManager, summonManager, threatManager);
            case "dismiss_summons" -> new DismissSummonsEffect(summonManager);
            case "taunt" -> new TauntEffect(threatManager, toDouble(raw.get("amount"), 1000));
            default -> null;
        };
    }

    /**
     * Parses a `sequence` effect: an ordered chain of stages, each firing
     * only once the previous one's animation has finished (or after an
     * explicit delay). Typical use: two or three `shape` animations building
     * up, with the final stage adding a `damage`/`heal` alongside its shape.
     *
     * skills.yml:
     *   - type: sequence
     *     steps:
     *       - effects:
     *           - type: shape        # stage 1: windup glyph
     *             duration_ticks: 20
     *             layers: [...]
     *         # no delay_ticks given -> auto-waits for this shape's own
     *         # duration_ticks (20) before starting the next step
     *       - effects:
     *           - type: shape        # stage 2: the actual strike
     *             duration_ticks: 15
     *             layers: [...]
     *           - type: damage        # fires alongside stage 2's shape,
     *             amount: 12           # i.e. once stage 1 has finished
     *
     * If a step has more than one `shape` in its `effects:`, or you want the
     * gap to differ from the shape's own duration, set `delay_ticks:`
     * explicitly on that step instead of relying on the auto-detected one.
     * Set `wait_for_shape_duration: false` on a step to fire the next step
     * immediately (0 delay) despite that step containing a shape.
     */
    private static SkillEffect parseSequenceEffect(Map<?, ?> raw, Plugin plugin, StatusManager statusManager, SummonManager summonManager, ThreatManager threatManager) {
        List<SequenceEffect.Step> steps = new ArrayList<>();
        for (Map<?, ?> stepRaw : asMapList(raw.get("steps"))) {
            List<SkillEffect> stepEffects = new ArrayList<>();
            Integer shapeDuration = null;
            for (Map<?, ?> effectRaw : asMapList(stepRaw.get("effects"))) {
                SkillEffect effect = parseEffect(effectRaw, plugin, statusManager, summonManager, threatManager);
                if (effect != null) stepEffects.add(effect);
                if ("shape".equalsIgnoreCase(String.valueOf(effectRaw.get("type")))) {
                    shapeDuration = toInt(effectRaw.get("duration_ticks"), 40);
                }
            }

            int delay;
            if (stepRaw.containsKey("delay_ticks")) {
                delay = toInt(stepRaw.get("delay_ticks"), 0);
            } else if (shapeDuration != null && toBool(stepRaw.get("wait_for_shape_duration"), true)) {
                delay = shapeDuration;
            } else {
                delay = 0;
            }

            steps.add(new SequenceEffect.Step(stepEffects, delay));
        }
        return new SequenceEffect(plugin, steps);
    }

    /**
     * Parses a `shape` effect: one or more stacked, independently-animated
     * particle layers (see ShapeLayer/ShapeEffect), an anchor for where the
     * whole thing is centered, an optional forward `travel`, and an
     * optional hitbox that fires `on_hit` effects against anything it touches.
     *
     * skills.yml:
     *   - type: shape
     *     anchor: self          # self | self_fixed | target | cursor
     *     duration_ticks: 40
     *     interval_ticks: 2
     *     hit_radius: 1.2       # omit/0 to disable hit detection
     *     hit_once: true
     *     hit_interval_ticks: 10
     *     include_self: false
     *     cursor_range: 20
     *     travel:
     *       speed: 10
     *       max_distance: 15
     *       gravity: false
     *       collide_with_blocks: true
     *     on_hit:
     *       - type: damage
     *         amount: 10
     *     layers:
     *       - shape: ring
     *         radius: 2.0
     *         points: 24
     *         particle: SOUL_FIRE_FLAME
     *         rotate_deg_per_sec: 180
     */
    private static SkillEffect parseShapeEffect(Map<?, ?> raw, Plugin plugin, StatusManager statusManager, SummonManager summonManager, ThreatManager threatManager) {
        ShapeEffect.Anchor anchor = switch (raw.get("anchor") == null ? "self" : raw.get("anchor").toString().toLowerCase(Locale.ROOT)) {
            case "self_fixed" -> ShapeEffect.Anchor.SELF_FIXED;
            case "target" -> ShapeEffect.Anchor.TARGET;
            case "cursor" -> ShapeEffect.Anchor.CURSOR;
            case "cursor_locked" -> ShapeEffect.Anchor.CURSOR_LOCKED;
            default -> ShapeEffect.Anchor.SELF;
        };

        int durationTicks = toInt(raw.get("duration_ticks"), 40);
        int intervalTicks = toInt(raw.get("interval_ticks"), 2);
        double cursorRange = toDouble(raw.get("cursor_range"), 20);

        ShapeEffect.Travel travel = null;
        if (raw.get("travel") instanceof Map<?, ?> travelRaw) {
            travel = new ShapeEffect.Travel(
                    toDouble(travelRaw.get("speed"), 10),
                    toDouble(travelRaw.get("max_distance"), 15),
                    toBool(travelRaw.get("gravity"), false),
                    toBool(travelRaw.get("collide_with_blocks"), true)
            );
        }

        // Hit config can be given either as a nested `hit:` block (radius,
        // area, height, once, interval_ticks, include_self, effects) or as
        // the older flat top-level keys (hit_radius, hit_area, hit_height,
        // hit_once, hit_interval_ticks, include_self, on_hit). The nested
        // block, if present, takes priority field-by-field; any field it
        // doesn't set falls back to the flat/default value below, so both
        // styles keep working side by side.
        Map<?, ?> hitRaw = raw.get("hit") instanceof Map<?, ?> m ? m : null;

        double hitRadius = toDouble(hitRaw != null ? hitRaw.get("radius") : raw.get("hit_radius"), 0);
        Object hitAreaRaw = hitRaw != null && hitRaw.get("area") != null ? hitRaw.get("area") : raw.get("hit_area");
        ShapeEffect.HitArea hitArea = switch (hitAreaRaw == null ? "points" : hitAreaRaw.toString().toLowerCase(Locale.ROOT)) {
            case "disk" -> ShapeEffect.HitArea.DISK;
            case "sphere" -> ShapeEffect.HitArea.SPHERE;
            default -> ShapeEffect.HitArea.POINTS;
        };
        double hitHeight = toDouble(hitRaw != null && hitRaw.get("height") != null ? hitRaw.get("height") : raw.get("hit_height"), 2.0);
        boolean hitOnce = toBool(hitRaw != null && hitRaw.get("once") != null ? hitRaw.get("once") : raw.get("hit_once"), true);
        int hitIntervalTicks = toInt(hitRaw != null && hitRaw.get("interval_ticks") != null ? hitRaw.get("interval_ticks") : raw.get("hit_interval_ticks"), intervalTicks);
        boolean includeSelf = toBool(hitRaw != null && hitRaw.get("include_self") != null ? hitRaw.get("include_self") : raw.get("include_self"), false);

        double offsetX = 0, offsetY = 0, offsetZ = 0;
        if (raw.get("offset") instanceof Map<?, ?> offsetRaw) {
            offsetX = toDouble(offsetRaw.get("x"), 0);
            offsetY = toDouble(offsetRaw.get("y"), 0);
            offsetZ = toDouble(offsetRaw.get("z"), 0);
        }

        boolean debugHitbox = toBool(raw.get("debug_hitbox"), false);
        int debugHitboxPoints = toInt(raw.get("debug_hitbox_points"), 8);

        // visible_to: everyone (default, unchanged broadcast behavior) or
        // caster_only (a trap's telltale ring, visible only to whoever
        // placed it - see the Visibility enum's doc on ShapeEffect).
        // disarm_after_hit: end the whole effect the instant it lands its
        // first hit instead of running its full duration_ticks regardless -
        // what makes a long-lived trap a proper single-use trigger.
        ShapeEffect.Visibility visibleTo = "caster_only".equalsIgnoreCase(String.valueOf(raw.get("visible_to")))
                ? ShapeEffect.Visibility.CASTER_ONLY : ShapeEffect.Visibility.EVERYONE;
        boolean disarmAfterHit = toBool(hitRaw != null && hitRaw.get("disarm") != null ? hitRaw.get("disarm") : raw.get("disarm_after_hit"), false);

        Object onHitSource = hitRaw != null && hitRaw.get("effects") != null ? hitRaw.get("effects") : raw.get("on_hit");
        List<SkillEffect> onHit = parseEffectList(asMapList(onHitSource), plugin, statusManager, summonManager, threatManager);

        List<ShapeLayer> layers = new ArrayList<>();
        for (Map<?, ?> layerRaw : asMapList(raw.get("layers"))) {
            layers.add(parseShapeLayer(layerRaw));
        }

        return new ShapeEffect(plugin, layers, anchor, durationTicks, intervalTicks, cursorRange, travel,
                offsetX, offsetY, offsetZ, hitRadius, hitArea, hitHeight, hitOnce, hitIntervalTicks, includeSelf,
                onHit, visibleTo, disarmAfterHit, debugHitbox, debugHitboxPoints);
    }

    private static ShapeLayer parseShapeLayer(Map<?, ?> raw) {
        ShapeType type = ShapeType.valueOf(raw.get("shape").toString().toUpperCase(Locale.ROOT));
        Particle particle = Particle.valueOf(raw.get("particle").toString().toUpperCase(Locale.ROOT));
        boolean vertical = raw.get("plane") != null && raw.get("plane").toString().equalsIgnoreCase("vertical");

        // shape: parametric only - a custom curve defined by math formulas
        // instead of fixed Java code (see MathExpression). Left null for
        // every other shape type, which never reads them.
        String formulaX = raw.get("formula_x") != null ? raw.get("formula_x").toString() : null;
        String formulaY = raw.get("formula_y") != null ? raw.get("formula_y").toString() : null;
        String formulaZ = raw.get("formula_z") != null ? raw.get("formula_z").toString() : null;

        // Whether this shape's "long axis" points along the caster's aim
        // (a beam barrel) instead of world-up/world-grounded (a standing
        // pillar/platform). Applies to cylinder, helix, box, and parametric -
        // everything else is either always facing-relative (line/arc/cone)
        // or never is (ring's own `plane` field covers its case instead).
        boolean facingRelative = toBool(raw.get("facing_relative"), false);

        // BOX in its default (wireframe) mode spreads `points` across 12
        // edges - the generic default of 24 works out to just 2 per edge,
        // i.e. only the two corner endpoints, so it'd render as 8 floating
        // dots with no visible edges connecting them. Give box a default
        // dense enough to actually read as a box (96 / 12 = 8 per edge).
        int defaultPoints = type == ShapeType.BOX ? 96 : 24;

        ShapeGenerator.Params params = new ShapeGenerator.Params(
                toInt(raw.get("points"), defaultPoints),
                toDouble(raw.get("radius"), 2.0),
                toDouble(raw.get("length"), 3.0),
                toDouble(raw.get("width"), 2.0),
                toDouble(raw.get("height"), 2.0),
                toDouble(raw.get("turns"), 2.0),
                toDouble(raw.get("arc_degrees"), 90),
                toInt(raw.get("rings"), 3),
                vertical,
                toDouble(raw.get("y_offset"), 0),
                toBool(raw.get("random_distribution"), false),
                toDouble(raw.get("radius_jitter"), 0),
                formulaX, formulaY, formulaZ, facingRelative
        );

        ShapeLayer.ScaleAxis scaleAxis = switch (raw.get("scale_axis") == null ? "uniform"
                : raw.get("scale_axis").toString().toLowerCase(Locale.ROOT)) {
            case "radial" -> ShapeLayer.ScaleAxis.RADIAL;
            case "vertical" -> ShapeLayer.ScaleAxis.VERTICAL;
            default -> ShapeLayer.ScaleAxis.UNIFORM;
        };

        Color dustColor = null;
        if (raw.get("color") instanceof Map<?, ?> colorRaw) {
            dustColor = Color.fromRGB(
                    clampByte(toInt(colorRaw.get("r"), 255)),
                    clampByte(toInt(colorRaw.get("g"), 255)),
                    clampByte(toInt(colorRaw.get("b"), 255))
            );
        }
        float dustSize = (float) toDouble(raw.get("dust_size"), 1.0);

        // start_offset -> end_offset: an animated world-space translation of
        // this layer's own center, lerped over the effect's duration (same t
        // as scale_start/scale_end). Both default to {0,0,0} - i.e. no-op,
        // fully backward compatible with layers that don't set either.
        // e.g. start_offset: {x: 6} + end_offset: {x: 0} makes a ring start
        // 6 blocks off to the side and slide in to the shared center; a
        // pillar with start_offset: {y: 20} + end_offset: {y: 0} drops in
        // from high overhead instead of growing up from the ground.
        double offsetStartX = 0, offsetStartY = 0, offsetStartZ = 0;
        if (raw.get("start_offset") instanceof Map<?, ?> startOffsetRaw) {
            offsetStartX = toDouble(startOffsetRaw.get("x"), 0);
            offsetStartY = toDouble(startOffsetRaw.get("y"), 0);
            offsetStartZ = toDouble(startOffsetRaw.get("z"), 0);
        }
        double offsetEndX = 0, offsetEndY = 0, offsetEndZ = 0;
        if (raw.get("end_offset") instanceof Map<?, ?> endOffsetRaw) {
            offsetEndX = toDouble(endOffsetRaw.get("x"), 0);
            offsetEndY = toDouble(endOffsetRaw.get("y"), 0);
            offsetEndZ = toDouble(endOffsetRaw.get("z"), 0);
        }

        // rotation: a constant base tilt (degrees), applied once - lets a
        // shape be oriented at any fixed angle instead of only ever lying
        // flat in its default plane (e.g. tilt a ring 45° so it's not flat
        // on the ground). rotate_x/z_deg_per_sec: continuous spin on those
        // axes, same idea as the original rotate_deg_per_sec (Y/yaw) which
        // is kept as-is for backward compatibility - together these give
        // free rotation on any axis instead of just spinning flat.
        double baseRotationX = 0, baseRotationY = 0, baseRotationZ = 0;
        if (raw.get("rotation") instanceof Map<?, ?> rotationRaw) {
            baseRotationX = toDouble(rotationRaw.get("x"), 0);
            baseRotationY = toDouble(rotationRaw.get("y"), 0);
            baseRotationZ = toDouble(rotationRaw.get("z"), 0);
        }

        return new ShapeLayer(
                type,
                params,
                particle,
                toInt(raw.get("particle_count"), 1),
                dustColor,
                dustSize,
                baseRotationX, baseRotationY, baseRotationZ,
                toDouble(raw.get("rotate_x_deg_per_sec"), 0),
                toDouble(raw.get("rotate_deg_per_sec"), 0),
                toDouble(raw.get("rotate_z_deg_per_sec"), 0),
                toDouble(raw.get("scale_start"), 1.0),
                toDouble(raw.get("scale_end"), 1.0),
                scaleAxis,
                toDouble(raw.get("pulsate_amplitude"), 0),
                toDouble(raw.get("pulsate_frequency"), 1.0),
                toDouble(raw.get("rise_per_sec"), 0),
                offsetStartX, offsetStartY, offsetStartZ,
                offsetEndX, offsetEndY, offsetEndZ
        );
    }

    private static int clampByte(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /**
     * skills.yml:
     *   - type: status
     *     status: frozen             # reference a definition from the top-level `statuses:` section
     *
     * ...or define one inline, one-off (no `behavior` key means "must be a
     * reference" - see the `status:` case below):
     *   - type: status
     *     behavior: dash              # built-in: dash | frozen | (omit for a purely cosmetic status)
     *     duration_ticks: 6
     *     distance: 8                 # dash-specific
     *     horizontal_only: true       # dash-specific
     *     disable_gravity: true       # dash-specific
     *     tick_interval_ticks: 1
     *     on_start: [ ... ]           # any effects - particle, shape, sequence, another status...
     *     on_tick: [ ... ]            # fires every tick_interval_ticks while active
     *     on_expire: [ ... ]          # fires once when duration runs out (or StatusManager.remove())
     *
     * Applies to context.getTargets() - same as damage/particle/etc. - so a
     * skill's own `effects:` needs targeter: self for a status effect on the
     * caster (e.g. dash), while a projectile's/shape's nested `effects:`/
     * `on_hit:` naturally targets whatever it actually hit (e.g. an ice
     * bolt inflicting frozen on impact).
     */
    private static SkillEffect parseStatusEffect(Map<?, ?> raw, Plugin plugin, StatusManager statusManager, SummonManager summonManager, ThreatManager threatManager) {
        if (raw.get("behavior") != null) {
            String fallbackId = raw.get("status") != null ? raw.get("status").toString()
                    : raw.get("behavior").toString();
            return new StatusEffect(statusManager, parseStatus(raw, fallbackId, plugin, statusManager, summonManager, threatManager));
        }

        Object refObj = raw.get("status");
        if (refObj == null) {
            throw new IllegalArgumentException(
                    "A 'status' effect needs either a 'behavior' (inline definition) or a 'status' id (reference).");
        }
        String refId = refObj.toString();
        Status status = statusManager.getDefinition(refId);
        if (status == null) {
            throw new IllegalArgumentException("Unknown status: " + refId
                    + " (not found under the top-level 'statuses:' section - check it's spelled the same there).");
        }
        return new StatusEffect(statusManager, status);
    }

    /** Shared by both the top-level `statuses:` registry entries and inline `type: status` definitions. */
    private static Status parseStatus(Map<?, ?> raw, String fallbackId, Plugin plugin, StatusManager statusManager, SummonManager summonManager, ThreatManager threatManager) {
        String id = raw.get("id") != null ? raw.get("id").toString() : fallbackId;
        int durationTicks = toInt(raw.get("duration_ticks"), 100);
        int tickIntervalTicks = toInt(raw.get("tick_interval_ticks"), 4);
        boolean refreshable = toBool(raw.get("refreshable"), true);

        StatusBehavior behavior = switch (raw.get("behavior") == null ? "none"
                : raw.get("behavior").toString().toLowerCase(Locale.ROOT)) {
            case "dash" -> new DashStatusBehavior(
                    toDouble(raw.get("distance"), 6),
                    durationTicks,
                    toBool(raw.get("horizontal_only"), true),
                    toBool(raw.get("disable_gravity"), true)
            );
            case "frozen" -> new FrozenStatusBehavior();
            default -> null;
        };

        return new Status(
                id,
                durationTicks,
                tickIntervalTicks,
                refreshable,
                behavior,
                parseEffectList(asMapList(raw.get("on_start")), plugin, statusManager, summonManager, threatManager),
                parseEffectList(asMapList(raw.get("on_tick")), plugin, statusManager, summonManager, threatManager),
                parseEffectList(asMapList(raw.get("on_expire")), plugin, statusManager, summonManager, threatManager)
        );
    }

    private static List<SkillEffect> parseEffectList(List<Map<?, ?>> rawList, Plugin plugin, StatusManager statusManager, SummonManager summonManager, ThreatManager threatManager) {
        List<SkillEffect> result = new ArrayList<>();
        for (Map<?, ?> effectRaw : rawList) {
            SkillEffect effect = parseEffect(effectRaw, plugin, statusManager, summonManager, threatManager);
            if (effect != null) result.add(effect);
        }
        return result;
    }

    /**
     * A projectile's `effects:` list is parsed exactly like a skill's own
     * top-level list (same parseEffect call, so it can nest anything - even
     * another projectile) and applied to whatever the projectile hits,
     * rather than to context.getTargets() the way a normal effect would.
     */
    private static SkillEffect parseProjectile(Map<?, ?> raw, Plugin plugin, StatusManager statusManager, SummonManager summonManager, ThreatManager threatManager) {
        Particle particle = raw.get("particle") != null
                ? Particle.valueOf(raw.get("particle").toString())
                : null;

        // Same nested-vs-flat convenience as `shape`: a `hit:` block with
        // `radius`/`effects` takes priority; either flat `hit_radius` /
        // top-level `effects` (the older style) still works if `hit:` is
        // absent or leaves a field unset.
        Map<?, ?> hitRaw = raw.get("hit") instanceof Map<?, ?> m ? m : null;

        Object onHitSource = hitRaw != null && hitRaw.get("effects") != null ? hitRaw.get("effects") : raw.get("effects");
        List<SkillEffect> onHit = parseEffectList(asMapList(onHitSource), plugin, statusManager, summonManager, threatManager);

        double hitRadius = toDouble(hitRaw != null && hitRaw.get("radius") != null ? hitRaw.get("radius") : raw.get("hit_radius"), 1.0);
        int pierce = toInt(hitRaw != null && hitRaw.get("pierce") != null ? hitRaw.get("pierce") : raw.get("pierce"), 1);

        return new ProjectileEffect(
                plugin,
                particle,
                toDouble(raw.get("speed"), 15),
                toDouble(raw.get("max_distance"), 20),
                hitRadius,
                pierce,
                toBool(raw.get("gravity"), false),
                toBool(raw.get("collide_with_blocks"), true),
                onHit
        );
    }

    /**
     * skills.yml:
     *   - type: summon
     *     entity: SKELETON        # any EntityType name
     *     count: 1                # how many to spawn per cast
     *     spawn_radius: 1.5       # scattered within this many blocks of the caster, not stacked on top of them
     *     name: "Skeletal Thrall" # custom nameplate - omit for the mob's default vanilla name
     *     health: 20              # omit/0 to leave the entity type's own vanilla default
     *     main_hand: BOW          # omit for the entity type's vanilla default (often nothing)
     *     helmet: WITHER_SKELETON_SKULL
     *     duration_ticks: -1      # -1 (default) = lives until it dies or is dismissed; a positive value despawns it after
     *     max_active: 4           # 0/omit = uncapped
     *     on_cap_exceeded: refuse # refuse (default) | dismiss_oldest - what happens casting past max_active
     *     aggro_radius: 12        # 0 disables auto-aggro entirely (a purely decorative/follow-only summon)
     *     follow_radius: 10       # how far it's allowed to wander from the caster before pathing back, while idle
     *     move_speed: 1.0         # Pathfinder speed multiplier used when walking back to the caster
     *     ai_interval_ticks: 10   # how often the follow/aggro check itself runs - see SummonAiTask
     *     on_summon:
     *       - type: particle      # fires once per spawned minion, targeting that minion
     *         particle: SOUL
     *         count: 15
     *
     * Spawns real Bukkit mobs with their own vanilla AI (not a simulated
     * entity like `projectile`) - see SummonEffect's class doc for why, and
     * SummonManager/SummonAiTask/SummonTargetListener for where the cap,
     * lifespan, follow/aggro, and "never attacks its own owner" behavior
     * actually live.
     */
    private static SkillEffect parseSummon(Map<?, ?> raw, Plugin plugin, StatusManager statusManager, SummonManager summonManager, ThreatManager threatManager) {
        EntityType entityType = EntityType.valueOf(raw.get("entity").toString().toUpperCase(Locale.ROOT));

        Material mainHand = raw.get("main_hand") != null
                ? Material.valueOf(raw.get("main_hand").toString().toUpperCase(Locale.ROOT)) : null;
        Material helmet = raw.get("helmet") != null
                ? Material.valueOf(raw.get("helmet").toString().toUpperCase(Locale.ROOT)) : null;

        // As with `shape`/`projectile`'s `hit:` block, `cap:`/`ai:` can be
        // given nested or as the older flat top-level keys; nested wins
        // field-by-field, flat/defaults fill in anything it omits.
        Map<?, ?> capRaw = raw.get("cap") instanceof Map<?, ?> m ? m : null;
        Map<?, ?> aiRaw = raw.get("ai") instanceof Map<?, ?> m ? m : null;

        int maxActive = toInt(capRaw != null && capRaw.get("max") != null ? capRaw.get("max") : raw.get("max_active"), 0);
        Object onCapExceededRaw = capRaw != null && capRaw.get("on_exceeded") != null ? capRaw.get("on_exceeded") : raw.get("on_cap_exceeded");
        SummonManager.CapBehavior onCapExceeded = "dismiss_oldest".equalsIgnoreCase(String.valueOf(onCapExceededRaw))
                ? SummonManager.CapBehavior.DISMISS_OLDEST : SummonManager.CapBehavior.REFUSE;

        double aggroRadius = toDouble(aiRaw != null && aiRaw.get("aggro_radius") != null ? aiRaw.get("aggro_radius") : raw.get("aggro_radius"), 12);
        double followRadius = toDouble(aiRaw != null && aiRaw.get("follow_radius") != null ? aiRaw.get("follow_radius") : raw.get("follow_radius"), 10);
        double moveSpeed = toDouble(aiRaw != null && aiRaw.get("move_speed") != null ? aiRaw.get("move_speed") : raw.get("move_speed"), 1.0);
        int aiIntervalTicks = toInt(aiRaw != null && aiRaw.get("interval_ticks") != null ? aiRaw.get("interval_ticks") : raw.get("ai_interval_ticks"), 10);

        List<SkillEffect> onSummon = parseEffectList(asMapList(raw.get("on_summon")), plugin, statusManager, summonManager, threatManager);

        return new SummonEffect(
                summonManager,
                entityType,
                toInt(raw.get("count"), 1),
                toDouble(raw.get("spawn_radius"), 1.5),
                raw.get("name") != null ? raw.get("name").toString() : null,
                toDouble(raw.get("health"), 0),
                mainHand,
                helmet,
                toInt(raw.get("duration_ticks"), -1),
                maxActive,
                onCapExceeded,
                aggroRadius,
                followRadius,
                moveSpeed,
                aiIntervalTicks,
                onSummon
        );
    }

    private static List<Map<?, ?>> asMapList(Object o) {
        List<Map<?, ?>> result = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) result.add(map);
            }
        }
        return result;
    }

    /**
     * Sound.valueOf(String) is deprecated-for-removal on current Paper -
     * sounds are registry entries now. Config still uses the familiar
     * enum-style name (ENTITY_BLAZE_SHOOT) since that's what everyone
     * copy-pastes from the old Sound enum; this just converts it to the
     * namespaced key format (entity.blaze.shoot) the registry expects.
     */
    private static Sound resolveSound(String enumStyleName) {
        NamespacedKey key = NamespacedKey.minecraft(enumStyleName.toLowerCase(Locale.ROOT).replace('_', '.'));
        Sound sound = Registry.SOUNDS.get(key);
        if (sound == null) {
            throw new IllegalArgumentException("Unknown sound: " + enumStyleName);
        }
        return sound;
    }

    private static double toDouble(Object o, double def) {
        return o == null ? def : Double.parseDouble(o.toString());
    }

    private static int toInt(Object o, int def) {
        return o == null ? def : Integer.parseInt(o.toString());
    }

    private static boolean toBool(Object o, boolean def) {
        return o == null ? def : Boolean.parseBoolean(o.toString());
    }
}
