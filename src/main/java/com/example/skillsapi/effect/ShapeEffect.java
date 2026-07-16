package com.example.skillsapi.effect;

import com.example.skillsapi.shape.ShapeGenerator;
import com.example.skillsapi.shape.ShapeLayer;
import com.example.skillsapi.skill.SkillContext;
import com.example.skillsapi.skill.SkillEffect;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The "flexible skill effect" building block: renders one or more stacked
 * {@link ShapeLayer}s (a particle shape, or several composited together)
 * over a span of ticks, optionally moving the whole thing forward like a
 * projectile, and optionally hurting/affecting whatever it touches.
 *
 * This is deliberately generic - a spinning ground rune, a rising pillar, an
 * expanding dome, a traveling blade-fan, or all of them stacked at once are
 * all just different `layers:` + `travel:` config on this one effect type.
 * See SkillConfigParser#parseShapeEffect for the YAML schema.
 */
public class ShapeEffect implements SkillEffect {

    // Cache key used by Anchor.CURSOR_LOCKED - stashed in the SkillContext's
    // generic data map so every `shape` step sharing that same context (e.g.
    // every stage of a `sequence`, like trinity_pillar's rings + pillar)
    // resolves to the exact same point instead of each stage independently
    // re-raycasting the caster's aim at whatever moment *that* stage happens
    // to start. A fresh SkillContext is created per cast, so this never
    // leaks a locked point across two different casts of the skill.
    // Package-visible (not private) so other effects sharing a SkillContext
    // within one `sequence` - e.g. `rain` - can anchor to this exact same
    // resolved point instead of independently re-raytracing the caster's
    // crosshair a moment later, which would drift if the caster moves or
    // turns between sequence steps.
    static final String CURSOR_LOCK_KEY = "shape_effect_cursor_locked_anchor";

    public enum Anchor { SELF, SELF_FIXED, TARGET, CURSOR, CURSOR_LOCKED }

    /**
     * How the hitbox is actually shaped, independent of what the layers
     * visually draw. POINTS (default) hit-tests a sphere of hit_radius
     * around every rendered particle point - good for thin traveling
     * shapes (line/arc/cone) where the visual line basically *is* the
     * hitbox. DISK/SPHERE ignore the rendered points entirely and instead
     * test a single flat disk or full sphere of hit_radius centered on the
     * effect's (offset-adjusted) center - what you want when the layer is
     * just an outline (e.g. a `ring`) but the hit area should be the whole
     * area it encloses, not just a thin band along the line itself.
     */
    public enum HitArea { POINTS, DISK, SPHERE }

    /**
     * Who actually sees the rendered particles. EVERYONE (default) is the
     * original behavior - a normal broadcast via World#spawnParticle, seen
     * by anyone nearby. CASTER_ONLY sends the particle packets to just the
     * caster (Player#spawnParticle) - invisible to everyone else. This is
     * what makes a trap: the caster can see exactly where their own ring is
     * placed, but another player walking toward it sees nothing until the
     * hitbox (which is server-side and applies regardless of visibility)
     * actually triggers on them. Falls back to EVERYONE if the caster isn't
     * a Player (e.g. a mob casting a skill) - "only visible to X" doesn't
     * mean anything for a non-player caster.
     */
    public enum Visibility { EVERYONE, CASTER_ONLY }

    public record Travel(double blocksPerSecond, double maxDistance, boolean gravity, boolean collideWithBlocks) {}

    private final Plugin plugin;
    private final List<ShapeLayer> layers;
    private final Anchor anchor;
    private final int durationTicks;
    private final int intervalTicks;
    private final double cursorRange;
    private final Travel travel;

    // Offset applied to wherever the anchor resolves to, every tick.
    // offsetX/offsetZ are local-space (right/forward, relative to the
    // caster's current facing) - "1 block forward" stays forward as the
    // caster turns. offsetY is plain world-Y, not local "up": local up
    // tilts away from true vertical whenever the caster isn't looking
    // exactly level (computeBasis derives it from the full 3D look
    // direction, pitch included), which is essentially always true for
    // anything anchored at a ground target - "1.5 blocks up" should mean
    // 1.5 blocks of real height regardless of how steeply the caster's
    // aiming, not something that only lifts straight up when they happen
    // to be looking flat ahead.
    private final double offsetX;
    private final double offsetY;
    private final double offsetZ;

