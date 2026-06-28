package meridian.interactiontest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.settings.SettingBinding;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.Block;
import meridian.core.api.BlockPos;
import meridian.core.api.InteractionControl;
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

    /** Cap on re-hits per block in 'Break nearby' — stops an unbreakable block looping. */
    private static final int MAX_HITS_PER_BLOCK = 12;

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
                .section("Scan radius", SettingsSpec.builder()
                        .int_("radius", "Radius", 1, 64, 4, v -> radius = v)
                        .string("match", "Name (contains)", "Flower",
                                v -> matchText = v == null ? "" : v)
                        .button("Use nearby", () -> useNearby(world, log))
                        .button("Break nearby", () -> breakNearby(world, log))
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
                .persistent("radius", "match", "lightRadius", "dropQty")
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
                             String label, Consumer<Block> action) {
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
        action.accept(world.blockAt(p.x(), p.y(), p.z()));
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
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    Block b = world.blockAt(px + dx, py + dy, pz + dz);
                    if (b.type() != null && b.type().name() != null
                            && b.type().name().contains("Tilled")
                            && world.blockAt(b.x(), b.y() + 1, b.z()).isAir()) {
                        soil.add(b);
                    }
                }
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

        List<Block> soil = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    Block b = world.blockAt(px + dx, py + dy, pz + dz);
                    if (b.type() != null && b.type().name() != null
                            && b.type().name().contains("Tilled")) {
                        soil.add(b);
                    }
                }
            }
        }
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
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    Block block = world.blockAt(px + dx, py + dy, pz + dz);
                    if (block.type() != null && block.type().name() != null
                            && block.type().name().contains(name)) {
                        block.use();
                        used++;
                    }
                }
            }
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
     * lamp is actuated, every later sweep that still reads that same id skips it, and
     * only when the block reads unlit (its id changed) is the claim cleared. A lamp is
     * therefore turned off exactly once and never toggled back on; a genuine re-light
     * (the block goes unlit then lit again) is picked up afresh.
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

        int used = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block block = world.blockAt(px + dx, py + dy, pz + dz);
                    BlockPos at = new BlockPos(px + dx, py + dy, pz + dz);
                    if (block.type() == null || !block.type().isLit()) {
                        litActuated.remove(at); // off / air / unloaded — release the claim
                        continue;
                    }
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
                    used++;
                }
            }
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
     * all). One hit may not break a block, so each target is re-hit until the
     * world shows it as air ({@link #hitUntilGone}).
     */
    private void breakNearby(World world, Logger log) {
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
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -r; dy <= r; dy++) {
                    Block block = world.blockAt(px + dx, py + dy, pz + dz);
                    if (block.isAir() || block.type() == null || block.type().name() == null) {
                        continue;
                    }
                    if (name.isEmpty() || block.type().name().contains(name)) {
                        targets.add(new BlockPos(px + dx, py + dy, pz + dz));
                    }
                }
            }
        }
        log.info("interaction-test: 'Break nearby' radius {} name '{}' — {} target(s), "
                + "hitting until clear", r, name, targets.size());
        for (BlockPos target : targets) {
            hitUntilGone(world, log, target, 0);
        }
    }

    /**
     * Reliability: a single hit may not break a block (a weak tool / hard block
     * needs several). Re-hits {@code pos} until the world reports it as air,
     * capped by {@link #MAX_HITS_PER_BLOCK} so an unbreakable block can't loop
     * forever. Each follow-up hit is chained off the previous forge's future.
     */
    private static void hitUntilGone(World world, Logger log, BlockPos pos, int attempt) {
        Block block = world.blockAt(pos.x(), pos.y(), pos.z());
        if (block.isAir()) {
            return; // broken
        }
        if (attempt >= MAX_HITS_PER_BLOCK) {
            log.warn("interaction-test: ({},{},{}) still not broken after {} hits — giving up",
                    pos.x(), pos.y(), pos.z(), MAX_HITS_PER_BLOCK);
            return;
        }
        block.hit().thenRun(() -> hitUntilGone(world, log, pos, attempt + 1));
    }
}
