# Meridian Interaction Test

A demo / sandbox panel for [Meridian Proxy](../meridian-proxy)'s
interaction-chain forging — a pure Layer-2 module built on top of
`meridian-core`.

It talks only to neutral APIs (`meridian-api` + `meridian-core-api`) and never
touches raw Hytale packets, so a Hytale protocol update cannot break it. The
actual interaction chains are built and submitted by `meridian-core`'s
`InteractionControl` service.

## Requirements

This module **requires `meridian-core` ≥ 0.3.0** — it acts on `Block`s through
`InteractionControl`, queries the `World` for scans, and (optionally)
subscribes to `SelectionBus` for cross-module fill of the X/Y/Z fields. Put
**both** jars in the proxy's modules folder:

```
modules/
├── meridian-core-impl-*.jar
└── meridian-interaction-test-*.jar
```

`meridian-core` loads first (the module's `module.json` declares
`dependsOn: meridian-core >=0.3.0`). Without it, interaction-test is skipped
with a warning.

## Features

Two sections in the settings panel:

### Single block

Pick a target by typing **X / Y / Z**, then hit one of the action buttons:

- **Fill from last observed block** — pulls coordinates from the last block
  the player interacted with in-game (or any block published through
  `SelectionBus`, e.g. by clicking a row in [meridian-esp](../meridian-esp)'s
  "Nearest blocks" list).
- **Use on block** — `block.use()`. Opens chests, talks to NPCs, harvests crops.
- **Hit block** — `block.hit()`. Breaks blocks; uses the actively held tool.
- **Plant on block** — `block.plant()`. Plants the seed currently in the
  hotbar onto tilled soil.
- **Water block** — `block.water()`. Waters tilled soil using the watering
  can.

X/Y/Z are session-only; the values you type don't survive a restart.

### Scan radius

Sweep a cube around the player and act on every matching block. Parameters
are persisted across restarts:

- **Radius** — half-side of the cube scanned (1..64).
- **Name (contains)** — substring filter on the block-type name. Empty matches
  everything.

Actions:

- **Use nearby** — `use()` every block whose name matches.
- **Break nearby** — `hit()` every non-air block in range; auto-re-hits each
  target until it reports as air (capped at 12 attempts per block so an
  unbreakable block can't loop forever).
- **Water nearby** — switches to the watering can, waters every tilled-soil
  block in range, switches back to the original hotbar slot.
- **Plant nearby** — switches to the seed item, plants on every empty
  tilled-soil block in range, switches back.

Each action is logged with a short summary line to the module's logger.

## Cross-module flow

Interaction-test is a subscriber on `SelectionBus`:

1. Some other module publishes a `BlockPos` (currently
   [meridian-esp](../meridian-esp) does so on row-clicks).
2. Interaction-test's listener is called.
3. It hops to the Swing EDT and writes the coordinates into the X/Y/Z fields
   via `SettingBinding<String>.set(...)`.
4. The user clicks any "single block" action — the fields are already filled.

If `SelectionBus` is missing (older core, or service not registered yet) the
module degrades quietly — the panel still works, the cross-module fill just
doesn't happen.

## Build

```sh
mvn clean package
```

Needs `meridian-api` and `meridian-core-api` in the local Maven repo — build the
[`meridian-proxy`](../meridian-proxy) and [`meridian-core`](../meridian-core)
repos first (`mvn install`). Produces the loadable module:

```
target/meridian-interaction-test-<version>.jar
```

Or build every Meridian module at once with the repo-root `build-releases.ps1`,
which collects all jars into `_releases/`.

## How it works

Interaction chains in Hytale are stateful sequences of `ChainingInteraction` /
`ParallelInteraction` / `ReplaceInteraction` ops the server walks tick by tick.
Forging them from outside the client is non-trivial — there's a VM, an item
registry, an inventory tracker, a chunk tracker, and a chain-id NAT to keep
forged chain ids from colliding with the player's own.

The split:

- **`meridian-core`** (Layer-1) owns the whole forge machinery — `World`,
  `Block`, `InteractionControl`, the registries and trackers, plus the
  `SelectionBus` pub/sub. All `meridian-protocol` contact lives here.
- **`meridian-interaction-test`** (Layer-2) is a thin
  [`SettingsSpec`](../meridian-proxy/docs/settings.md)-rendered panel: text
  fields, buttons, a scheduler-free body that delegates everything to the
  core API.

It's also the canonical worked example of the new settings primitives —
`.button(...)`, `SettingBinding<String>` for two-way text fields, opt-in
`.persistent(...)`, and the `SelectionBus` subscriber pattern. See
[docs/settings.md](../meridian-proxy/docs/settings.md) for the full API.
