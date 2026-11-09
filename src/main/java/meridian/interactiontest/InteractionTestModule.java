package meridian.interactiontest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.settings.SettingBinding;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.Block;
import meridian.core.api.BlockPos;
import meridian.core.api.BlockView;
import meridian.core.api.EntityTracker;
import meridian.core.api.InteractionControl;
import meridian.core.api.InteractionOutcome;
import meridian.core.api.Player;
import meridian.core.api.SelectionBus;
import meridian.core.api.Vec3;
import meridian.core.api.World;
import org.slf4j.Logger;

/**
 * meridian-interaction-examples — a Layer-2 demo / sandbox module for
 * meridian-core's interaction system. Pure Layer-2, no {@code meridian.protocol}.
 *
 * <p>Three workflows, all driven from a {@link SettingsSpec}-rendered panel:
 *
 * <ul>
 *   <li><b>Single block</b> — type X/Y/Z (or fill them from the last observed
 *       block, or from another module via {@link SelectionBus}), then hit one
 *       of the action buttons: <i>Use, Hit, Plant, Water</i>. The resolved
 *       {@link Block} runs through {@link InteractionControl}'s forge queue.</li>
 *   <li><b>Scan radius</b> — pick a cube around the player and a name filter,
 *       then <i>Use / Break / Water / Plant nearby</i>. The module scans the
 *       cube via {@link World}, filters by block-type name, and pipes each
 *       match into the forge queue. "Break nearby" auto-re-hits until each
 *       block reports as air (single hit isn't always enough).</li>
 *   <li><b>Forge</b> — one click acts on what the crosshair is on: the block (hit, use,
 *       right-click, place, dig swing), the entity (attack, use), or the air (use the held
 *       item, swing - held for the given time: a bow shoots past 100 ms). Each logs how the server ran the chain ({@link InteractionOutcome}):
 *       its status, the operations that failed, its forks, the corrections. The way to check
 *       core's forging by hand in a live game.</li>
 *   <li><b>Lights</b> — <i>Turn off lights nearby</i> scans a cube, filters by
 *       {@link meridian.core.api.BlockView#isLit()}, logs each block's
 *       {@link meridian.core.api.BlockView#state()}, and forges a <i>Use</i> on
 *       each — the worked example for the block-state projection. The <i>Auto
 *       (every 0.25s)</i> checkbox runs that sweep on a 4&nbsp;Hz ticker, keeping
 *       lamps in range off; a per-id guard ({@code litActuated}) actuates each lamp
 *       once and only re-acts after its block id has actually changed, so a lamp is
 *       turned off rather than toggled back and forth.</li>
 * </ul>
 *
 * <p>X/Y/Z are bound through {@link SettingBinding}s — both the "Fill from
 * last observed block" button and the cross-module {@link SelectionBus}
 * listener mutate the rendered fields directly. Scan parameters (radius,
 * name filter) are persisted; the X/Y/Z target itself is session-only.
 *
 * <p>This is the canonical worked example for actuators that need cross-module
 * input — see also {@code meridian-esp}'s "Nearest blocks" list, which
 * publishes through the same {@link SelectionBus}.
 */
public class InteractionTestModule implements ProxyModule {

    /** Cap on swings per block in 'Break nearby' — stops an unbreakable block looping. */
    private static final int MAX_HITS_PER_BLOCK = 12;

    /** How long a lamp's Use is watched for the server's change before the sweeps take over. */
    private static final Duration LIGHT_CHANGE_WAIT = Duration.ofSeconds(5);

    /** Auto-lights sweep cadence — the 0.25&nbsp;s tick requested for the area light-off. */
    private static final Duration AUTO_LIGHTS_PERIOD = Duration.ofMillis(250);

    // Live-mirrored from each text field's onChange.
    private volatile String xText = "";
    private volatile String yText = "";
    private volatile String zText = "";
    private volatile String matchText = "Flower";
    private volatile int radius = 4;
    private volatile int lightRadius = 6;
    private volatile boolean autoLights = false;
    private volatile int dropQty = 0;
    /** How long 'Use held item' and 'Swing' hold the button, in ms; 0 holds each charge to its first tier. */
    private volatile int holdMs = 0;