    private final double hitRadius; // <= 0 disables hit detection entirely
    private final HitArea hitArea;
    private final double hitHeight; // DISK only: vertical tolerance, checked both above and below center
    private final boolean hitOnce;
    // Cadence of the hit-scan itself (the getNearbyLivingEntities query and
    // everything downstream of it) - fully independent of intervalTicks,
    // which only governs how often the shape re-renders/moves. Defaults to
    // intervalTicks (scan every render tick), but can be set lower to check
    // for hits more often than the shape actually redraws (e.g. a slow
    // 20-tick particle emit with a 1-tick hit check so nothing can dodge
    // between renders), or higher to check less often than it redraws
    // (render every tick for smooth visuals, only pay for a scan every few
    // ticks). Both directions run off the same 1-tick-resolution base loop
    // in apply() below, so neither has to be a multiple of the other.
    private final int hitIntervalTicks;
    private final boolean includeSelf;
    private final List<SkillEffect> onHit;

    private final Visibility visibleTo;
    // Ends the whole effect (rendering AND hit-scanning both stop) the
    // instant it lands its first hit, instead of running out its full
    // duration_ticks regardless. Combined with a long duration_ticks and
    // visible_to: caster_only, this is what turns a `shape` effect into a
    // proper single-use trap: it sits there invisibly (to everyone but the
    // caster) for as long as it takes someone to walk into it, then
    // triggers on_hit once and disappears for good rather than lingering
    // to catch a second victim.
    private final boolean disarmAfterHit;

    // Debug: outlines the actual hit area so you can see exactly how fat/thin
    // the hitbox really is instead of guessing from the visual particles alone.
    private final boolean debugHitbox;
    private final int debugHitboxPoints;

    public ShapeEffect(Plugin plugin, List<ShapeLayer> layers, Anchor anchor, int durationTicks, int intervalTicks,
                        double cursorRange, Travel travel, double offsetX, double offsetY, double offsetZ,
                        double hitRadius, HitArea hitArea, double hitHeight, boolean hitOnce, int hitIntervalTicks,
                        boolean includeSelf, List<SkillEffect> onHit, Visibility visibleTo, boolean disarmAfterHit,
                        boolean debugHitbox, int debugHitboxPoints) {
        this.plugin = plugin;
        this.layers = layers;
        this.anchor = anchor;
        this.durationTicks = Math.max(1, durationTicks);
        this.intervalTicks = Math.max(1, intervalTicks);
        this.cursorRange = cursorRange;
        this.travel = travel;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.hitRadius = hitRadius;
        this.hitArea = hitArea;
        this.hitHeight = hitHeight;
        this.hitOnce = hitOnce;
        this.hitIntervalTicks = Math.max(1, hitIntervalTicks);
        this.includeSelf = includeSelf;
        this.onHit = onHit;
        this.visibleTo = visibleTo;
        this.disarmAfterHit = disarmAfterHit;
        this.debugHitbox = debugHitbox;
        this.debugHitboxPoints = Math.max(3, debugHitboxPoints);
    }

