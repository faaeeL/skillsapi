package com.example.skillsapi.mob;

/** When a mob template's bound skill fires. See MobInstanceManager for dispatch. */
public enum Trigger {
    ON_SPAWN,
    ON_DEATH,
    ON_DAMAGED,
    ON_ATTACK,
    ON_TIMER,
    ON_LOW_HEALTH
}