    // Lamps already actuated: position -> the lit block id we forged a Use on. A
    // sweep skips a lamp still reading that same id (its Use hasn't propagated yet),
    // so a lamp is only ever turned off, never toggled back on. The entry clears once
    // the block reads unlit, so a genuine re-light can be turned off again.
    private final Map<BlockPos, Integer> litActuated = new ConcurrentHashMap<>();

    // Two-way bindings for X/Y/Z — modules and the SelectionBus listener push
    // values into the rendered fields through these.
    private final SettingBinding<String> xBinding = new SettingBinding<>();
    private final SettingBinding<String> yBinding = new SettingBinding<>();
    private final SettingBinding<String> zBinding = new SettingBinding<>();

    @Override
    public void onEnable(ModuleContext ctx) {
        Logger log = ctx.getLogger();
        World world = ctx.services().require(World.class);
        InteractionControl interactions = ctx.services().require(InteractionControl.class);
        SelectionBus selectionBus = ctx.services().get(SelectionBus.class).orElse(null);
        EntityTracker entities = ctx.services().get(EntityTracker.class).orElse(null);

        // X/Y/Z are a target the user picks for this session — pointless to
        // restore a random triple from a previous run. The scan radius and
        // name filter are tuning, persisted.
        ctx.registerSettings(SettingsSpec.builder()
                .section("Single block", SettingsSpec.builder()
                        .string("x", "X", "", v -> xText = v == null ? "" : v, xBinding)
                        .string("y", "Y", "", v -> yText = v == null ? "" : v, yBinding)
                        .string("z", "Z", "", v -> zText = v == null ? "" : v, zBinding)
                        .button("Fill from last observed block",
                                () -> fillFromLastObserved(interactions, log))
                        .button("Use on block", () -> runOnTarget(world, interactions, log, "Use on block", Block::use))
                        .button("Hit block", () -> runOnTarget(world, interactions, log, "Hit block", Block::hit))
                        .button("Plant on block", () -> runOnTarget(world, interactions, log, "Plant on block", Block::plant))
                        .button("Water block", () -> runOnTarget(world, interactions, log, "Water block", Block::water))
                        .build())
                // One click on what the crosshair is on, with the server's verdict in the log.
                .section("Forge", SettingsSpec.builder()
                        .button("Hit block in crosshair", () -> onCrosshairBlock(world, log, "Hit", Block::hit))
                        .button("Use block in crosshair", () -> onCrosshairBlock(world, log, "Use", Block::use))
                        .button("Right-click block in crosshair (water, till, fill)",
                                () -> onCrosshairBlock(world, log, "Right-click", Block::water))
                        .button("Place on block in crosshair", () -> onCrosshairBlock(world, log, "Place", Block::place))
                        .button("Dig swing at crosshair", () -> onCrosshairBlock(world, log, "Dig swing",
                                b -> interactions.digSwing(new BlockPos(b.x(), b.y(), b.z()))))
                        .button("Attack entity in crosshair", () -> onCrosshairEntity(entities, interactions, log,
                                "Attack", interactions::hitEntity))
                        .button("Use entity in crosshair", () -> onCrosshairEntity(entities, interactions, log,
                                "Use", interactions::useOnEntity))
                        .int_("holdMs", "Hold for use / swing, ms (0: to the first charge tier)", 0, 5000, 0,
                                v -> holdMs = v)
                        .button("Use held item", () -> inAir(interactions, log, held("Use held item"),
                                () -> holdMs > 0 ? interactions.useItem(Duration.ofMillis(holdMs)) : interactions.useItem()))
                        .button("Swing", () -> inAir(interactions, log, held("Swing"),
                                () -> holdMs > 0 ? interactions.swing(Duration.ofMillis(holdMs)) : interactions.swing()))
                        .build())
                .section("Scan radius", SettingsSpec.builder()
                        .int_("radius", "Radius", 1, 64, 4, v -> radius = v)
                        .string("match", "Name (contains)", "Flower",
                                v -> matchText = v == null ? "" : v)
                        .button("Use nearby", () -> useNearby(world, log))
                        .button("Break nearby", () -> breakNearby(world, interactions, log))
                        .button("Water nearby", () -> waterNearby(world, log))
                        .button("Plant nearby", () -> plantNearby(world, log))
                        .build())
                // ESP-style probe of the new BlockView.state() projection: find every
                // light-emitting block in a cube and forge a Use on each (a lamp toggles
                // off). Its own radius so it can reach ceiling/wall fixtures.
                .section("Lights", SettingsSpec.builder()
                        .int_("lightRadius", "Radius", 1, 64, 6, v -> lightRadius = v)
                        .bool("autoLights", "Auto (every 0.25s)", false, v -> autoLights = v)
                        .button("Turn off lights nearby", () -> toggleLightsNearby(world, log))
                        .build())
                // Drops the held hotbar item via core's forge. quantity 0 is the
                // pre-51 phantom-drop vector (patched in 0.6.0-pre.2).
                .section("Inventory", SettingsSpec.builder()
                        .int_("dropQty", "Drop quantity",
                                -1, 64, 0, v -> dropQty = v)
                        .button("Drop held", () -> dropHeld(world, log))
                        .build())
                .persistent("radius", "match", "lightRadius", "dropQty", "holdMs")
                .build());

        // Cross-module fill — any module (currently ESP's "Nearest blocks"
        // click) can publish a block to SelectionBus and our X/Y/Z snap to it.
        // Also pull the current selection in case it was set before we
        // subscribed.
        if (selectionBus != null) {
            selectionBus.lastBlock().ifPresent(this::applyBlock);
            selectionBus.onBlockSelected(p ->
                    SwingUtilities.invokeLater(() -> applyBlock(p)));
        }

        // Auto-lights ticker: always scheduled, gated on the checkbox flag (the
        // EspModule pattern). The per-connection scheduler is torn down on
        // disconnect, and onDisable clears the flag, so it stops cleanly.
        ctx.scheduler().scheduleAtFixedRate(() -> autoLightsTick(world, log),
                AUTO_LIGHTS_PERIOD, AUTO_LIGHTS_PERIOD);

        log.info("meridian-interaction-examples enabled");
    }