    @Override
    public void apply(SkillContext context) {
        LivingEntity caster = context.getCaster();
        Location initial = resolveInitialAnchor(context);
        if (initial == null || initial.getWorld() == null) return;

        // Per-real-tick, not per-render-tick: travel/gravity now advance every
        // game tick regardless of how often the shape actually re-renders or
        // gets hit-scanned (see the loop below), so motion stays smooth even
        // when interval_ticks is set high for a slow-emitting shape.
        final Vector travelVelocityPerTick = travel != null
                ? caster.getEyeLocation().getDirection().normalize().multiply(travel.blocksPerSecond() / 20.0)
                : null;

        double durationSeconds = durationTicks / 20.0;

        // Facing basis: SELF tracks the caster's *current* look every tick,
        // matching its own live position tracking - that's the whole point
        // of a `laser_beam`-style effect meant to follow your aim while
        // it's active. Every other anchor's *position* is already fixed
        // (SELF_FIXED/CURSOR/CURSOR_LOCKED never re-track the caster's
        // location, and even TARGET only tracks the target, not the
        // caster) - orientation should be just as fixed, captured once
        // here rather than silently recomputed from the caster's live eye
        // direction every tick regardless of anchor. That mismatch was the
        // actual bug: a `self_fixed` + `travel` shape's *position* moved in
        // a clean straight line, but any `offset` or facing-relative shape
        // (arc/line/cone/...) still measured itself against a `right/up`
        // basis that rotated with the caster's ongoing head movement, so it
        // visibly bobbed/drifted even though the path itself didn't.
        final Vector[] fixedBasis = computeBasis(caster.getEyeLocation().getDirection());

        new BukkitRunnable() {
            private final Location center = initial.clone();
            private int elapsedTicks = 0;
            private double travelled = 0;
            private final Set<UUID> alreadyHit = new HashSet<>();

            // Both of these bank real ticks toward their own next action and
            // fire independently of one another - interval_ticks (rendering)
            // and hit_interval_ticks (hit-scanning) are two separate clocks
            // sharing the same 1-tick-resolution runnable as their base, not
            // one driving the other. Each starts pre-loaded with its own
            // threshold so the first run() triggers both immediately, and
            // each subtracts (rather than resets to 0) so neither drifts when
            // its interval doesn't evenly divide the other's.
            private int renderAccumulator = intervalTicks;
            private int hitScanAccumulator = hitIntervalTicks;

            // The points a POINTS-mode hit-scan tests against. Only
            // recomputed on render ticks (that's genuinely all there is to
            // test - nothing new was drawn in between); a hit-scan tick that
            // doesn't coincide with a render tick reuses whatever was last
            // actually rendered, same as the debug outline would show.
            private List<Location> lastRenderedPoints = null;

            @Override
            public void run() {
                if (!caster.isValid() || elapsedTicks >= durationTicks) {
                    cancel();
                    return;
                }

                if (anchor == Anchor.SELF) {
                    center.setX(caster.getLocation().getX());
                    center.setY(caster.getLocation().getY());
                    center.setZ(caster.getLocation().getZ());
                } else if (anchor == Anchor.TARGET) {
                    LivingEntity target = firstTarget(context);
                    if (target != null && target.isValid()) {
                        center.setX(target.getLocation().getX());
                        center.setY(target.getLocation().getY());
                        center.setZ(target.getLocation().getZ());
                    }
                }

                // Full 3D facing basis (right/up/forward), not flattened to
                // horizontal - a facing-relative shape (line/arc/cone/
                // vertical-ring) needs to point exactly where the caster is
                // looking, including straight up/down, not just their yaw.
                // Live for SELF only (see the fixedBasis comment above) -
                // every other anchor reuses the one captured at cast time.
                // Computed up here (before the collision check) so the
                // offset can be applied to the point that's actually
                // block-tested below, not just to the point that gets
                // rendered - otherwise a self_fixed anchor sitting at the
                // caster's feet with e.g. `offset: {y: 1.5}` would have its
                // *visual* sitting safely at chest height while the
                // *collision test* still ran against the un-lifted,
                // ground-level point, cancelling on a downward-aimed cast
                // almost immediately despite the rendered arc never coming
                // near the floor.
                Vector right, up, forward;
                if (anchor == Anchor.SELF) {
                    Vector[] basis = computeBasis(caster.getEyeLocation().getDirection());
                    right = basis[0];
                    up = basis[1];
                    forward = basis[2];
                } else {
                    right = fixedBasis[0];
                    up = fixedBasis[1];
                    forward = fixedBasis[2];
                }

                // offsetY intentionally does NOT go through `up` here - `up`
                // is derived from the caster's full 3D look direction
                // (computeBasis), so it tilts away from true vertical
                // whenever the caster isn't looking exactly level, which is
                // essentially always for anything anchored at a ground
                // target (you're aiming down at it). "Lift N blocks into
                // the sky" should mean N blocks of real world-Y regardless
                // of the caster's pitch, so offsetY is added straight to Y
                // directly instead. offsetX/offsetZ stay facing-relative
                // through right/forward - a horizontal nudge still makes
                // sense to keep tied to which way the caster's facing.
                Location offsetPoint = center.clone();
                if (offsetX != 0 || offsetZ != 0) {
                    offsetPoint.add(right.clone().multiply(offsetX));
                    offsetPoint.add(forward.clone().multiply(offsetZ));
                }
                offsetPoint.add(0, offsetY, 0);

                if (travelVelocityPerTick != null) {
                    // A single point-sample (offsetPoint.getBlock().isSolid())
                    // only catches a block if a sample happens to land inside
                    // it - at typical travel speeds (e.g. 0.5 blocks/tick @
                    // 10 blocks/sec) the point can easily step clean over a
                    // thin wall, or even a full block at a shallow angle,
                    // without ever sampling from inside it. Raytracing the
                    // actual segment this tick is about to move through
                    // catches anything the path crosses, not just where it
                    // happens to land.
                    //
                    // ignorePassableBlocks is explicitly false here: the
                    // 3-arg World#rayTraceBlocks overload defaults that to
                    // true, which treats anything without a full-cube
                    // collision shape - stairs, slabs, fences, walls,
                    // carpets, snow layers, etc. - as transparent to the
                    // ray and skips it entirely, even though it's visually
                    // and gameplay-wise solid. Passing false here means the
                    // travel actually stops at those too, not just plain
                    // full blocks.
                    double stepLength = travelVelocityPerTick.length();
                    if (travel.collideWithBlocks() && stepLength > 0) {
                        var hit = offsetPoint.getWorld().rayTraceBlocks(
                                offsetPoint, travelVelocityPerTick.clone().normalize(), stepLength,
                                FluidCollisionMode.NEVER, false);
                        if (hit != null) {
                            cancel();
                            return;
                        }
                    }
                    center.add(travelVelocityPerTick);
                    travelled += travelVelocityPerTick.length();
                    if (travel.gravity()) {
                        travelVelocityPerTick.setY(travelVelocityPerTick.getY() - 0.02);
                    }
                    if (travelled >= travel.maxDistance()) {
                        cancel();
                        // still render/hit-check this final tick before stopping next time
                    }
                }

                // Apply the configured offset on a *clone* - center itself must stay
                // the pure anchor/travel point, or the offset would stack every tick.
                // Recomputed against center's post-travel position (offsetPoint above
                // was only this tick's pre-travel collision probe).
                Location renderCenter = center.clone();
                if (offsetX != 0 || offsetZ != 0) {
                    renderCenter.add(right.clone().multiply(offsetX));
                    renderCenter.add(forward.clone().multiply(offsetZ));
                }
                renderCenter.add(0, offsetY, 0);

                double elapsedSeconds = elapsedTicks / 20.0;

                boolean checkShapeCollision = travel != null && travel.collideWithBlocks();

                boolean shouldRender = renderAccumulator >= intervalTicks;
                if (shouldRender) {
                    renderAccumulator -= intervalTicks;

                    // Points get collected (not just rendered) whenever a
                    // POINTS-mode hitbox needs them for its own hit-scan, or
                    // whenever block collision is on - the center-path
                    // raytrace above only catches the anchor's own travel
                    // path tunneling through a block; it says nothing about
                    // a *wide* layer (e.g. a 3-block-radius arc) whose
                    // outer points can poke into a block well off to the
                    // side of that path, which the center alone would never
                    // detect. Checking the actual rendered points here
                    // catches that spread instead of just the anchor.
                    boolean collectPoints = (hitRadius > 0 && hitArea == HitArea.POINTS) || checkShapeCollision;
                    List<Location> allPoints = collectPoints ? new ArrayList<>() : null;
                    List<Player> recipients = (visibleTo == Visibility.CASTER_ONLY && caster instanceof Player p)
                            ? List.of(p) : null;
                    for (ShapeLayer layer : layers) {
                        List<Location> points = layer.render(renderCenter, right, up, forward, elapsedSeconds, durationSeconds);
                        layer.spawnParticles(points, recipients);
                        if (allPoints != null) allPoints.addAll(points);
                    }
                    if (hitRadius > 0 && hitArea == HitArea.POINTS) lastRenderedPoints = allPoints;

                    if (checkShapeCollision && allPoints != null) {
                        for (Location point : allPoints) {
                            if (point.getBlock().getType().isSolid()) {
                                cancel();
                                return;
                            }
                        }
                    }
                }

                boolean shouldHitScan = hitRadius > 0 && hitScanAccumulator >= hitIntervalTicks;
                if (shouldHitScan) {
                    hitScanAccumulator -= hitIntervalTicks;

                    Set<LivingEntity> candidates = hitArea == HitArea.POINTS
                            ? nearbyAroundPoints(lastRenderedPoints)
                            : nearbyAroundArea(renderCenter);

                    if (debugHitbox) {
                        if (hitArea == HitArea.POINTS) spawnPointHitboxDebug(lastRenderedPoints);
                        else spawnAreaHitboxDebug(renderCenter);
                    }

                    if (!candidates.isEmpty()) handleHits(candidates);
                } else if (debugHitbox && hitRadius > 0 && shouldRender) {
                    // Not a scan tick, but a fresh render just happened - redraw
                    // the outline against the latest points so the preview
                    // doesn't lag the particles it's meant to overlay.
                    if (hitArea == HitArea.POINTS) spawnPointHitboxDebug(lastRenderedPoints);
                    else spawnAreaHitboxDebug(renderCenter);
                }

                renderAccumulator += 1;
                if (hitRadius > 0) hitScanAccumulator += 1;

                elapsedTicks += 1;
            }

            /** POINTS mode: union of everything within hit_radius of any rendered point. */
            private Set<LivingEntity> nearbyAroundPoints(List<Location> points) {
                Set<LivingEntity> nearby = new HashSet<>();
                if (points == null) return nearby;
                for (Location loc : points) {
                    nearby.addAll(loc.getWorld().getNearbyLivingEntities(loc, hitRadius));
                }
                return nearby;
            }

            /** DISK/SPHERE mode: a single area check centered on the effect, ignoring the rendered points. */
            private Set<LivingEntity> nearbyAroundArea(Location origin) {
                double verticalHalfExtent = hitArea == HitArea.DISK ? hitHeight : hitRadius;
                Set<LivingEntity> result = new HashSet<>();
                for (LivingEntity entity : origin.getWorld().getNearbyLivingEntities(origin, hitRadius, verticalHalfExtent, hitRadius)) {
                    Location loc = entity.getLocation();
                    double dx = loc.getX() - origin.getX();
                    double dz = loc.getZ() - origin.getZ();
                    if (hitArea == HitArea.DISK) {
                        double horizontal = Math.sqrt(dx * dx + dz * dz);
                        if (horizontal <= hitRadius && Math.abs(loc.getY() - origin.getY()) <= hitHeight) {
                            result.add(entity);
                        }
                    } else { // SPHERE
                        double dy = loc.getY() - origin.getY();
                        if (dx * dx + dy * dy + dz * dz <= hitRadius * hitRadius) {
                            result.add(entity);
                        }
                    }
                }
                return result;
            }

            private void spawnPointHitboxDebug(List<Location> points) {
                if (points == null) return;
                List<Vector> outline = ShapeGenerator.wireframeSphere(debugHitboxPoints, hitRadius);
                Particle.DustOptions dust = new Particle.DustOptions(Color.RED, 1.0F);
                for (Location point : points) {
                    for (Vector offset : outline) {
                        Location debugLoc = point.clone().add(offset);
                        debugLoc.getWorld().spawnParticle(Particle.DUST, debugLoc, 1, 0, 0, 0, 0, dust);
                    }
                }
            }

            /** Draws the disk (flat ring at the radius, plus a top/bottom ring hinting the vertical tolerance) or sphere (wireframe ball). */
            private void spawnAreaHitboxDebug(Location origin) {
                Particle.DustOptions dust = new Particle.DustOptions(Color.RED, 1.0F);
                List<Vector> outline = hitArea == HitArea.DISK
                        ? ShapeGenerator.ring(debugHitboxPoints, hitRadius, false)
                        : ShapeGenerator.wireframeSphere(debugHitboxPoints, hitRadius);
                for (Vector offset : outline) {
                    spawnDebugDust(origin.clone().add(offset), dust);
                }
                if (hitArea == HitArea.DISK && hitHeight > 0) {
                    for (Vector offset : ShapeGenerator.ring(debugHitboxPoints, hitRadius, false)) {
                        spawnDebugDust(origin.clone().add(offset).add(0, hitHeight, 0), dust);
                        spawnDebugDust(origin.clone().add(offset).add(0, -hitHeight, 0), dust);
                    }
                }
            }

            private void spawnDebugDust(Location loc, Particle.DustOptions dust) {
                loc.getWorld().spawnParticle(Particle.DUST, loc, 1, 0, 0, 0, 0, dust);
            }

            private void handleHits(Set<LivingEntity> nearby) {
                // No per-entity cooldown check needed here anymore: this method
                // only ever runs once every hitIntervalTicks (see the
                // hitScanAccumulator gate above), so every candidate present in
                // a scan is, by construction, due for a hit. hitOnce is the only
                // remaining per-entity filter, for effects that should connect
                // with a given target a single time over their whole duration.
                List<LivingEntity> toHit = nearby.stream()
                        .filter(e -> includeSelf || !e.equals(caster))
                        .filter(e -> !hitOnce || !alreadyHit.contains(e.getUniqueId()))
                        .collect(Collectors.toList());

                if (toHit.isEmpty()) return;

                if (hitOnce) {
                    for (LivingEntity target : toHit) {
                        alreadyHit.add(target.getUniqueId());
                    }
                }

                SkillContext hitContext = new SkillContext(caster, context.getSkill());
                hitContext.setTargets(toHit);
                for (SkillEffect effect : onHit) {
                    effect.apply(hitContext);
                }

                if (disarmAfterHit) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private LivingEntity firstTarget(SkillContext context) {
        List<LivingEntity> targets = context.getTargets();
        return (targets == null || targets.isEmpty()) ? null : targets.get(0);
    }

    /**
     * An orthonormal {right, up, forward} basis from a raw look direction -
     * shared by the once-per-cast computation (every anchor except SELF)
     * and the live per-tick one (SELF only), so the two can never drift out
     * of sync with each other over some future edit to one but not the
     * other. Falls back to a fixed reference axis on the (near-)vertical
     * edge case where forward is parallel to world-up and the cross
     * product would otherwise degenerate to zero.
     */
    private static Vector[] computeBasis(Vector forwardDir) {
        Vector forward = forwardDir.clone().normalize();
        Vector worldUp = new Vector(0, 1, 0);
        Vector right = forward.getCrossProduct(worldUp);
        if (right.lengthSquared() < 1e-6) {
            right = forward.getCrossProduct(new Vector(0, 0, 1));
            if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
        }
        right = right.normalize();
        Vector up = right.getCrossProduct(forward).normalize();
        return new Vector[]{right, up, forward};
    }

    private Location resolveInitialAnchor(SkillContext context) {
        LivingEntity caster = context.getCaster();
        return switch (anchor) {
            case SELF, SELF_FIXED -> caster.getLocation().clone();
            case TARGET -> {
                LivingEntity target = firstTarget(context);
                yield (target != null) ? target.getLocation().clone() : caster.getLocation().clone();
            }
            case CURSOR -> {
                var result = caster.getWorld().rayTraceBlocks(
                        caster.getEyeLocation(), caster.getEyeLocation().getDirection(), cursorRange);
                yield (result != null && result.getHitPosition() != null)
                        ? result.getHitPosition().toLocation(caster.getWorld())
                        : caster.getEyeLocation().clone().add(
                                caster.getEyeLocation().getDirection().normalize().multiply(cursorRange));
            }
            case CURSOR_LOCKED -> {
                Object cached = context.get(CURSOR_LOCK_KEY);
                if (cached instanceof Location cachedLoc) {
                    yield cachedLoc.clone();
                }
                var result = caster.getWorld().rayTraceBlocks(
                        caster.getEyeLocation(), caster.getEyeLocation().getDirection(), cursorRange);
                Location resolved = (result != null && result.getHitPosition() != null)
                        ? result.getHitPosition().toLocation(caster.getWorld())
                        : caster.getEyeLocation().clone().add(
                                caster.getEyeLocation().getDirection().normalize().multiply(cursorRange));
                context.put(CURSOR_LOCK_KEY, resolved.clone());
                yield resolved;
            }
        };
    }
}
