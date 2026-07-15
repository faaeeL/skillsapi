package com.example.skillsapi.skill;

/**
 * Immediate outcome of trying to cast a skill. For a skill with no cast time
 * this is also the final result. For a channeled skill, CHANNEL_STARTED just
 * means the windup began - whether it actually lands happens later, once the
 * cast time elapses (or gets interrupted), and CastEngine messages that part
 * separately since there's no caller left waiting for a return value by then.
 */
public enum CastAttemptResult {
    RESOLVED_INSTANTLY,
    CHANNEL_STARTED,
    ALREADY_CASTING,
    ON_COOLDOWN,
    CONDITION_FAILED,
    NO_TARGET
}
