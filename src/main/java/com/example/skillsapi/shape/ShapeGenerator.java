package com.example.skillsapi.shape;

import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link ShapeType} + its parameters into a list of *local-space*
 * points (no animation, no world position - that's ShapeLayer/ShapeEffect's
 * job). Local space convention, matched by ShapeLayer's transform:
 *   x = "right" axis, y = "up" axis, z = "forward" axis
 * Shapes that don't care about facing (ring/sphere/point) mostly only use
 * x/z as plain world-ish offsets and ignore the facing basis entirely;
 * shapes that should point wherever the caster is looking (line/arc/cone, a
 * ring in "vertical" mode, and now cylinder/helix/box when their own
 * `facing_relative: true` is set) are built along local +z ("forward") on
 * purpose so ShapeLayer's facing-relative transform lines them up correctly.
 * cylinder/helix/box are the shapes with a genuine "long axis" that's
 * meaningful either way - a cylinder can be a standing pillar (long axis
 * along world-up) or a beam barrel (long axis along the caster's aim), so
 * unlike line/arc/cone (always facing-relative) they take the same
 * `facing_relative` flag PARAMETRIC already used, generalized to apply here
 * too - see {@link #isFacingRelative}.
 */
public final class ShapeGenerator {

    private ShapeGenerator() {}

    public record Params(
            int points,
            double radius,
            double length,
            double width,
            double height,
            double turns,
            double arcDegrees,
            int rings,
            boolean verticalPlane,
            double yOffset,
            boolean randomDistribution,
            double radiusJitter,
            String formulaX,
            String formulaY,
            String formulaZ,
            boolean facingRelative
    ) {}

    public static List<Vector> generate(ShapeType type, Params p) {
        List<Vector> points = switch (type) {
            case RING -> ring(p.points(), p.radius(), p.verticalPlane());
            case SPHERE -> fibonacciSphere(p.points(), p.radius(), false);
            case HALF_SPHERE -> fibonacciSphere(p.points(), p.radius(), true);
            case LINE -> line(p.points(), p.length());
            case HELIX -> helix(p.points(), p.radius(), p.height(), p.turns(), p.facingRelative());
            case CYLINDER -> cylinder(p.points(), p.radius(), p.height(), Math.max(2, p.rings()),
                    p.randomDistribution(), p.radiusJitter(), p.facingRelative());
            case BOX -> box(p.points(), p.width(), p.height(), p.length(),
                    p.randomDistribution(), p.facingRelative());
            case ARC -> arc(p.points(), p.radius(), p.arcDegrees());
            case CONE -> cone(p.points(), p.radius(), p.arcDegrees(), Math.max(1, p.rings()));
            case POINT -> new ArrayList<>(List.of(new Vector(0, 0, 0)));
            case PARAMETRIC -> parametric(p.points(), p.formulaX(), p.formulaY(), p.formulaZ());
        };
        if (p.yOffset() != 0) {
            for (Vector v : points) v.setY(v.getY() + p.yOffset());
        }
        return points;
    }

    /**
     * verticalPlane=false: a flat ring on the ground (x/z), independent of
     * facing - a magic circle that doesn't spin just because you turn your
     * head. verticalPlane=true: built along the right/up plane (a portal
     * ring facing whichever way the caster looks) - facing-relative.
     */
    public static List<Vector> ring(int points, double radius, boolean verticalPlane) {
        List<Vector> list = new ArrayList<>();
        int n = Math.max(3, points);
        for (int i = 0; i < n; i++) {
            double angle = 2 * Math.PI * i / n;
            double a = Math.cos(angle) * radius;
            double b = Math.sin(angle) * radius;
            list.add(verticalPlane ? new Vector(a, b, 0) : new Vector(a, 0, b));
        }
        return list;
    }

    /**
     * A cheap wireframe ball (two great circles - one horizontal, one
     * vertical) - not meant to be a real shape layer, just something to
     * draw around a point so you can *see* how big its hit_radius actually
     * is. Used by ShapeEffect's `debug_hitbox` option.
     */
    public static List<Vector> wireframeSphere(int pointsPerRing, double radius) {
        List<Vector> points = new ArrayList<>(ring(pointsPerRing, radius, false));
        points.addAll(ring(pointsPerRing, radius, true));
        return points;
    }

    /** Along local +z ("forward") from 0 to length - facing-relative. */
    private static List<Vector> line(int points, double length) {
        List<Vector> list = new ArrayList<>();
        int n = Math.max(2, points);
        for (int i = 0; i < n; i++) {
            double z = length * i / (n - 1);
            list.add(new Vector(0, 0, z));
        }
        return list;
    }

    /**
     * Spirals upward around its long axis - by default that's world Y (a
     * standing corkscrew, symmetric either way you're facing); with
     * `facing_relative: true` the long axis becomes local +z instead, so it
     * corkscrews outward along the caster's aim - a drilling beam.
     */
    private static List<Vector> helix(int points, double radius, double height, double turns, boolean facingRelative) {
        List<Vector> list = new ArrayList<>();
        int n = Math.max(2, points);
        double totalAngle = 2 * Math.PI * turns;
        for (int i = 0; i < n; i++) {
            double t = (double) i / (n - 1);
            double angle = totalAngle * t;
            double axisPos = height * t;
            double a = Math.cos(angle) * radius;
            double b = Math.sin(angle) * radius;
            list.add(facingRelative ? new Vector(a, b, axisPos) : new Vector(a, axisPos, b));
        }
        return list;
    }

    /**
     * The "column of light" shape - either a hollow tube of clean stacked
     * rings (original behavior, {@code randomDistribution=false}), or a
     * scattered point cloud spread randomly across the cylinder's surface
     * ({@code randomDistribution=true}). The clean-ring version is precise
     * and reads well for thin/fast beams (see light_beam_strike), but at
     * larger sizes every ring lines up at the same angles, so the particles
     * form obvious repeating vertical bands/lattice instead of looking like
     * an organic column of energy. The scattered version breaks that up:
     * every point gets its own random angle and height instead of falling
     * on a fixed grid of rings, and {@code radiusJitter} (0..1, fraction of
     * radius) lets the surface itself wobble in/out per point instead of
     * sitting on a perfectly smooth tube - both together read as chaotic
     * motes of light rather than a stack of hula-hoops.
     *
     * By default the tube's long axis is world Y (a standing pillar - see
     * light_beam_strike). With {@code facingRelative=true} the long axis
     * becomes local +z instead - a barrel pointing along the caster's aim,
     * for a thick "beam cannon" instead of a thin `line`.
     */
    private static List<Vector> cylinder(int points, double radius, double height, int levels,
                                          boolean randomDistribution, double radiusJitter, boolean facingRelative) {
        if (randomDistribution) {
            List<Vector> list = new ArrayList<>();
            java.util.Random rng = new java.util.Random();
            int n = Math.max(3, points);
            for (int i = 0; i < n; i++) {
                double angle = rng.nextDouble() * 2 * Math.PI;
                double axisPos = rng.nextDouble() * height;
                double jitter = radiusJitter > 0 ? 1.0 + (rng.nextDouble() * 2 - 1) * radiusJitter : 1.0;
                double r = radius * jitter;
                list.add(cylinderPoint(angle, r, axisPos, facingRelative));
            }
            return list;
        }

        List<Vector> list = new ArrayList<>();
        int perRing = Math.max(3, points / levels);
        for (int lvl = 0; lvl < levels; lvl++) {
            double axisPos = height * lvl / (levels - 1);
            int n = Math.max(3, perRing);
            for (int i = 0; i < n; i++) {
                double angle = 2 * Math.PI * i / n;
                list.add(cylinderPoint(angle, radius, axisPos, facingRelative));
            }
        }
        return list;
    }

    /** One point on a cylinder's circular cross-section, placed on whichever axis is currently "long" (Y normally, Z when facing-relative). */
    private static Vector cylinderPoint(double angle, double r, double axisPos, boolean facingRelative) {
        double a = Math.cos(angle) * r;
        double b = Math.sin(angle) * r;
        return facingRelative ? new Vector(a, b, axisPos) : new Vector(a, axisPos, b);
    }

    /**
     * A rectangular prism. `facingRelative=false` (default): axis-aligned
     * in the world, footprint (width × length) centered on the anchor, base
     * at y=0, rising to `height` - matches cylinder's default grounded
     * orientation. `facingRelative=true`: beam mode, same convention as
     * `line`/facing-relative `cylinder` - centered cross-section
     * (width × height), extending from 0 to `length` along local +z.
     * `randomDistribution=true` scatters points across the 6 faces instead
     * of the default wireframe (12 edges) - same idea as cylinder's two modes.
     */
    private static List<Vector> box(int points, double width, double height, double length,
                                     boolean randomDistribution, boolean facingRelative) {
        double xMin = -width / 2, xMax = width / 2;
        double yMin, yMax, zMin, zMax;
        if (facingRelative) {
            yMin = -height / 2; yMax = height / 2;
            zMin = 0; zMax = length;
        } else {
            yMin = 0; yMax = height;
            zMin = -length / 2; zMax = length / 2;
        }
        return randomDistribution
                ? boxSurfaceRandom(points, xMin, xMax, yMin, yMax, zMin, zMax)
                : boxWireframe(points, xMin, xMax, yMin, yMax, zMin, zMax);
    }

    /**
     * The 12 edges of a box, evenly sampled - a crisp, readable cube/prism
     * outline. {@code points} is split evenly across all 12 edges
     * ({@code perEdge = points / 12}, minimum 2) - at exactly 2 per edge,
     * every edge is just its own two corner endpoints with nothing sampled
     * in between, so the whole box renders as 8 floating dots with no
     * visible lines connecting them. Budget at least ~4-8 points per edge
     * (48-96+ total) for edges that actually read as edges - see the
     * `points` default in SkillConfigParser#parseShapeLayer, which already
     * accounts for this for `box` specifically.
     */
    private static List<Vector> boxWireframe(int points, double xMin, double xMax, double yMin, double yMax,
                                              double zMin, double zMax) {
        double[] xs = {xMin, xMax};
        double[] ys = {yMin, yMax};
        double[] zs = {zMin, zMax};
        Vector[] corners = new Vector[8];
        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 2; yi++) {
                for (int zi = 0; zi < 2; zi++) {
                    corners[xi * 4 + yi * 2 + zi] = new Vector(xs[xi], ys[yi], zs[zi]);
                }
            }
        }
        int[][] edges = {
                {0, 1}, {2, 3}, {4, 5}, {6, 7}, // vary z
                {0, 2}, {1, 3}, {4, 6}, {5, 7}, // vary y
                {0, 4}, {1, 5}, {2, 6}, {3, 7}  // vary x
        };

        List<Vector> list = new ArrayList<>();
        int perEdge = Math.max(2, points / edges.length);
        for (int[] edge : edges) {
            Vector a = corners[edge[0]];
            Vector b = corners[edge[1]];
            for (int i = 0; i < perEdge; i++) {
                double t = perEdge == 1 ? 0 : (double) i / (perEdge - 1);
                list.add(new Vector(
                        a.getX() + (b.getX() - a.getX()) * t,
                        a.getY() + (b.getY() - a.getY()) * t,
                        a.getZ() + (b.getZ() - a.getZ()) * t
                ));
            }
        }
        return list;
    }

    /** Random points scattered across the box's 6 faces - a glowing/energy cube instead of a crisp wireframe. */
    private static List<Vector> boxSurfaceRandom(int points, double xMin, double xMax, double yMin, double yMax,
                                                  double zMin, double zMax) {
        List<Vector> list = new ArrayList<>();
        java.util.Random rng = new java.util.Random();
        int n = Math.max(6, points);
        for (int i = 0; i < n; i++) {
            double x, y, z;
            switch (rng.nextInt(6)) {
                case 0 -> { x = xMin; y = randomBetween(rng, yMin, yMax); z = randomBetween(rng, zMin, zMax); }
                case 1 -> { x = xMax; y = randomBetween(rng, yMin, yMax); z = randomBetween(rng, zMin, zMax); }
                case 2 -> { y = yMin; x = randomBetween(rng, xMin, xMax); z = randomBetween(rng, zMin, zMax); }
                case 3 -> { y = yMax; x = randomBetween(rng, xMin, xMax); z = randomBetween(rng, zMin, zMax); }
                case 4 -> { z = zMin; x = randomBetween(rng, xMin, xMax); y = randomBetween(rng, yMin, yMax); }
                default -> { z = zMax; x = randomBetween(rng, xMin, xMax); y = randomBetween(rng, yMin, yMax); }
            }
            list.add(new Vector(x, y, z));
        }
        return list;
    }

    private static double randomBetween(java.util.Random rng, double min, double max) {
        return min + rng.nextDouble() * (max - min);
    }


    /**
     * A custom curve: evaluates formulaX/Y/Z at `points` evenly-spaced
     * samples of t from 0 to 1 (plus i, the raw sample index, and n, the
     * total sample count, also available to the formulas - see
     * MathExpression). Each formula is parsed and evaluated once per point,
     * here at layer-construction time (config load / reload), same as every
     * other shape's point cloud - not re-evaluated per render tick, so a
     * complex formula costs nothing extra during actual gameplay.
     */
    private static List<Vector> parametric(int points, String formulaX, String formulaY, String formulaZ) {
        if (formulaX == null || formulaY == null || formulaZ == null) {
            throw new IllegalArgumentException(
                    "shape: parametric requires formula_x, formula_y, and formula_z to all be set");
        }
        int n = Math.max(2, points);
        List<Vector> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double t = (double) i / (n - 1);
            Map<String, Double> vars = Map.of("t", t, "i", (double) i, "n", (double) n);
            double x = evaluateFormula(formulaX, vars, "formula_x", i);
            double y = evaluateFormula(formulaY, vars, "formula_y", i);
            double z = evaluateFormula(formulaZ, vars, "formula_z", i);
            list.add(new Vector(x, y, z));
        }
        return list;
    }

    private static double evaluateFormula(String formula, Map<String, Double> vars, String fieldName, int sampleIndex) {
        try {
            return MathExpression.evaluate(formula, vars);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Error evaluating " + fieldName + " at sample i=" + sampleIndex + ": " + e.getMessage(), e);
        }
    }

    /** A wedge of `arcDegrees` centered on local +z ("forward") - facing-relative. */
    private static List<Vector> arc(int points, double radius, double arcDegrees) {
        List<Vector> list = new ArrayList<>();
        int n = Math.max(2, points);
        double halfRad = Math.toRadians(arcDegrees) / 2.0;
        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0 : (double) i / (n - 1);
            double angle = -halfRad + t * 2 * halfRad;
            list.add(new Vector(Math.sin(angle) * radius, 0, Math.cos(angle) * radius));
        }
        return list;
    }

    /** Several concentric arcs out to maxRadius - a filled wedge - facing-relative. */
    private static List<Vector> cone(int points, double maxRadius, double arcDegrees, int rings) {
        List<Vector> list = new ArrayList<>();
        int perRing = Math.max(2, points / rings);
        for (int r = 1; r <= rings; r++) {
            double radius = maxRadius * r / rings;
            list.addAll(arc(perRing, radius, arcDegrees));
        }
        return list;
    }

    /** Evenly spaced points over a sphere (or its top half) via the golden-angle method. */
    private static List<Vector> fibonacciSphere(int points, double radius, boolean halfOnly) {
        List<Vector> list = new ArrayList<>();
        int n = Math.max(2, points);
        double goldenAngle = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double yUnit = halfOnly
                    ? (double) i / (n - 1)          // 0..1 -> top half only
                    : 1 - 2.0 * i / (n - 1);         // 1..-1 -> full sphere
            double radiusAtY = Math.sqrt(Math.max(0, 1 - yUnit * yUnit));
            double theta = goldenAngle * i;
            double x = Math.cos(theta) * radiusAtY;
            double z = Math.sin(theta) * radiusAtY;
            list.add(new Vector(x * radius, yUnit * radius, z * radius));
        }
        return list;
    }

    /** Whether this shape (given its full params) should be rotated to face the caster's look direction. */
    public static boolean isFacingRelative(ShapeType type, Params p) {
        return switch (type) {
            case LINE, ARC, CONE -> true;
            case RING -> p.verticalPlane();
            case CYLINDER, HELIX, BOX, PARAMETRIC -> p.facingRelative();
            default -> false;
        };
    }
}
