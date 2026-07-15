# SkillsAPI

A small skill framework for a Paper/Spigot MMORPG server. No mods needed -
this is a plugin, written in Java, meant to be built with Gradle.

## How it's put together

- **Targeter** — decides *who* gets hit (self, single-target ray trace, radius AOE, cone).
- **Condition** — decides *if* the skill is allowed to fire (health threshold example included).
- **SkillEffect** — decides *what* happens (damage, heal, potion effect, particles, knockback,
  or a traveling `projectile` — see below).
- **Skill** — glues one Targeter + any number of Conditions + any number of Effects
  together, and tracks per-player cooldowns. `Skill.cast()` is a plain synchronous
  resolve: run conditions, resolve targets, run effects. That's still true even for
  channeled skills (below) — it's just called later instead of immediately.
- **SkillManager** — a registry you look skills up by id in.
- **ResourceManager** — generic named resource pools (mana, stamina, rage, whatever
  you define in `resources.yml`) per entity, with passive regen. In-memory only;
  see the note in `ResourceManager.java` if you need it to survive a restart.
- **StatusManager** — tracks persistent, over-time effects (dash, frozen, whatever
  you add next) per entity, and doubles as a registry for reusable named statuses
  defined once and referenced by id from any skill — see below.
- **SkillConfigParser** — reads `skills.yml` and builds Skills out of it, so you (or
  a builder on your team) can add new skills without touching Java at all. A
  skill's `cost:` block automatically becomes a `ResourceCostCondition` (gates
  the cast) + a `ResourceConsumeEffect` (spends it on success).

### Cast times, telegraphs, and interrupts

A skill can have a `cast_time` (ms). If it does, casting it doesn't resolve
instantly — it starts a **channel**: a `Telegraph` (looping particle/sound at
the caster) plays for the duration, and if the caster takes damage the cast
is cancelled before it does anything, unless the skill sets
`interruptible: false`. Only when the cast time elapses does `Skill.cast()`
actually run — targeting included, so a channeled skill re-aims at whatever
you're facing (or whoever's still in range) the moment it resolves, not when
you started casting.

This is handled by three new pieces:
- **`CastManager`** — tracks who's currently channeling what, so anything
  (today: taking damage, via `CastInterruptListener`) can cancel it.
- **`CastEngine`** — the actual entry point for casting: `attemptCast(skill, caster)`
  returns a `CastAttemptResult` (`RESOLVED_INSTANTLY`, `CHANNEL_STARTED`,
  `ALREADY_CASTING`, `ON_COOLDOWN`, `CONDITION_FAILED`, `NO_TARGET`) so the
  caller can give immediate feedback even though the actual resolution (for a
  channeled skill) happens later.
- **`Telegraph`** — the particle/sound loop during a windup; parsed from a
  skill's `telegraph:` block. It also has an `on_start:` list of effects
  fired once, right when the windup begins (self-only context) — use this to
  give the windup its own animation instead of just a repeating particle.

`Skill.cast()` itself is unchanged and still works fine if you call it
directly (see "Wire skills to something other than `/cast`" below) — you'll
just skip cast times entirely, which is correct for anything with no
`cast_time` set (the default, and everything that behaved this way before).

### Chaining animations with `sequence`

A `sequence` effect plays a chain of stages one after another instead of all
at once: stage 1's animation plays, and once it *finishes* (or after an
explicit delay) stage 2 fires, and so on. It's a plain effect, so it's usable
in both places you'd want a chained animation:

- inside a `telegraph.on_start:` — the windup itself becomes a multi-stage
  tell (e.g. a small warning glyph that widens into a bigger one) instead of
  one particle repeating.
- in a skill's top-level `effects:` — the "after the windup resolves" half,
  where the chain typically ends in a `damage`/`heal` alongside its last
  animation stage.

```yaml
effects:
  - type: sequence
    steps:
      - effects:
          - type: shape
            duration_ticks: 20   # next step auto-waits this long
            layers: [...]
      - effects:
          - type: shape
            duration_ticks: 15
            layers: [...]
          - type: damage          # fires once stage 1 has finished
            amount: 12
```

