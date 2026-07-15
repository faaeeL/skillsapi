package com.example.skillsapi.status;

import org.bukkit.Input;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Map;

/**
 * The movement behind a dash status: shoves the entity along a fixed
 * direction, sustained every tick via repeated setVelocity - so Minecraft's
 * own collision stops it naturally at a wall instead of a teleport clipping
 * through one, and player-controlled cameras don't get the snap a per-tick
 * teleport causes.
 *
 * Direction priority, in order:
 *   1. For a real Player, their current WASD input (Player#getCurrentInput())
 *      translated into a world-space direction relative to where they're
 *      facing - forward/back/left/right/diagonals all work. This is the one
 *      that actually matters: Bukkit's entity.getVelocity() does NOT reflect
 *      ordinary client-driven walking for players (it only reflects
 *      server-applied velocity like knockback or explosions), so relying on
 *      it alone means strafing/backpedalling never registers and the dash
 *      always ends up firing in the look direction instead.
 *   2. entity.getVelocity() - covers non-player LivingEntity dashers (mobs),
 *      and players who aren't pressing any movement key but do have real
 *      server-side velocity (e.g. knockback, elytra).
 *   3. Look direction - last resort when the entity is roughly stationary
 *      and gave us nothing else to go on.
 */
public class DashStatusBehavior implements StatusBehavior {

    private static final String VELOCITY_KEY = "velocity_per_tick";
    private static final String HAD_GRAVITY_KEY = "had_gravity";
    private static final double STATIONARY_THRESHOLD_SQUARED = 0.0025; // ~0.05 blocks/tick

    private final double distance;
    private final int durationTicks;
    private final boolean horizontalOnly;
    private final boolean disableGravityDuringDash;

    public DashStatusBehavior(double distance, int durationTicks, boolean horizontalOnly,
                               boolean disableGravityDuringDash) {
        this.distance = distance;
        this.durationTicks = Math.max(1, durationTicks);
        this.horizontalOnly = horizontalOnly;
        this.disableGravityDuringDash = disableGravityDuringDash;
    }

    @Override
    public void onStart(LivingEntity entity, Map<String, Object> state) {
        Vector direction = fromPlayerInput(entity);

        if (direction == null) {
            direction = entity.getVelocity().clone();
            if (horizontalOnly) direction.setY(0);

            if (direction.lengthSquared() < STATIONARY_THRESHOLD_SQUARED) {
                direction = entity.getLocation().getDirection().clone();
                if (horizontalOnly) direction.setY(0);
            }
        }

        if (direction.lengthSquared() < 1e-6) {
            state.put(VELOCITY_KEY, new Vector(0, 0, 0));
            return;
        }

        direction.normalize();
        state.put(VELOCITY_KEY, direction.multiply(distance / durationTicks));
        state.put(HAD_GRAVITY_KEY, entity.hasGravity());
        if (disableGravityDuringDash) entity.setGravity(false);
    }

    /**
     * Turns a player's currently-held WASD keys into a world-space direction
     * relative to their facing - e.g. holding D while looking north gives
     * "east", holding W+A gives the diagonal between "north" and "west".
     * Returns null if this isn't a player, or if they aren't holding any
     * movement key right now (so the caller can fall through to velocity/
     * look-direction instead).
     */
    private Vector fromPlayerInput(LivingEntity entity) {
        if (!(entity instanceof Player player)) return null;

        Input input = player.getCurrentInput();
        double forwardAmount = (input.isForward() ? 1 : 0) - (input.isBackward() ? 1 : 0);
        double rightAmount = (input.isRight() ? 1 : 0) - (input.isLeft() ? 1 : 0);
        if (forwardAmount == 0 && rightAmount == 0) return null;

        Vector forward = entity.getLocation().getDirection().clone();
        forward.setY(0);
        if (forward.lengthSquared() < 1e-6) return null; // looking straight up/down - no horizontal facing to work from
        forward.normalize();

        // rotate forward -90 degrees around the Y axis to get "right",
        // matching Minecraft's compass (e.g. facing north, right = east)
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());

        Vector combined = forward.multiply(forwardAmount).add(right.multiply(rightAmount));
        if (combined.lengthSquared() < 1e-6) return null;
        return combined; // caller normalizes
    }

    @Override
    public void onTick(LivingEntity entity, int elapsedTicks, Map<String, Object> state) {
        // Re-set every tick rather than once: a single setVelocity only
        // nudges that one tick, and gravity/friction eat into it right
        // after. Reapplying it each tick is what actually sustains the
        // dash's speed for its full duration.
        if (state.get(VELOCITY_KEY) instanceof Vector velocityPerTick) {
            entity.setVelocity(velocityPerTick);
        }
    }

    @Override
    public void onExpire(LivingEntity entity, Map<String, Object> state) {
        if (disableGravityDuringDash && entity.isValid() && state.get(HAD_GRAVITY_KEY) instanceof Boolean hadGravity) {
            entity.setGravity(hadGravity);
        }
    }
}