    /**
     * Pulls the last block the player interacted with into the X/Y/Z fields.
     * Reads from {@link InteractionControl#targetedBlock} — that observer is
     * fed by core's interaction-chain observer, so the most recent in-game
     * interaction wins.
     */
    /**
     * Drops the held hotbar item through core's {@link Player#dropHeld} forge.
     * {@code dropQty = 0} reproduces the pre-51 phantom drop for testing a
     * vulnerable dev server (the slot stays, a count-0 item entity spawns).
     */
    private void dropHeld(World world, Logger log) {
        Optional<Player> player = world.player();
        if (player.isEmpty()) {
            log.warn("interaction-test: no player yet — join a world first");
            return;
        }
        Player p = player.get();
        p.dropHeld(dropQty);
        log.info("interaction-test: drop held (slot {}) qty={}", p.activeHotbarSlot(), dropQty);
    }

    private void fillFromLastObserved(InteractionControl interactions, Logger log) {
        Optional<BlockPos> t = interactions.targetedBlock();
        if (t.isEmpty()) {
            log.warn("interaction-test: no observed block yet — interact with one in-game");
            return;
        }
        applyBlock(t.get());
    }

    /** Writes {@code p} into the X/Y/Z bound text fields. EDT-only. */
    private void applyBlock(BlockPos p) {
        xBinding.set(Integer.toString(p.x()));
        yBinding.set(Integer.toString(p.y()));
        zBinding.set(Integer.toString(p.z()));
    }

    /** Parses the X/Y/Z text fields and runs {@code action} on the resolved block. */
    private void runOnTarget(World world, InteractionControl interactions, Logger log,
                             String label, Function<Block, CompletableFuture<InteractionOutcome>> action) {
        if (!interactions.available()) {
            log.warn("interaction-test: no session — join a world first");
            return;
        }
        Optional<BlockPos> pos = parseXyz(log);
        if (pos.isEmpty()) {
            return;
        }
        BlockPos p = pos.get();
        log.info("interaction-test: '{}' at ({},{},{})", label, p.x(), p.y(), p.z());
        report(log, label, action.apply(world.blockAt(p.x(), p.y(), p.z())));
    }

