package com.example.skillsapi.shape;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * One visual layer inside a {@code shape} effect: a base point cloud (built
 * once, at parse time) plus how it animates over the effect's lifetime.
 * Stack several of these in a skill's `layers:` list to build up a compound
 * effect (e.g. a spinning ground ring + a rising helix + a pulsing dome).
 *
 * Per-tick transform applied to every base point, in order:
 *   1. facing-relative shapes (line/arc/cone/vertical-ring) get rotated
 *      from local (right/up/forward) space into world space using the
 *      caster's current facing; everything else is already world-oriented.
 *   2. it's rotated around all three axes: a constant base tilt
 *      ({@code rotation.x/y/z}, set once, doesn't animate) plus a
 *      continuous spin on each axis ({@code rotate_x/_/z_deg_per_sec} -
 *      the plain {@code rotate_deg_per_sec} is the Y-axis one, kept for
 *      backward compatibility). Applied X (pitch) then Y (yaw) then Z
 *      (roll). This is what lets a shape be tilted/spun on any axis, not
 *      just spun flat around vertical.
 *   3. it's scaled (on the configured axis) by a start->end lerp over the
 *      effect's duration, multiplied by an optional sine pulsation.
 *   4. it's lifted by {@code risePerSec} * elapsed seconds.
 *   5. its own center is nudged by a {@code start_offset -> end_offset}
 *      lerp over the same duration - lets a layer's position (not just its
 *      size) animate, e.g. sliding in from off to the side or dropping down
 *      from high overhead. When this layer's points are facing-relative
 *      (the normal case), the offset itself is too - it moves along the
 *      caster's right/up/forward, not raw world XYZ, so e.g. "shift this
 *      layer to the right" stays correct as the caster turns instead of
 *      only being correct for one fixed world direction. Setting
 *      {@code start_offset} and {@code end_offset} to the same value gives
 *      a static per-layer offset instead of an animated one - useful for
 *      staggering multiple layers of one shape effect side by side (e.g. a
 *      slash built from several arcs slightly offset from each other).
 *   6. it's placed relative to the effect's current center location.
 */
public class ShapeLayer {

    /**
     * Which axes {@code scale_start -> scale_end} actually affects.
     * UNIFORM (default, old behavior): scales x/y/z together - fine for
     * things like a ring or dome growing in place, but wrong for a
     * cylinder/column where you usually want the *height* to shoot up
     * without the radius ballooning out too (or vice versa: a widening
     * shockwave ring that shouldn't stretch vertically).
     * RADIAL: scales x/z only, y untouched - "erupt outward, fixed height".
     * VERTICAL: scales y only, x/z untouched - "shoot upward, fixed radius".
     */
    public enum ScaleAxis { UNIFORM, RADIAL, VERTICAL }

    private final ShapeType type;
    private final List<Vector> basePoints;
    private final boolean facingRelative;

    private final Particle particle;
    private final int particleCount;
    private final Particle.DustOptions dustOptions; // non-null only when particle == Particle.DUST and a color was configured

    // Constant base tilt, in degrees, applied once (doesn't animate) - lets
    // a shape be oriented at any fixed angle instead of only ever lying in
    // its default plane. Applied X (pitch) then Y (yaw) then Z (roll).
    private final double baseRotationXDeg;
    private final double baseRotationYDeg;
    private final double baseRotationZDeg;

    // Continuous spin, in degrees/second, one rate per axis. rotateYDegPerSec
    // is the original `rotate_deg_per_sec` field (kept under that same YAML
    // key for backward compatibility) - X/Z are new, so a shape can now spin
    // tumbling on any axis, not just flat around vertical.
    private final double rotateXDegPerSec;
    private final double rotateYDegPerSec;
    private final double rotateZDegPerSec;

    private final double scaleStart;
    private final double scaleEnd;
    private final ScaleAxis scaleAxis;
    private final double pulsateAmplitude;
    private final double pulsateFrequency;
    private final double risePerSec;