Each step's wait is auto-derived from its own `shape`'s `duration_ticks` —
you don't have to duplicate the timing by hand. Override it with an explicit
`delay_ticks:` on the step, or set `wait_for_shape_duration: false` to chain
immediately with no wait. See `arcane_judgement` in `skills.yml` for a full
telegraph-then-payoff example, and `ShapeLayer.ScaleAxis` (`scale_axis:
uniform | radial | vertical` on a layer) for making a `cylinder` grow in
height only instead of ballooning outward as it scales.

One caveat: a queued sequence step isn't tied to `CastManager` — if a
telegraph gets interrupted partway through its `on_start` chain, any stages
already scheduled will still fire on their timers (this matches how a normal
`shape` effect's animation already isn't cancellable once it's running).

### Reloading config without restarting

`/skillsapi reload` re-reads `skills.yml` and `resources.yml` straight off
disk and rebuilds the skill/resource registries in place - no restart, no
full-server `/reload`. Requires the `skillsapi.reload` permission (defaults
to op). Edit the files under `plugins/SkillsAPI/`, run the command, done.

This is exactly the same code path the plugin uses on startup
(`SkillsPlugin#reloadConfigs`), so there's nothing special about the first
load vs. a later reload. A couple of things worth knowing:
- Skills removed from `skills.yml` disappear from the registry immediately.
  A skill mid-channel when you reload keeps resolving against the `Skill`
  object it already captured, so an in-progress cast still finishes normally.
- Resource *definitions* (`max`/`regen_per_second`) refresh for every type
  still present in `resources.yml`. A type you delete from the file is left
  alone rather than removed - see the note in `ResourceManager.java`.

### Shape effects - flexible particle skill visuals

The `shape` effect type is the general-purpose building block for anything
that should look and feel like a real skill instead of a plain damage number
with a particle puff - magic circles, rising domes, spinning blade fans,
traveling wedges, pulsing auras, all built from the same handful of pieces:

- **Shape** - the geometry of one layer: `ring`, `sphere`, `half_sphere`,
  `line`, `helix`, `cylinder` (a hollow vertical tube - stacked rings up a
  height, the column/light-beam shape), `arc` (a flat wedge), `cone`
  (several concentric arcs - a filled wedge), or `point`.
- **Stacking** - a `shape` effect has a `layers:` list. Put as many shapes
  in there as you want; they all render together every tick, each with its
  own particle, size, and animation. A ground rune + an outer blade ring +
  a rising helix of motes is three layers in one effect.
- **Animation** - each layer can independently `rotate_deg_per_sec` (spin
  around the vertical axis), `rise_per_sec` (climb over time), lerp
  `scale_start` -> `scale_end` over the effect's duration (grow/shrink), and
  `pulsate_amplitude`/`pulsate_frequency` (a sine wiggle on top of that scale,
  for a "breathing" look).
- **Anchor** - where the whole stack is centered: `self` (tracks the caster
  every tick - auras/domains that follow you), `self_fixed` (caster's
  position at cast time, doesn't follow), `target` (tracks your first
  target entity), or `cursor` (a ray-traced point out in front of you, for
  ground-targeted AOEs).
- **Travel** - an optional `travel:` block makes the whole anchor point fly
  forward like a projectile (`speed`, `max_distance`, `gravity`,
  `collide_with_blocks`) - for a traveling blade-fan or slash that has to be
  dodged rather than an effect that just happens on top of you.
- **Hitbox** - set `hit_radius` (> 0) and the shape checks for nearby living
  entities each tick and fires the `on_hit:` effect list (any ordinary
  `SkillEffect` - damage, knockback, potion, even another `shape` or
  `projectile`) against whatever it touches. `hit_once: true` (default)
  hits each entity a single time for the effect's whole duration;
  `hit_once: false` + `hit_interval_ticks` lets a standing aura tick
  repeatedly on anyone still inside.
- **Hit area** - `hit_area` controls *what shape* that hitbox actually is,
  independently of what the layers visually draw:
  - `points` (default) - a sphere of `hit_radius` around every rendered
    particle point. Right for thin traveling shapes (`line`/`arc`/`cone`)
    where the visual line basically *is* the hitbox.
  - `disk` - a flat circle of `hit_radius`, centered on the effect (after
    `offset`), with `hit_height` as the vertical tolerance checked both
    above and below center. This is what you want for a `ring` that should
    look like an outline but hit *anyone standing inside it*, not just
    people on the line - see `heal_circle` below.
  - `sphere` - a full 3D sphere of `hit_radius` centered on the effect,
    ignoring the layers' points - for a dome/nova whose hit area should
    just be "everything within N blocks", regardless of how the visual is
    built.
- **Offset** - `offset: {x, y, z}` nudges wherever the anchor resolved to
  (self/target/cursor) before anything renders or hit-tests. It's in the
  caster's local facing space, not raw world axes: `x` = right, `y` = up,
  `z` = forward. So `{y: 1.5}` centers the effect at chest height instead
  of at your feet, and `{z: 1.0}` pushes it a block out in front of you.
  Recomputed every tick from the caster's *current* facing, so an offset
  effect anchored on `self` will swing around with you as you turn.
- **Hitbox debug** - `debug_hitbox: true` draws the actual hit area in red
  particles so you can see it instead of guessing from the visual
  particles: a wireframe ball per point in `points` mode, or a single
  ring/wireframe-ball at the effect's center in `disk`/`sphere` mode. It's
  particle-heavy in `points` mode with a lot of points - turn it off once
  you've tuned things, or lower `debug_hitbox_points` (default 8).

`line`/`arc`/`cone` (and a `ring` with `plane: vertical`) are drawn facing
wherever the caster is currently looking, re-evaluated every tick - so they
track your aim even mid-channel. `ring` (default plane), `sphere`,
`half_sphere`, and `helix` are orientation-independent by design (a ground
rune shouldn't flip because you turned your head).

See `arcane_domain` (three stacked, independently-spinning layers around
the caster), `blade_fan` (a traveling wedge with a hitbox), `sanctuary_dome`
(a cursor-anchored, growing/pulsing dome with a repeat-tick heal/damage
aura), `heal_circle` (a `ring` that's visual-only, with `hit_area: disk`
covering the whole circle it encloses), `light_beam_strike` (a `cylinder`
column that erupts up to full height on cast), `arcane_judgement` (a
`sequence` effect chained both inside a telegraph and after it resolves),
and `trinity_pillar` (three colored `dust` rings converging on a point,
then a big light pillar) in `skills.yml` for full worked examples.

Full field reference for one `shape` effect entry:

```yaml
- type: shape
  anchor: self            # self | self_fixed | target | cursor
  cursor_range: 20         # only used by anchor: cursor
  duration_ticks: 40       # how long the whole effect runs
  interval_ticks: 2        # ticks between each render/hit-check step
  offset:                  # optional - nudges the anchor before rendering
    x: 0                    # right, relative to current facing
    y: 0                    # up
    z: 0                    # forward, relative to current facing
  hit_radius: 0            # > 0 enables hit detection
  hit_area: points          # points (per-particle sphere) | disk | sphere - see above
  hit_height: 2              # disk only: vertical tolerance, above & below center
  hit_once: true           # false = can hit the same entity again later
  hit_interval_ticks: 10   # cooldown per entity when hit_once is false
  include_self: false      # let the effect hit its own caster
  debug_hitbox: false      # true = draw the real hit area in red
  debug_hitbox_points: 8   # points per wireframe/ring - lower if it's too dense
  travel:                  # optional - omit for a stationary effect
    speed: 10              # blocks/second
    max_distance: 15
    gravity: false
    collide_with_blocks: true
  on_hit:                  # any normal effect list, applied to whatever's hit
    - type: damage
      amount: 10
  layers:                  # one or more - stack freely
    - shape: ring           # ring | sphere | half_sphere | line | helix | cylinder | arc | cone | point
      particle: SOUL_FIRE_FLAME
      points: 24            # point count in the shape
      particle_count: 1     # particles spawned per point per tick
      radius: 2.0            # ring/sphere/half_sphere/helix/cylinder/arc/cone
      length: 3.0            # line
      height: 2.0             # helix/cylinder
      turns: 2.0              # helix
      arc_degrees: 90          # arc/cone
      rings: 3                 # cone (concentric arcs) / cylinder (stacked cross-sections)
      plane: xz                # ring only: xz (ground, default) | vertical (portal, facing-relative)
      y_offset: 0                # local vertical offset baked into the shape
      rotate_deg_per_sec: 0       # spin around the vertical axis
      color:                       # optional - only takes effect when particle: dust
        r: 255                      # 0-255
        g: 0
        b: 0
      dust_size: 1.0                # dust particle size, only used alongside color
      scale_start: 1.0             # scale at t=0
      scale_end: 1.0                # scale at t=duration
      scale_axis: uniform           # uniform (x/y/z) | radial (x/z only) | vertical (y only)
                                     # cylinder/helix erupting *up* want vertical; a shape
                                     # widening in place without stretching height wants radial
      pulsate_amplitude: 0            # extra sine wobble on top of scale
      pulsate_frequency: 1.0
      rise_per_sec: 0                  # climb in world Y over time
```

**Adding a brand-new shape type** (e.g. a star, a lattice, whatever): add a
case to `ShapeType` and `ShapeGenerator.generate` (return a `List<Vector>` in
local space - x=right, y=up, z=forward if it should face the caster's aim,
otherwise treat x/y/z as plain world-ish offsets) and, if it should be
facing-relative, a case in `ShapeGenerator.isFacingRelative`. `ShapeLayer`
and `ShapeEffect` don't need to change at all - animation, stacking, anchor,
travel, and hitboxes all already work generically over any point cloud.

### Traveling projectiles

`SingleEntityTargeter` is a hitscan: it resolves instantly, so there's
nothing to dodge. The `projectile` effect type is different — it simulates
an actual point moving through the world tick by tick (with a particle
trail), and only applies its own nested `effects:` list once it actually
touches an entity, hits a block, or runs out of range. Since it takes real
time to arrive, it's dodgeable the way SingleEntityTargeter never was.

```yaml
effects:
  - type: projectile
    particle: FLAME
    speed: 18            # blocks/second
    max_distance: 25
    hit_radius: 1.5
    gravity: false        # true = arcs downward like a thrown object
    pierce: 1              # entities it can hit before stopping
    collide_with_blocks: true
    effects:                # this is its own SkillEffect list - nest anything,
      - type: damage        # including another projectile
        amount: 14
      - type: knockback
        strength: 1.5
```

A skill using `projectile` still needs a top-level `targeter:` (`self` is the
usual choice) purely so `Skill.cast()` has *something* to consider a valid
cast — the projectile does its own targeting from the caster's look
direction and doesn't use `context.getTargets()` at all.

See `meteor_strike` (telegraphed windup → single big hit) and
`piercing_bolt` (fast cast, pierces 3 targets) in `skills.yml` for full
examples of both new mechanics together.

### Statuses — persistent, over-time effects

A regular effect fires once. A **status** is something that lasts: dash
(you're moving for the next several ticks), frozen (you can't move for the
next few seconds), and anything else that needs to persist and keep doing
something rather than resolve instantly.

A `Status` is a definition, made of two independent pieces you can mix:
- `behavior` — Java-side continuous game logic that isn't just particles:
  locking movement, setting velocity every tick. Built-in behaviors:
  `dash` and `frozen` (see `DashStatusBehavior`/`FrozenStatusBehavior`) —
  add your own by implementing `StatusBehavior` for anything code-level a
  future status needs. Omit `behavior` entirely for a purely cosmetic status
  (a lingering aura with no gameplay effect of its own).
- `on_start` / `on_tick` / `on_expire` — plain `SkillEffect` lists, fired
  once at the start, every `tick_interval_ticks` while active, and once
  when it ends. **This is the part that replaced `DashEffect`**: instead of
  a dedicated effect class with two hardcoded particle fields (trail +
  foot ring), any status's `on_tick` is just a normal effects list — stack
  as many particle/shape/sequence effects as you want, in any combination.

Define one **inline**, right where it's used, for something only one skill
needs (dash's own movement — nobody else is going to reuse "wind_step's
specific dash," so there's no reason to name and register it separately):

```yaml
effects:
  - type: status
    behavior: dash
    duration_ticks: 6
    distance: 8
    horizontal_only: true
    disable_gravity: true
    on_tick:
      - type: particle
        particle: CLOUD
        count: 4
      - type: particle       # freely stacked - just another list entry
        particle: CRIT
        count: 2
```

Or define one **once, under a top-level `statuses:` section**, and
*reference* it by id from as many skills as want to inflict it — the point
being e.g. every future ice-themed skill can all apply the exact same
`frozen` without repeating its config:

```yaml
statuses:
  frozen:
    behavior: frozen
    duration_ticks: 60
    tick_interval_ticks: 5
    on_start:
      - type: particle
        particle: SNOWFLAKE
        count: 25
    on_tick:
      - type: particle
        particle: SNOWFLAKE
        count: 3

skills:
  frostbite_bolt:
    # ...
    effects:
      - type: projectile
        # ...
        effects:
          - type: damage
            amount: 5
          - type: status
            status: frozen     # reference, not a redefinition
```

The dividing line the parser uses: a `behavior:` key means "this is an
inline definition," no `behavior:` key means "`status:` must be a reference
to something registered under `statuses:`." See `wind_step` (inline dash)
and `frostbite_bolt` + the top-level `frozen` entry (referenced) in
`skills.yml` for both in full.

A status effect applies to `context.getTargets()` — same as
damage/particle/etc. — so a skill's own top-level `effects:` needs
`targeter: self` for a status that lands on the caster (dash), while a
projectile's/shape's nested `effects:`/`on_hit:` naturally targets whatever
it actually hit (frostbite_bolt's frozen).

## Building

You'll need internet access to Maven Central + PaperMC's repo + the Gradle
Plugin Portal (the sandbox this was generated in doesn't have that, so it
hasn't been compiled here — do a `./gradlew build` locally and the jar will
land in `build/libs/`).

Requires JDK 21 and matches Paper 1.21.11's API. Bump the `paper-api` version
in `build.gradle.kts` to target a different Minecraft version.

**Testing:** `./gradlew runServer` boots a real Paper 1.21.11 test server with
this plugin already loaded (via the `run-paper` Gradle plugin), so you don't
have to manually copy jars around while iterating.

**If you add a third-party library later** (an HTTP client, an extra parser,
etc.), add the `com.gradleup.shadow` Gradle plugin and run `shadowJar` instead
of `jar` — it bundles your dependencies into the output jar. Not needed yet:
this project has zero external dependencies besides Paper's own API.

## Using it

Drop the jar in `plugins/`, start the server once to generate
`plugins/SkillsAPI/skills.yml`, edit it, `/reload` (or restart).

In-game: `/cast fireball`, `/cast heal_burst`, `/cast whirlwind`, `/cast battle_shout`
(these are just the example skills — rename/add your own in skills.yml).

Resource pools (mana/stamina) start full for every player and regen once per
second based on `plugins/SkillsAPI/resources.yml`. Add a new pool type (e.g.
`rage`) just by adding it to that file — no code needed — then reference it
from any skill's `cost:` block.

## Extending it

**Add a new effect** (e.g. summon a projectile, apply custom knockup, run a
particle animation over several ticks):
1. Implement `SkillEffect` in the `effect` package.
2. Add a `case` for it in `SkillConfigParser.parseEffect` if you want it
   usable from YAML.

**Add a new targeter** (e.g. "lowest HP ally in radius", "all players in an arena"):
1. Implement `Targeter` in the `targeter` package.
2. Add a `case` in `SkillConfigParser.parseTargeter`.

**Wire skills to something other than `/cast`** — a right-click item, a mob's
attack, a quest trigger, a class system:
```java
SkillsPlugin plugin = (SkillsPlugin) getServer().getPluginManager().getPlugin("SkillsAPI");

plugin.getSkillManager().get("meteor_strike").ifPresent(skill ->
        plugin.getCastEngine().attemptCast(skill, somePlayer));
```
Register that call in a Bukkit event listener (`PlayerInteractEvent` for
right-click casting, `EntityDamageByEntityEvent` for mob skills on hit, etc.)
and you've got a trigger system — this framework deliberately doesn't own
triggers, so you can hook it into anything.

Going through `CastEngine.attemptCast` (rather than `skill.cast(caster)`
directly) is what gets you cast times/telegraphs/interrupts for free — it
falls straight through to an instant `skill.cast(caster)` for any skill with
no `cast_time`, so it's a safe default even if you don't care about
channeling for a particular trigger.

**Classes, XP, cast bars, unlocking skills per-level** — none of that is
opinionated here on purpose. A class system is just something that decides
*which skill ids* a player is allowed to `/cast` (or trigger some other way) -
it can live entirely outside this framework and call into it. Resource costs
are already built in via `cost:` in skills.yml + ResourceManager; if you want
per-player *max* resource (e.g. mana scales with level) instead of a global
max, that's the one place you'd extend ResourceManager - swap the flat
`ResourceDefinition.max()` lookup for something that reads a player attribute
or stat.