    // ------------------------------------------------------------------
    // Forge: one click on what the crosshair is on, the server's verdict in the log
    // ------------------------------------------------------------------

    /** How far the crosshair reaches for a block or an entity - a little past the server's reach. */
    private static final double CROSSHAIR_RANGE = 8.0;
    /** How far off the look an entity may stand and still count as in the crosshair. */
    private static final double CROSSHAIR_ANGLE_DEG = 12.0;

    /** Acts on the block the crosshair is on. */
    private void onCrosshairBlock(World world, Logger log, String label,
                                  Function<Block, CompletableFuture<InteractionOutcome>> action) {
        Optional<Player> player = world.player();
        if (player.isEmpty()) {
            log.warn("interaction-test: no player yet — join a world first");
            return;
        }
        Optional<BlockPos> pos = player.get().lookedAtBlock(CROSSHAIR_RANGE);
        if (pos.isEmpty()) {
            log.warn("interaction-test: '{}' — no block in the crosshair within {}", label, CROSSHAIR_RANGE);
            return;
        }
        BlockPos p = pos.get();
        Block block = world.blockAt(p.x(), p.y(), p.z());
        String what = String.format("%s on %s at (%d,%d,%d) with %s", label,
                block.type() == null ? "air" : block.type().name(), p.x(), p.y(), p.z(), player.get().heldItem());
        report(log, what, action.apply(block));
    }

    /** Acts on the entity the crosshair is on - or, with none there, the nearest one. */
    private void onCrosshairEntity(EntityTracker entities, InteractionControl interactions, Logger log,
                                   String label, Function<Integer, CompletableFuture<InteractionOutcome>> action) {
        if (entities == null || !interactions.available()) {
            log.warn("interaction-test: no session — join a world first");
            return;
        }
        OptionalInt id = entities.entityInCrosshair(CROSSHAIR_ANGLE_DEG, CROSSHAIR_RANGE);
        String how = "in the crosshair";
        if (id.isEmpty()) {
            id = entities.nearestEntity();
            how = "nearest";
        }
        if (id.isEmpty()) {
            log.warn("interaction-test: '{}' — no entity around", label);
            return;
        }
        int target = id.getAsInt();
        String what = String.format("%s on %s #%d (%s)", label, entities.labelOf(target), target, how);
        report(log, what, action.apply(target));
    }

    /** An action's name with its hold, when there is one. */
    private String held(String label) {
        return holdMs > 0 ? label + " (held " + holdMs + " ms)" : label;
    }

    /** Acts with the held item at nothing in particular. */
    private void inAir(InteractionControl interactions, Logger log, String label,
                       Supplier<CompletableFuture<InteractionOutcome>> action) {
        if (!interactions.available()) {
            log.warn("interaction-test: no session — join a world first");
            return;
        }
        report(log, label, action.get());
    }