    // Animated world-space translation of this layer's own center, lerped
    // start->end over the same t as scale_start/scale_end. Independent of
    // rotate_deg_per_sec and scale - this moves *where the shape sits*, not
    // how its points are shaped. Used e.g. to place 3 rings at 3 separate
    // spots that slide together into a shared convergence point, or to drop
    // a full-height pillar in from high overhead down to ground level.
    private final double offsetStartX;
    private final double offsetStartY;
    private final double offsetStartZ;
    private final double offsetEndX;
    private final double offsetEndY;
    private final double offsetEndZ;

    public ShapeLayer(ShapeType type, ShapeGenerator.Params params, Particle particle, int particleCount,
                       Color dustColor, float dustSize,
                       double baseRotationXDeg, double baseRotationYDeg, double baseRotationZDeg,
                       double rotateXDegPerSec, double rotateYDegPerSec, double rotateZDegPerSec,
                       double scaleStart, double scaleEnd, ScaleAxis scaleAxis,
                       double pulsateAmplitude, double pulsateFrequency, double risePerSec,
                       double offsetStartX, double offsetStartY, double offsetStartZ,
                       double offsetEndX, double offsetEndY, double offsetEndZ) {
        this.type = type;
        this.basePoints = ShapeGenerator.generate(type, params);
        this.facingRelative = ShapeGenerator.isFacingRelative(type, params);
        this.particle = particle;
        this.particleCount = particleCount;
        this.dustOptions = (particle == Particle.DUST && dustColor != null)
                ? new Particle.DustOptions(dustColor, dustSize)
                : null;
        this.baseRotationXDeg = baseRotationXDeg;
        this.baseRotationYDeg = baseRotationYDeg;
        this.baseRotationZDeg = baseRotationZDeg;
        this.rotateXDegPerSec = rotateXDegPerSec;
        this.rotateYDegPerSec = rotateYDegPerSec;
        this.rotateZDegPerSec = rotateZDegPerSec;
        this.scaleStart = scaleStart;
        this.scaleEnd = scaleEnd;
        this.scaleAxis = scaleAxis;
        this.pulsateAmplitude = pulsateAmplitude;
        this.pulsateFrequency = pulsateFrequency;
        this.risePerSec = risePerSec;
        this.offsetStartX = offsetStartX;
        this.offsetStartY = offsetStartY;
        this.offsetStartZ = offsetStartZ;
        this.offsetEndX = offsetEndX;
        this.offsetEndY = offsetEndY;
        this.offsetEndZ = offsetEndZ;
    }

    public ShapeType getType() { return type; }

