package com.example.skillsapi.shape;

/**
 * The geometric primitives a {@code shape} effect layer can be. Each one is
 * just a point cloud generator (see {@link ShapeGenerator}) - the animation
 * (spin/scale/rise/pulse) and hitbox behavior live in ShapeLayer/ShapeEffect
 * and apply identically to all of them.
 */
public enum ShapeType {
    /** A flat circle - a "magic circle" on the ground, or a portal-style ring facing the caster. */
    RING,
    /** Evenly distributed points across a full sphere surface. */
    SPHERE,
    /** Evenly distributed points across the top half of a sphere - a dome. */
    HALF_SPHERE,
    /** A straight line, pointing wherever the caster is facing. */
    LINE,
    /** A rising spiral. */
    HELIX,
    /** A hollow vertical tube - stacked rings up a height. The column/light-beam shape. Set `facing_relative: true` to orient it along the caster's aim instead - a "beam cannon" barrel instead of a standing pillar. */
    CYLINDER,
    /**
     * A rectangular prism (`width` × `height` × `length`). Default
     * (`facing_relative: false`): sits axis-aligned in the world, footprint
     * centered on the anchor, base at y=0 - like `cylinder`'s default
     * orientation. `facing_relative: true`: oriented like `line`/`cylinder`
     * in beam mode - centered cross-section (width × height), extending
     * from the anchor out to `length` along wherever the caster is facing.
     * `random_distribution: true` scatters points across the 6 faces
     * instead of the default wireframe edges - same idea as `cylinder`'s
     * two modes.
     */
    BOX,
    /** A flat fan/wedge in front of the caster - one arc at a fixed radius. */
    ARC,
    /** Several concentric arcs stacked out to a max radius - a filled fan/wedge. */
    CONE,
    /** A single point, mostly useful as a lightweight hitbox-only marker. */
    POINT,
    /**
     * A custom curve defined by math formulas instead of fixed Java code:
     * `formula_x`/`formula_y`/`formula_z`, each a function of `t` (0..1
     * across `points` samples). See {@link com.example.skillsapi.shape.MathExpression}
     * for exactly what a formula can use. Not facing-relative unless
     * `facing_relative: true` is set, since a custom curve might be meant
     * to sit fixed in the world (a rune on the ground) or rotate with the
     * caster's aim (a custom "line" replacement) - the author decides.
     */
    PARAMETRIC
}