    /**
     * Logs how the server ran the chain once it says so: its status, the operations that failed
     * (a chain often ends FINISHED down its failed branch), its forks, and how often the server
     * corrected core's guess.
     */
    private static void report(Logger log, String what, CompletableFuture<InteractionOutcome> chain) {
        chain.whenComplete((o, error) -> {
            if (error != null) {
                log.warn("interaction-test: {} — {}", what, error.toString());
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(o.status()).append(", ").append(o.steps().size()).append(" op(s)");
            if (!o.failedSteps().isEmpty()) {
                sb.append(", failed ").append(o.failedSteps().stream()
                        .map(s -> s.index() + " " + s.operation()).toList());
            }
            if (!o.forks().isEmpty()) {
                sb.append(", forks ").append(o.forks());
            }
            if (o.corrections() > 0) {
                sb.append(", ").append(o.corrections()).append(" correction(s) by the server");
            }
            log.info("interaction-test: {} -> {}", what, sb);
        });
    }

    private Optional<BlockPos> parseXyz(Logger log) {
        try {
            return Optional.of(new BlockPos(
                    Integer.parseInt(xText.trim()),
                    Integer.parseInt(yText.trim()),
                    Integer.parseInt(zText.trim())));
        } catch (NumberFormatException ex) {
            log.warn("interaction-test: enter integer X / Y / Z coordinates first");
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // 'Nearby' scan actions — module logic, core only hands out World / Block / Player.
    // ------------------------------------------------------------------

    /**
     * Module-side logic: switch to the seed item, plant on every empty tilled-soil
     * block in range, switch back. As with watering, the module just issues the
     * calls — core's forge queue serializes the chains.
     */
    private void plantNearby(World world, Logger log) {
        Optional<Player> maybePlayer = world.player();
        if (maybePlayer.isEmpty()) {
            log.warn("interaction-test: no session — join a world first");
            return;
        }
        Player player = maybePlayer.get();
        int seedSlot = player.hotbarSlotOf("Seed");
        if (seedSlot < 0) {
            log.warn("interaction-test: no seed item in the hotbar");
            return;
        }
        Vec3 pos = player.position();
        if (pos == null) {
            log.warn("interaction-test: player position unknown");
            return;
        }
        int r = radius;
        int px = (int) Math.floor(pos.x());
        int py = (int) Math.floor(pos.y());
        int pz = (int) Math.floor(pos.z());

        // Tilled soil with air directly above — room for the crop block.
        List<Block> soil = new ArrayList<>();
        for (Block b : inBox(world, px, py, pz, r, -2, 1, InteractionTestModule::tilled)) {
            if (world.blockAt(b.x(), b.y() + 1, b.z()).isAir()) {
                soil.add(b);
            }
        }
        if (soil.isEmpty()) {
            log.info("interaction-test: 'Plant nearby' — no empty tilled soil within {}", r);
            return;
        }

        int original = player.activeHotbarSlot();
        log.info("interaction-test: 'Plant nearby' radius {} — {} soil block(s)", r, soil.size());
        player.selectHotbarSlot(seedSlot);
        for (Block b : soil) {
            b.plant();
        }
        player.selectHotbarSlot(original)
                .thenRun(() -> log.info("interaction-test: 'Plant nearby' done — {} block(s)",
                        soil.size()));
    }

    /**
     * Module-side logic: switch to the watering can, water every tilled-soil
     * block in range, switch back. The module just issues the calls in order —
     * core's forge queue runs each chain to completion before the next, so no
     * hand-spacing is needed even though a watering charge spans many ticks.
     */
    private void waterNearby(World world, Logger log) {
        Optional<Player> maybePlayer = world.player();
        if (maybePlayer.isEmpty()) {
            log.warn("interaction-test: no session — join a world first");
            return;
        }
        Player player = maybePlayer.get();
        int canSlot = player.hotbarSlotOf("Watering_Can");
        if (canSlot < 0) {
            log.warn("interaction-test: no watering can in the hotbar");
            return;
        }
        Vec3 pos = player.position();
        if (pos == null) {
            log.warn("interaction-test: player position unknown");
            return;
        }
        int r = radius;
        int px = (int) Math.floor(pos.x());
        int py = (int) Math.floor(pos.y());
        int pz = (int) Math.floor(pos.z());

        List<Block> soil = inBox(world, px, py, pz, r, -2, 1, InteractionTestModule::tilled);
        if (soil.isEmpty()) {
            log.info("interaction-test: 'Water nearby' — no tilled soil within {}", r);
            return;
        }

        int original = player.activeHotbarSlot();
        log.info("interaction-test: 'Water nearby' radius {} — {} soil block(s)", r, soil.size());
        // Each call is async — it returns a future and core's queue runs the
        // chains in order. We only need the future of the last one: it completes
        // after everything ahead of it on the queue, so .thenRun fires when the
        // whole batch is done.
        player.selectHotbarSlot(canSlot);
        for (Block b : soil) {
            b.water();
        }
        player.selectHotbarSlot(original)
                .thenRun(() -> log.info("interaction-test: 'Water nearby' done — {} block(s)",
                        soil.size()));
    }

    /** Module-side logic: scan a radius around the player, use blocks by name. */
    private void useNearby(World world, Logger log) {
        Optional<Player> maybePlayer = world.player();
        if (maybePlayer.isEmpty()) {
            log.warn("interaction-test: no session — join a world first");
            return;
        }
        String name = matchText.trim();
        Vec3 pos = maybePlayer.get().position();
        if (pos == null) {
            log.warn("interaction-test: player position unknown");
            return;
        }
        int r = radius;
        int px = (int) Math.floor(pos.x());
        int py = (int) Math.floor(pos.y());
        int pz = (int) Math.floor(pos.z());

        int used = 0;
        for (Block block : inBox(world, px, py, pz, r, -2, 2, t -> named(t, name))) {
            block.use();
            used++;
        }
        log.info("interaction-test: 'Use nearby' radius {} name '{}' — used {} block(s)",
                r, name, used);
    }

    /**
     * Manual one-shot: sweep the cube once and report. Verbose — logs each lit
     * block's {@link meridian.core.api.BlockView#state()} so the new projection is
     * visible — and warns when there's no session/position.
     */
    private void toggleLightsNearby(World world, Logger log) {
        if (world.player().isEmpty()) {
            log.warn("interaction-test: no session — join a world first");
            return;
        }
        int used = sweepLights(world, log, true);
        log.info("interaction-test: 'Turn off lights nearby' radius {} — used {} lit block(s)",
                lightRadius, used);
    }

    /**
     * Auto-lights ticker body (every {@link #AUTO_LIGHTS_PERIOD}). A no-op unless the
     * checkbox is on; quiet otherwise — only logs when it actually actuates a lamp, so
     * an idle ticker doesn't flood the console. Set false by {@link #onDisable} so the
     * sweep stops the moment the connection tears down.
     */
    private void autoLightsTick(World world, Logger log) {
        if (!autoLights) {
            return;
        }
        int used = sweepLights(world, log, false);
        if (used > 0) {
            log.info("interaction-test: auto-lights — issued {} use(s) within {}", used, lightRadius);
        }
    }

    /**
     * The shared ESP-style sweep behind the button and the ticker: scan a cube around
     * the player, collect every light-emitting block ({@link meridian.core.api.BlockView#isLit()}),
     * and forge a {@code Use} on each — a toggleable lamp flips off.
     *
     * <p><b>Off, not toggle.</b> {@code Use} toggles a lamp, and the forge resolves
     * before the resulting block change reaches the chunk tracker — so a naive sweep
     * sees the lamp still lit and fires again, flipping it back on (flicker). The
     * {@link #litActuated} guard keys on the <em>observed</em> block id: once a lit
     * lamp is actuated, every later sweep that still reads that same id skips it. The
     * claim is let go when the server's change lands ({@link World#awaitBlock}), or when
     * a sweep reads the lamp unlit or unloaded. A lamp is therefore turned off exactly
     * once and never toggled back on; a genuine re-light (the block goes unlit then lit
     * again) is picked up afresh. A lamp over two cells is one block, used once.
     *
     * <p>A lit block with no Use interaction (glowstone, lava) just no-ops the forge —
     * still actuated once, then left alone. Returns how many uses were issued;
     * {@code verbose} logs each lit block's {@link meridian.core.api.BlockView#state()}.
     */
    private int sweepLights(World world, Logger log, boolean verbose) {
        Optional<Player> maybePlayer = world.player();
        if (maybePlayer.isEmpty()) {
            return 0;
        }
        Vec3 pos = maybePlayer.get().position();
        if (pos == null) {
            if (verbose) {
                log.warn("interaction-test: player position unknown");
            }
            return 0;
        }
        int r = lightRadius;
        int px = (int) Math.floor(pos.x());
        int py = (int) Math.floor(pos.y());
        int pz = (int) Math.floor(pos.z());

        // Off / air / unloaded in the box — release the claim.
        litActuated.keySet().removeIf(at -> Math.abs(at.x() - px) <= r && Math.abs(at.y() - py) <= r
                && Math.abs(at.z() - pz) <= r && !litWith(world.blockAt(at.x(), at.y(), at.z()), -1));
        int used = 0;
        for (Block block : inBox(world, px, py, pz, r, -r, r, BlockView::isLit)) {
            BlockPos at = new BlockPos(block.x(), block.y(), block.z());
            int id = block.type().id();
            Integer prev = litActuated.get(at);
            if (prev != null && prev == id) {
                continue; // already used this exact lit block; its change hasn't landed yet
            }
            litActuated.put(at, id);
            if (verbose) {
                log.info("interaction-test: light at ({},{},{}) '{}' state '{}'",
                        at.x(), at.y(), at.z(), block.type().name(), block.type().state());
            }
            block.use();
            // The server's change lets the claim go; one that never comes (glowstone has no Use)
            // leaves it to the sweeps.
            world.awaitBlock(at, b -> !litWith(b, id), LIGHT_CHANGE_WAIT)
                    .thenAccept(changed -> changed.ifPresent(b -> litActuated.remove(at, id)));
            used++;
        }
        return used;
    }

    @Override
    public void onDisable() {
        // Stop the auto-sweep before the connection's World/forge are torn down.
        autoLights = false;
        litActuated.clear();
    }

    /**
     * Module-side logic: scan a cube around the player and break every non-air
     * block in range. The Name field filters by block-type name (empty matches
     * all). One hit may not break a block, so core swings at each target until the
     * world shows it gone ({@link InteractionControl#breakBlock}), at most
     * {@link #MAX_HITS_PER_BLOCK} times.
     */
    private void breakNearby(World world, InteractionControl interactions, Logger log) {
        Optional<Player> maybePlayer = world.player();
        if (maybePlayer.isEmpty()) {
            log.warn("interaction-test: no session — join a world first");
            return;
        }
        String name = matchText.trim();
        Vec3 pos = maybePlayer.get().position();
        if (pos == null) {
            log.warn("interaction-test: player position unknown");
            return;
        }
        int r = radius;
        int px = (int) Math.floor(pos.x());
        int py = (int) Math.floor(pos.y());
        int pz = (int) Math.floor(pos.z());

        List<BlockPos> targets = new ArrayList<>();
        for (Block block : inBox(world, px, py, pz, r, -r, r, t -> name.isEmpty() ? t.name() != null : named(t, name))) {
            targets.add(new BlockPos(block.x(), block.y(), block.z()));
        }
        log.info("interaction-test: 'Break nearby' radius {} name '{}' — {} target(s), "
                + "hitting until clear", r, name, targets.size());
        for (BlockPos target : targets) {
            interactions.breakBlock(target, MAX_HITS_PER_BLOCK).thenAccept(broken -> {
                if (!broken) {
                    log.warn("interaction-test: ({},{},{}) still not broken after {} hits — giving up",
                            target.x(), target.y(), target.z(), MAX_HITS_PER_BLOCK);
                }
            });
        }
    }

    /**
     * The blocks of a box about the player's cell - x and z within {@code r} of it, y from
     * {@code lo} to {@code hi} over it - whose type {@code type} accepts: core's search over the
     * sphere round the box, the box cut out of it. A block over more than one cell (a door, a bed)
     * is found once, at the cell it stands on; air and cells core has not been sent never are.
     */
    private static List<Block> inBox(World world, int px, int py, int pz, int r, int lo, int hi,
                                     Predicate<BlockView> type) {
        double half = (hi - lo) / 2.0;
        double reach = Math.sqrt(2.0 * r * r + half * half) + 1e-6;
        Vec3 middle = new Vec3(px + 0.5, py + 0.5 + (lo + hi) / 2.0, pz + 0.5);
        List<Block> out = new ArrayList<>();
        for (Block b : world.find(middle, reach, type, Integer.MAX_VALUE)) {
            int dy = b.y() - py;
            if (Math.abs(b.x() - px) <= r && Math.abs(b.z() - pz) <= r && dy >= lo && dy <= hi) {
                out.add(b);
            }
        }
        return out;
    }

    /** A block type whose name contains {@code part} (case counts). */
    private static boolean named(BlockView t, String part) {
        return t.name() != null && t.name().contains(part);
    }

    private static boolean tilled(BlockView t) {
        return named(t, "Tilled");
    }

    /** Whether a block is lit - and, for {@code id} 0 or more, still that very block. */
    private static boolean litWith(Block b, int id) {
        return b.type() != null && b.type().isLit() && (id < 0 || b.type().id() == id);
    }
}