    /**
     * Computes this tick's absolute world points. `right`/`up`/`forward`
     * are the caster's current facing basis (forward is horizontal-only,
     * unit length); only used at all if this layer is facing-relative.
     */
    public List<Location> render(Location center, Vector right, Vector up, Vector forward,
                                  double elapsedSeconds, double durationSeconds) {
        double rotXRad = Math.toRadians(baseRotationXDeg + rotateXDegPerSec * elapsedSeconds);
        double rotYRad = Math.toRadians(baseRotationYDeg + rotateYDegPerSec * elapsedSeconds);
        double rotZRad = Math.toRadians(baseRotationZDeg + rotateZDegPerSec * elapsedSeconds);

        double t = durationSeconds > 0 ? Math.min(1.0, elapsedSeconds / durationSeconds) : 0.0;
        double lerpScale = scaleStart + (scaleEnd - scaleStart) * t;
        double pulsate = 1.0 + pulsateAmplitude * Math.sin(2 * Math.PI * pulsateFrequency * elapsedSeconds);
        double scale = lerpScale * pulsate;
        double rise = risePerSec * elapsedSeconds;

        double offX = offsetStartX + (offsetEndX - offsetStartX) * t;
        double offY = offsetStartY + (offsetEndY - offsetStartY) * t;
        double offZ = offsetStartZ + (offsetEndZ - offsetStartZ) * t;

        List<Location> out = new ArrayList<>(basePoints.size());
        World world = center.getWorld();
        for (Vector local : basePoints) {
            Vector world_ = facingRelative
                    ? right.clone().multiply(local.getX())
                            .add(up.clone().multiply(local.getY()))
                            .add(forward.clone().multiply(local.getZ()))
                    : local.clone();

            rotate3D(world_, rotXRad, rotYRad, rotZRad);

            switch (scaleAxis) {
                case RADIAL -> {
                    world_.setX(world_.getX() * scale);
                    world_.setZ(world_.getZ() * scale);
                }
                case VERTICAL -> world_.setY(world_.getY() * scale);
                default -> world_.multiply(scale); // UNIFORM
            }
            world_.setY(world_.getY() + rise);

            // Facing-relative when the layer's own points are (the normal
            // case): offX/Y/Z move along the caster's right/up/forward, the
            // same basis the shape itself is drawn in, so "shift this layer
            // slightly to the right" stays correct as the caster turns,
            // instead of only being correct for whichever way they happened
            // to be facing when the numbers were written. Falls back to raw
            // world-space when facingRelative is false, matching how this
            // layer's base points already behave in that mode.
            Vector offset = facingRelative
                    ? right.clone().multiply(offX).add(up.clone().multiply(offY)).add(forward.clone().multiply(offZ))
                    : new Vector(offX, offY, offZ);

            out.add(new Location(world, center.getX() + world_.getX() + offset.getX(),
                    center.getY() + world_.getY() + offset.getY(), center.getZ() + world_.getZ() + offset.getZ()));
        }
        return out;
    }

    /**
     * Rotates {@code v} in place around all three axes, applied in order
     * X (pitch) -> Y (yaw) -> Z (roll). The Y-axis formula matches the
     * plugin's original (Y-only) spin exactly, so a layer using only
     * {@code rotate_deg_per_sec} (Y) with X/Z left at 0 renders identically
     * to before this method existed - X and Z are purely additive.
     */
    private static void rotate3D(Vector v, double radX, double radY, double radZ) {
        double x = v.getX(), y = v.getY(), z = v.getZ();

        if (radX != 0) {
            double cos = Math.cos(radX), sin = Math.sin(radX);
            double y2 = y * cos - z * sin;
            double z2 = y * sin + z * cos;
            y = y2;
            z = z2;
        }
        if (radY != 0) {
            double cos = Math.cos(radY), sin = Math.sin(radY);
            double x2 = x * cos - z * sin;
            double z2 = x * sin + z * cos;
            x = x2;
            z = z2;
        }
        if (radZ != 0) {
            double cos = Math.cos(radZ), sin = Math.sin(radZ);
            double x2 = x * cos - y * sin;
            double y2 = x * sin + y * cos;
            x = x2;
            y = y2;
        }

        v.setX(x);
        v.setY(y);
        v.setZ(z);
    }

    /**
     * @param recipients null (default) broadcasts normally - every nearby
     *                   player sees it, via World#spawnParticle. Non-null
     *                   sends the particle packets ONLY to those players
     *                   (Player#spawnParticle), invisible to everyone else -
     *                   this is what makes a trap's telltale ring visible
     *                   only to whoever placed it. See ShapeEffect's
     *                   `visible_to` option, which decides what gets passed
     *                   in here.
     */
    public void spawnParticles(List<Location> points, List<Player> recipients) {
        for (Location loc : points) {
            if (recipients != null) {
                for (Player recipient : recipients) {
                    if (dustOptions != null) {
                        recipient.spawnParticle(particle, loc, particleCount, 0, 0, 0, 0, dustOptions);
                    } else {
                        recipient.spawnParticle(particle, loc, particleCount, 0, 0, 0, 0);
                    }
                }
            } else if (dustOptions != null) {
                loc.getWorld().spawnParticle(particle, loc, particleCount, 0, 0, 0, 0, dustOptions);
            } else {
                loc.getWorld().spawnParticle(particle, loc, particleCount, 0, 0, 0, 0);
            }
        }
    }
}
