# SkillsAPI Usage Reference

Reference for `skills/*.yml` and `resources.yml`. Generated from `SkillConfigParser.java`.

> **Nested blocks vs. flat keys:** `shape`/`projectile`'s `hit:` and `summon`'s `cap:`/`ai:` blocks shown throughout this doc are the recommended style. Older configs using flat keys (`hit_radius`, `hit_area`, `on_hit`, `max_active`, `aggro_radius`, etc. directly on the effect) still work unchanged — the parser checks the nested block first and falls back to the matching flat key for anything it doesn't set. Don't mix both for the same field in one effect; if both are present, the nested value wins.

## Contents

1. [Quick start](#quick-start)
2. [Cookbook](#cookbook)
3. [Files](#files) · [Editor setup](#editor-setup)
4. [Skill (top level)](#skill-top-level)
5. [Targeters](#targeters)
6. [Cost & resources](#cost--resources)
7. [Cast time, interrupts, telegraph](#cast-time-interrupts-telegraph)
8. [Effects](#effects)
   - [damage](#damage) · [heal](#heal) · [particle](#particle) · [potion](#potion) · [glow](#glow) · [knockback](#knockback)
   - [status](#status-effect)
   - [projectile](#projectile)
   - [summon](#summon) · [dismiss_summons](#summon)
   - [taunt](#taunt)
   - [shape](#shape)
     - [Custom curves (shape: parametric)](#custom-curves-shape-parametric)
   - [sequence](#sequence)
9. [Statuses](#statuses)
10. [Mob templates](#mob-templates)
11. [Mob skills & triggers](#mob-skills--triggers)
12. [Commands & item binding](#commands--item-binding)
13. [YAML anchors](#yaml-anchors)
14. [Notes](#notes)
15. [Full worked example](#full-worked-example)

---

## Quick start

```yaml
skills:
  spark:
    cooldown: 3000
    targeter:
      type: single
      range: 15
    effects:
      - type: damage
        amount: 8
```

Required: `cooldown`, `targeter`, `effects`. Everything else is optional.

---

## Cookbook

**Single-target nuke:**
```yaml
fireball:
  cooldown: 4000
  targeter:
    type: single
    range: 18
  cost: { type: mana, amount: 10 }
  effects:
    - type: damage
      amount: 12
    - type: particle
      particle: FLAME
      count: 20
```

**AoE around the caster:**
```yaml
shockwave:
  cooldown: 8000
  targeter:
    type: radius
    radius: 6
    include_self: false
  cost: { type: mana, amount: 20 }
  effects:
    - type: damage
      amount: 6
    - type: knockback
      strength: 1.5
```

**Self-heal:**
```yaml
mend:
  cooldown: 10000
  targeter:
    type: self
  cost: { type: mana, amount: 15 }
  effects:
    - type: heal
      amount: 15
    - type: particle
      particle: HEART
      count: 10
```

**Temporary buff (vanilla potion effect):**
```yaml
haste_potion:
  cooldown: 15000
  targeter:
    type: self
  cost: { type: stamina, amount: 10 }
  effects:
    - type: potion
      effect: SPEED
      duration_ticks: 100
      amplifier: 1
```

**Damage-over-time (via a registered status):**
```yaml
poison_bolt:
  cooldown: 6000
  targeter:
    type: single
    range: 15
  cost: { type: mana, amount: 12 }
  effects:
    - type: damage
      amount: 3
    - type: status
      status: envenomed
```

**Projectile:**
```yaml
bolt:
  cooldown: 5000
  targeter:
    type: self
  cost: { type: mana, amount: 10 }
  effects:
    - type: projectile
      particle: FLAME
      speed: 20
      max_distance: 20
      hit:
        radius: 1.0
        effects:
          - type: damage
            amount: 10
```

---

## Files

| File | Purpose |
|---|---|
| `skills/*.yml` | Any name, nested folders OK. All `statuses:`/`skills:` across every file merge into one registry at load. All statuses register before any skills parse. |
| `skills.yml` (plugin root, legacy) | Loaded last, if present. |
| `mobs/*.yml` → `mobs:` | Mob templates, own directory - see [Mob templates](#mob-templates). Merged the same way as `skills/*.yml`, but templates and skills are separate registries. |
| `resources.yml` → `resources:` | Named resource pools (mana, stamina, ...). Single file. |
| `skills-schema.json` | JSON Schema for editor autocomplete/hover docs. Not read at runtime. |

Duplicate id across files: later-loaded wins; `/skillsapi reload` logs a warning naming both files.

### Editor setup

Add to top of each file under `skills/`:
```yaml
# yaml-language-server: $schema=../skills-schema.json
```
VSCode: install `redhat.vscode-yaml`. Gives autocomplete, hover docs, error squiggles, breadcrumbs/sticky-scroll for nested paths, and folding. Any editor with a YAML language server works the same way.

---

## Skill (top level)

```yaml
skills:
  my_skill:
    cooldown: 5000              # ms, default 0
    targeter:
      type: self                # self | single | radius | cone - default self
      range: 20                 # single/cone only
      radius: 5                 # radius only
      angle: 90                 # cone only
      include_self: false       # radius only
    cast_time: 0                 # ms, default 0
    interruptible: true          # default true
    min_health_percent: 0.0      # optional
    cost:
      type: mana                 # must match a resources.yml key
      amount: 15
    telegraph: {...}             # optional
    effects: [...]               # required
```

| Field | Notes |
|---|---|
| `cooldown` | ms, per-caster, in-memory |
| `min_health_percent` | Cast fails silently if caster's health fraction is below this |
| `cost` | Adds a cast condition + a spend effect (inserted first in `effects`) |

---

## Targeters

Set via `targeter:`. Determines `context.getTargets()` for the skill's top-level `effects:`. Nested lists (`effects` under `hit`, `on_start`/`on_tick`/`on_expire`, sequence steps) get their own targets.

| `targeter.type` | Extra fields | Targets |
|---|---|---|
| `self` (default) | - | Caster only |
| `single` | `range` (20) | Entity in crosshair, up to `range` blocks. Empty = fizzle |
| `radius` | `radius` (5), `include_self` (false) | All living entities within `radius` |
| `cone` | `range` (5), `angle` (90) | All living entities within `range` and `angle`° cone in front of caster |

---

## Cost & resources

`resources.yml`:
```yaml
resources:
  mana:
    max: 100
    regen_per_second: 1.5
  stamina:
    max: 100
    regen_per_second: 4.0
```

Any name allowed. `cost.type` must match a key here. Regen runs once/sec, in-memory (no persistence).

---

## Cast time, interrupts, telegraph

```yaml
skills:
  my_skill:
    cast_time: 800               # ms windup
    interruptible: true          # only matters if cast_time > 0
    telegraph:
      particle: END_ROD
      particle_count: 6
      sound: ENTITY_BLAZE_SHOOT  # legacy enum name, auto-converted
      interval_ticks: 4
      on_start:                  # fires once, at windup start
        - type: sequence
          steps: [...]
```

- `cast_time: 0` (default) = instant cast.
- `telegraph.on_start` effects run with `targets = [caster]` only.
- Targeter runs when `cast_time` elapses, not when it starts.

---

## Effects

All effects go in an `effects:` list (skill top-level, `on_hit`, `on_start`/`on_tick`/`on_expire`, sequence step). Looked up by `type:`.

### damage
```yaml
- type: damage
  amount: 8          # default 1
```

### heal
```yaml
- type: heal
  amount: 8          # default 1
```
Clamped to max health.

### particle
```yaml
- type: particle
  particle: FLAME     # any org.bukkit.Particle
  count: 20            # default 10
  color: { r: 255, g: 0, b: 0 } # DUST only
  dust_size: 1.0                # DUST only, default 1.0
```
Spawns at each target's location +1 block up, (0.3,0.3,0.3) spread. One-shot only; use `shape` for animated/hitboxed particles.

### potion
```yaml
- type: potion
  effect: SLOWNESS               # any org.bukkit.potion.PotionEffectType
  duration_ticks: 100             # default 100
  amplifier: 0                    # default 0
```

### glow
```yaml
- type: glow
  color: RED           # any org.bukkit.ChatColor name, default WHITE
  duration_ticks: 100  # default 100
```
Vanilla `GLOWING` (see [potion](#potion) above) has no color of its own - the outline color is entirely controlled by whatever scoreboard team the entity belongs to. `glow` is the effect that actually makes a *colored* glow possible: it gets-or-creates a shared, permanently-registered scoreboard team per color (`skillsapi_glow_<COLOR>`), adds the target to it, applies ordinary `GLOWING` on top, and removes just that target's entry from the team again once `duration_ticks` elapses. The team itself is never deleted - it's shared by every `glow` effect using that color, on any entity, for as long as the plugin is loaded.
- If a target is already glowing a different color when a new `glow` effect is applied, both team memberships can briefly overlap - Minecraft resolves that using whichever team assignment the client currently has last, so stacking two different `glow` colors on the same entity back-to-back isn't a reliable way to get a specific final color; space them out or don't overlap durations if the exact color matters.

### knockback
```yaml
- type: knockback
  strength: 1.5       # default 1
```
Pushes targets away from caster (never the caster itself), min 0.3 upward lift.

### status effect
```yaml
# reference
- type: status
  status: frozen

# inline
- type: status
  behavior:
    type: dash          # dash | frozen | omit for cosmetic
    distance: 8              # dash only
    horizontal_only: true    # dash only
    disable_gravity: true    # dash only
  duration_ticks: 6
  tick_interval_ticks: 1
  refreshable: true
  on_start: [...]
  on_tick: [...]
  on_expire: [...]
```
See [Statuses](#statuses). Applies to `context.getTargets()`.

### projectile
```yaml
- type: projectile
  particle: FLAME              # optional trail
  speed: 15                     # blocks/sec, default 15
  max_distance: 20              # default 20
  gravity: false                 # default false
  collide_with_blocks: true      # default true
  hit:
    radius: 1.0                # default 1.0
    pierce: 1                  # default 1
    effects:                   # required
      - type: damage
        amount: 12
```
Simulated point, moves from caster's eye along look direction. `hit.pierce` = max entities hit before stopping. `hit.effects` can nest any effect type, including another `projectile` or `shape`.

### summon
```yaml
- type: summon
  entity: SKELETON              # any EntityType
  count: 1                       # default 1
  spawn_radius: 1.5              # default 1.5
  name: "Skeletal Thrall"        # optional
  health: 20                     # optional
  equipment:
    main_hand: BOW                 # optional
    helmet: WITHER_SKELETON_SKULL  # optional
  duration_ticks: -1             # default -1 = no timer
  cap:
    max: 4                         # default 0 = uncapped
    on_exceeded: refuse            # refuse (default) | dismiss_oldest
  ai:
    aggro_radius: 12               # default 12, 0 = no auto-aggro
    follow_radius: 10              # default 10
    move_speed: 1.0                # default 1.0
    interval_ticks: 10             # default 10
  on_summon:                     # fires once per minion
    - type: particle
      particle: SOUL
      count: 15

- type: dismiss_summons           # dismisses caster's tracked summons
```
Real Bukkit mobs with vanilla AI, not simulated. Plugin-added behavior:

- Never targets its owner, a sibling summon, or *any other* tracked summon
  (any owner) - a target-event veto plus a direct damage block cover the
  owner/sibling case in both directions (a summon can't hurt its owner, and
  the owner can't hurt their own summon either). Cross-owner summon-vs-summon
  targeting is additionally self-corrected every `ai.interval_ticks`: each
  minion re-validates whatever target it currently has, not just brand new
  picks, since vanilla "hurt by" revenge AI can set a target through a path
  that skips the Bukkit target-event entirely on some mobs/versions - so a
  bad target only sticks for one tick at most instead of indefinitely.
- Idle summons auto-target the nearest valid `LivingEntity` (excludes players, armor stands, its own owner, and any tracked summon regardless of owner) within `ai.aggro_radius` every `ai.interval_ticks`.
- Idle summons instantly assist when the owner damages a non-summon, non-player entity within `ai.aggro_radius`.
- Idle summons beyond `ai.follow_radius` path back to the caster at `ai.move_speed`.
- `cap.max` checked pre-spawn. `refuse` fails the cast; `dismiss_oldest` frees a slot.
- `duration_ticks: -1` = lives until death/dismiss. Positive value = auto-despawn (with `POOF` particle) after that many ticks.
- Deaths are auto-cleaned from tracking via `EntityDeathEvent`.

Not handled:
- No `Tameable` perks (sitting, breeding, taming hearts) for e.g. `WOLF` summons.
- No custom ranged-attack logic beyond handing over the item.
- Summon aggro/assist and the [taunt](#taunt) threat table are independent systems.

See `skills/necromancer.yml`.

### taunt
```yaml
- type: taunt
  amount: 1000   # default 1000
```
`Monster`-only threat/aggro system. Every `Monster` tracks threat per attacker and targets whoever holds the most.

- Passive: any hit on a `Monster` generates threat = damage dealt. Always on, no config.
- Explicit: `type: taunt` adds a flat `amount` of threat, independent of damage.
- Threat table overrides vanilla targeting via `EntityTargetLivingEntityEvent`; wiped on death.
- Decays 10% every 5 seconds; negligible/invalid entries dropped.
- `Monster`-only — no effect on players/passive mobs as targets.
- Event priority: `SummonTargetListener`'s target-veto (`HIGH`) runs before `ThreatListener` (`NORMAL`) - a hard "never attack your own owner/sibling" safety constraint always wins over a threat-based preference, regardless of which listener happens to be registered first. The owner-can't-damage-own-summon block runs at `LOWEST` instead, deliberately *before* the assist-notification handler (`NORMAL`) - otherwise a blocked hit on your own summon would still notify your other summons to "assist" against their sibling before the cancellation took effect.

See `skills/tank.yml` (`shield_slam`, `provoke`).

### shape
 
Particle visual + optional hitbox. One or more animated **layers**, positioned by an **anchor**, optionally **traveling**, optionally **hitting**.
 
 ```yaml
 - type: shape
   anchor: self               # self | self_fixed | target | cursor | cursor_locked - default self
   cursor_range: 20            # cursor/cursor_locked only, default 20
   duration_ticks: 40          # default 40
   interval_ticks: 2           # render rate, default 2
   offset: { x: 0, y: 1.5, z: 1 }   # local (right/up/forward) constant nudge from anchor
   travel:
     speed: 10
     max_distance: 15
     gravity: false
     collide_with_blocks: true
   hit:
     radius: 1.2              # 0/omit = no hit detection
     area: points             # points | disk | sphere - default points
     height: 2.0              # disk only
     once: true               # default true
     interval_ticks: 2        # default = interval_ticks
     include_self: false
     disarm: false            # default false
     effects:
       - type: damage
         amount: 10
   visible_to: everyone         # everyone | caster_only - default everyone
   debug_hitbox: false
   debug_hitbox_points: 8
   layers:                      # required
     - shape: ring
       ...
 ```
 
 #### Anchors
 
 | `anchor:` | Behavior |
 |---|---|
 | `self` (default) | Follows caster every tick |
 | `self_fixed` | Snapshot at effect start |
 | `target` | Follows `context.getTargets()[0]`, falls back to caster |
 | `cursor` | Raycasts caster's aim once, at effect start |
 | `cursor_locked` | Same as `cursor`, but cached on `SkillContext` — reused by other `cursor_locked` shapes in the same cast |
 
 `self`/`target` update live; `self_fixed`/`cursor`/`cursor_locked` resolve once (travel/offset/layer animation still apply on top).
 
 #### offset
 
 Constant local-space (right/up/forward, relative to caster's current facing) nudge on the anchor point, applied every tick. Distinct from a layer's `start_offset`/`end_offset` (animated, world-space, per-layer).
 
 #### travel
 
 Moves the whole shape forward over time. Cancels early on block collision (if `collide_with_blocks`) or at `max_distance`.
 
 #### Hit detection
 
 Defined under the `hit:` block.
 
 | Field | Notes |
 |---|---|
 | `radius` | `<= 0`/omit disables hit detection |
 | `area` | `points` (default) tests sphere of `radius` around points. `disk`/`sphere` tests flat disk or full sphere. |
 | `height` | disk only - vertical tolerance above/below center |
 | `once` | Default true — one hit per entity per effect lifetime |
 | `interval_ticks` | Independent clock from rendering `interval_ticks` |
 | `include_self` | Default false — caster excluded from hit-scans |
 | `disarm` | Default false — true ends the whole shape effect on first hit |
 | `effects` | Nested list of effects to run against what was hit |
 
 #### Traps (caster-only visibility)
 
 `visible_to: caster_only` sends particles to the caster only (hitbox unaffected, fully server-side). Combined with long `duration_ticks` + `hit.disarm: true` = single-use hidden trap. `hit.disarm: false` = reusable trap.
 
 - `effects` under `hit:` always broadcast normally regardless of `visible_to`.
 - `debug_hitbox` always broadcasts to everyone.
 - `visible_to: caster_only` falls back to normal broadcast for non-`Player` casters.
 
 See `skills/traps.yml` (`hunters_snare`, `venom_dart_trap`).

#### Layers

```yaml
layers:
  - shape: ring                # RING | SPHERE | HALF_SPHERE | LINE | HELIX | CYLINDER | BOX | ARC | CONE | POINT | PARAMETRIC
    particle: SOUL_FIRE_FLAME    # required
    particle_count: 1             # default 1
    color: { r: 255, g: 0, b: 0 } # DUST only
    dust_size: 1.5                 # DUST only, default 1.0

    # geometry (varies by shape - see table below)
    points: 24
    radius: 2.0
    length: 3.0
    width: 2.0
    height: 2.0
    turns: 2.0
    arc_degrees: 90
    rings: 3
    plane: horizontal              # horizontal | vertical - RING only
    y_offset: 0                     # any shape
    random_distribution: false      # CYLINDER, BOX only
    radius_jitter: 0                # CYLINDER only, 0-1
    facing_relative: false           # CYLINDER, HELIX, BOX, PARAMETRIC only

    # animation (every shape type)
    rotation: { x: 0, y: 0, z: 0 }
    rotate_x_deg_per_sec: 0
    rotate_deg_per_sec: 0
    rotate_z_deg_per_sec: 0
    scale_start: 1.0
    scale_end: 1.0
    scale_axis: uniform             # uniform | radial | vertical
    pulsate_amplitude: 0
    pulsate_frequency: 1.0
    rise_per_sec: 0
    start_offset: { x: 0, y: 0, z: 0 }
    end_offset: { x: 0, y: 0, z: 0 }
```

**Shape types:**

| `shape:` | Uses | Facing-relative? |
|---|---|---|
| `ring` | `points`, `radius`, `plane` | Only if `plane: vertical` |
| `sphere` | `points`, `radius` | No |
| `half_sphere` | `points`, `radius` (top half) | No |
| `line` | `points`, `length` | Always |
| `helix` | `points`, `radius`, `height`, `turns`, `facing_relative` | Only if `facing_relative: true` |
| `cylinder` | `points`, `radius`, `height`, `rings`, `random_distribution`, `radius_jitter`, `facing_relative` | Only if `facing_relative: true` |
| `box` | `points`, `width`, `height`, `length`, `random_distribution`, `facing_relative` | Only if `facing_relative: true` |
| `arc` | `points`, `radius`, `arc_degrees` | Always |
| `cone` | `points`, `radius`, `arc_degrees`, `rings` | Always |
| `point` | none | No |
| `parametric` | `points`, `formula_x/y/z`, `facing_relative` | Only if `facing_relative: true` |

`facing_relative` on `cylinder`/`helix`/`box`:
- `false` (default): grounded — `cylinder`/`box` rise y=0→`height`; `helix` spirals world Y.
- `true`: beam orientation, long axis = local +z (matches `line`).

`box` default `points` is `96` (8/edge) — override needs ≥48 for a visible wireframe; `points` splits evenly across 12 edges, min 2/edge. Doesn't apply to `random_distribution: true`.

`cylinder` distribution modes:
- `random_distribution: false` (default): stacked rings, `rings` levels, `points / rings` per ring.
- `random_distribution: true`: independent random angle/height per point, `rings` ignored. `radius_jitter` (0-1) randomizes distance from center.
- Point cloud generated once at config load, not per-cast.

#### Custom curves (`shape: parametric`)

```yaml
- shape: parametric
  particle: DUST
  color: { r: 230, g: 60, b: 130 }
  points: 200                  # sample count
  formula_x: "cos(5 * t * tau) * cos(t * tau) * 3"
  formula_y: "0"
  formula_z: "cos(5 * t * tau) * sin(t * tau) * 3"
  facing_relative: false
```

- `formula_x/y/z` required, evaluated once per sample point at config load.
- Variables: `t` (0→1), `i` (sample index), `n` (sample count), `pi`, `tau`, `e`.
- Functions: `sin cos tan asin acos atan atan2 sqrt abs pow min max floor ceil exp log`. Standard precedence, `^` exponent (right-assoc), parens, unary minus.
- Custom evaluator (`MathExpression.java`) — no variables/loops/branches/strings, math only.
- Malformed formula throws at config load (sample index + field named).
- `y_offset` still applies afterward.

**Animation fields (all shapes):**

| Field | Default | Effect |
|---|---|---|
| `rotation` | `{0,0,0}` | Constant base tilt (degrees), applied once |
| `rotate_x_deg_per_sec` / `rotate_deg_per_sec` / `rotate_z_deg_per_sec` | 0/0/0 | Continuous spin per axis (pitch/yaw/roll), applied X→Y→Z on top of `rotation` |
| `scale_start` / `scale_end` | 1.0/1.0 | Lerped over `duration_ticks` |
| `scale_axis` | `uniform` | `uniform` (x/y/z), `radial` (x/z only), `vertical` (y only) |
| `pulsate_amplitude` / `pulsate_frequency` | 0/1.0 | Scale *= `1 + amplitude * sin(2π * frequency * elapsedSeconds)` |
| `rise_per_sec` | 0 | Adds `rise_per_sec * elapsedSeconds` to Y each tick |
| `start_offset` / `end_offset` | `{0,0,0}` | Animated world-space translation of layer center, lerped over duration |

---

### sequence

Chains stages of effects with delays between them.

```yaml
- type: sequence
  steps:
    - effects:
        - type: shape
          duration_ticks: 20
          layers: [...]
      # no delay_ticks -> defaults to this step's shape duration_ticks
    - effects:
        - type: shape
          duration_ticks: 15
          layers: [...]
        - type: damage
          amount: 12
      delay_ticks: 5             # optional override
      wait_for_shape_duration: false  # optional
```

- Step containing `type: shape` auto-delays the next step by that shape's `duration_ticks`, unless `delay_ticks` is set.
- `wait_for_shape_duration: false` skips the auto-wait (0 delay).
- Multiple shapes in one step: only the last parsed sets the auto-delay.
- Nests anywhere any other effect does.

---

## Statuses

```yaml
statuses:
  frozen:
    behavior:
      type: frozen             # dash | frozen | omit for cosmetic
    duration_ticks: 60            # -1 = indefinite
    tick_interval_ticks: 5
    refreshable: true              # default true
    on_start: [...]
    on_tick: [...]
    on_expire: [...]
```

Effects in `on_start`/`on_tick`/`on_expire` run with the affected entity as both caster and sole target.

| `behavior.type` | Extra fields (in `behavior:` map) | Effect |
|---|---|---|
| `dash` | `distance` (6), `horizontal_only` (true), `disable_gravity` (true) | Constant-speed shove for `duration_ticks`, covering `distance` blocks. Direction: held movement key (players) → existing velocity → look direction. Uses vanilla collision. |
| `frozen` | - | Zeroes velocity every tick |
| *(omitted)* | - | Cosmetic only (hooks, no lock) |

`duration_ticks: -1` = never auto-expires; needs explicit removal.

---

## Mob templates

`mobs/*.yml` (own top-level directory, alongside `skills/` - parsed by `MobConfigParser`, registered in `MobTemplateManager`):
```yaml
mobs:
  frost_wraith:
    type: WITHER_SKELETON        # required, any EntityType
    display_name: "&bFrost Wraith"
    health: 60                    # optional, overrides vanilla max health
    equipment:
      main_hand: DIAMOND_SWORD
      off_hand: SHIELD
      helmet: BLUE_ICE
      chestplate: null
      leggings: null
      boots: null
    armor: 4                       # optional, flat armor points
    move_speed: 1.0                # default 1.0
    despawn: false                  # default false
    skills:
      - trigger: on_spawn
        skill: frost_wraith_intro
      - trigger: on_timer
        interval_ticks: 100
        skill: frost_nova
        chance: 0.5
      - trigger: on_damaged
        skill: retaliate_shard
        cooldown: 3000
      - trigger: on_death
        skill: death_burst
      - trigger: on_low_health
        health_percent: 0.3
        skill: enrage
```

| Field | Notes |
|---|---|
| `type` | required, any `EntityType` |
| `display_name` | optional, `&`-color codes |
| `health` | optional, overrides vanilla max health |
| `equipment` | optional, per-slot `Material` |
| `armor` | optional, flat armor points |
| `move_speed` | default 1.0, multiplies the entity type's vanilla speed attribute |
| `despawn` | default false - `false` sets `setRemoveWhenFarAway(false)` so it persists like a named vanilla mob |
| `skills` | list of trigger bindings, see below |

`despawn: false` (default) does not mean "prevent all removal" - a persistent, equipped mob still dies normally in combat; it just won't vanish from the "far away, unloaded chunk" despawn check the way a random unnamed mob would.

See `mobs/frost_wraith.yml` + `skills/mob_abilities.yml` for a complete example (all 5 triggers, uses `radius` targeters since a mob caster has no crosshair).

---

## Mob skills & triggers

Binds an existing `skills:`-registry skill (same registry/parser as player-cast skills) to a mob template event.

```yaml
- trigger: on_timer      # see table below
  skill: frost_nova       # skill id from skills/*.yml
  chance: 1.0              # default 1.0
  cooldown: 0               # ms, default 0 - per-mob-instance
  interval_ticks: 100       # on_timer only
  health_percent: 0.3       # on_low_health only
```

| `trigger:` | Fires | Extra fields |
|---|---|---|
| `on_spawn` | Once, on spawn | - |
| `on_death` | Once, on death | - |
| `on_damaged` | Every hit taken | - |
| `on_attack` | Every melee hit landed | - |
| `on_timer` | Every `interval_ticks` while alive | `interval_ticks` |
| `on_low_health` | Once, first tick health ≤ `health_percent` | `health_percent` |

- The mob is the skill's caster (`Skill.cast(mobEntity)` directly, not through `CastEngine` - a mob's triggered skill always resolves instantly, no cast-time channel) - `targeter: self` affects the mob; `single`/`radius`/`cone` target from the mob's position/facing. A mob has no crosshair, so `radius` is the usual choice.
- Multiple triggers can bind the same skill.
- `chance` is independent of the bound skill's own `cost`/`cooldown`/`min_health_percent` conditions - those still apply.
- Each binding's own `cooldown` is separate from, and on top of, the bound skill's own per-caster `cooldown` (both are keyed by the mob's UUID, so they stack).
- `on_low_health` fires at most once per binding per mob's lifetime - it does not re-fire if healed back above `health_percent` and dropping again.
- Spawning: `/skillsapi spawnmob <templateId>` (op only, see below).

---

## Commands & item binding

| Command | Permission | Does |
|---|---|---|
| `/cast <skillId>` | - | Casts a skill as yourself via `CastEngine` |
| `/skillsapi reload` | `skillsapi.reload` (op) | Re-reads `skills/*.yml` + `resources.yml` |
| `/skillsapi bind <skillId>` | `skillsapi.bind` (op) | Adds a skill to the held item's rotation (see below). No-op if it's already bound. |
| `/skillsapi unbind [skillId]` | `skillsapi.bind` (op) | Removes just that skill from the rotation, or the whole rotation if no id given |
| `/skillsapi spawnmob <templateId>` | `skillsapi.spawnmob` (op) | Spawns a `mobs/*.yml` template 3 blocks in front of you |

**An item can carry any number of bound skills** as an ordered rotation
(`BoundSkillList`), not just one:

```
/skillsapi bind fireball
/skillsapi bind heal
/skillsapi bind dash
```

Right-click casts whichever skill is currently *selected* - `fireball`
first, here, since it was bound first. Shift+right-click doesn't cast
anything; it advances the selection to the next skill in the rotation
(wrapping back to the first once it reaches the end) so you can cycle
through everything bound to that item without needing a second item or a
menu. Binding only one skill still works exactly like a plain single-skill
item always has - shift+right-click just re-selects the same one each time,
which is a harmless no-op.

The item's lore tracks the rotation live: which skill right-click currently
casts, your position in the rotation (`2/3`), and the full list with the
selected one bracketed - all refreshed automatically on every bind, unbind,
and cycle, so the lore can never drift out of sync with what clicking the
item actually does.

Items bound before this existed (a fixed `primary`/`secondary` pair) still
work unchanged - they're read as a 2-skill rotation the first time the item
is clicked or touched by `bind`/`unbind`, no migration step needed on your
end.

---

## YAML anchors

```yaml
_frost_dust: &frost_color
  r: 120
  g: 200
  b: 255

skills:
  frost_ring:
    effects:
      - type: shape
        layers:
          - shape: ring
            particle: DUST
            color: *frost_color
            radius: 2.0

  frost_nova:
    effects:
      - type: shape
        layers:
          - shape: sphere
            particle: DUST
            color: *frost_color
            radius: 4.0
```

`&name` defines, `*name` reuses — standard YAML, no plugin-side effect. Works for colors, `layers:` blocks, `on_hit:` blocks, anything.

---

## Notes

- `targeter` only sets targets for the skill's own top-level `effects:`; `projectile`/`shape` `on_hit` gets its own context.
- `cursor` re-raycasts per step; `cursor_locked` shares one raycast per cast.
- `travel` + `anchor: self`/`target`: re-snapping fights travel's velocity. Use `self_fixed`/`cursor`/`cursor_locked` instead.
- `hit_area: points` with low `points` count can let fast targets slip through gaps.
- Facing basis recomputes every tick regardless of anchor; even fixed-position anchors re-aim direction.
- `rotation`/`rotate_*_deg_per_sec` apply X → Y → Z, after any facing-relative transform.
- `scale_axis` is a no-op if `scale_start == scale_end`.
- `random_distribution`: CYLINDER/BOX only. `radius_jitter`: CYLINDER only. No-op elsewhere.
- `facing_relative`: CYLINDER/HELIX/BOX/PARAMETRIC only. `line`/`arc`/`cone` are always facing-relative; `ring` uses `plane` instead.
- `facing_relative` follows the caster's current look, not a traveling shape's direction of flight.
- `formula_x/y/z`: PARAMETRIC only. `parametric` ignores `radius`/`length`/`height`/`turns`/`arc_degrees`/`rings`.
- `plane: vertical` is RING-only.
- `start_offset`/`end_offset` stack additively with `rise_per_sec`.
- A `sequence` step's non-shape effects (e.g. `damage`) target the outer skill's original targets, not that step's own shape's hits — give the shape its own `hit_radius` + `on_hit` for that.
- `hit_once: false` needs `hit_interval_ticks` raised above `interval_ticks`, or `on_hit` refires every scan.
- `color`/`dust_size` apply to DUST particles only.
- `min_health_percent` failures are silent (no cast feedback); `cost` failures notify via `CastFeedback`.
- `summon.duration_ticks` defaults to `-1` (no timer) — opposite of `shape`/`status` defaults.
- `taunt` only affects `Monster` targeting; a `summon`'s own aggro is separate and ignores the threat table.

---

## Full worked example

```yaml
skills:
  meteor_lance:
    cooldown: 12000
    targeter: self
    cast_time: 600
    interruptible: true
    cost:
      type: mana
      amount: 30
    telegraph:
      particle: FLAME
      particle_count: 4
      interval_ticks: 3
    effects:
      - type: sequence
        steps:
          - effects:
              - type: shape
                anchor: cursor_locked
                cursor_range: 30
                duration_ticks: 24
                layers:
                  - shape: ring
                    particle: DUST
                    color: { r: 255, g: 120, b: 30 }
                    dust_size: 1.4
                    points: 32
                    radius: 3.0
                    rotate_deg_per_sec: 120
                    scale_start: 1.4
                    scale_end: 0.4
          - effects:
              - type: shape
                anchor: cursor_locked
                duration_ticks: 20
                hit:
                  radius: 3.5
                  area: disk
                  height: 2.5
                  once: true
                  effects:
                    - type: damage
                      amount: 14
                    - type: status
                      behavior: frozen
                      duration_ticks: 40
                layers:
                  - shape: cylinder
                    particle: FLAME
                    points: 220
                    radius: 1.8
                    height: 8
                    random_distribution: true
                    radius_jitter: 0.1
                    scale_start: 1.0
                    scale_end: 1.0
                    scale_axis: vertical
                    start_offset: { y: 18 }
                    end_offset: { y: 0 }
```
