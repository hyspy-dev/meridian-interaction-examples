package meridian.interactiontest;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.core.api.Block;
import meridian.core.api.BlockPos;
import meridian.core.api.InteractionControl;
import meridian.core.api.Player;
import meridian.core.api.Vec3;
import meridian.core.api.World;
import org.slf4j.Logger;

/**
 * meridian-interaction-test — a Layer-2 test module.
 *
 * <p>A demonstration of meridian-core's building blocks: it queries the
 * {@link World} and acts on {@link Block}s, writing its own scan / decision
 * logic. Core supplies the objects and removes the packet plumbing — the
 * behaviour ("scan a radius, use matching blocks") lives here, in the module.
 */
public class InteractionTestModule implements ProxyModule {

    private static final Color BG = new Color(20, 20, 20);
    private static final Color FG = Color.WHITE;
    private static final Color FIELD_BG = new Color(35, 35, 35);
    private static final Color BUTTON_BG = new Color(55, 55, 60);

    /** Cap on re-hits per block in 'Break nearby' — stops an unbreakable block looping. */
    private static final int MAX_HITS_PER_BLOCK = 12;

    @Override
    public void onEnable(ModuleContext ctx) {
        Logger log = ctx.getLogger();
        World world = ctx.services().require(World.class);
        InteractionControl interactions = ctx.services().require(InteractionControl.class);
        ctx.registerSettings(buildPanel(world, interactions, log));
        log.info("meridian-interaction-test enabled");
    }

    @SuppressWarnings("deprecation") // a raw panel is the right tool for a test harness
    private JPanel buildPanel(World world, InteractionControl interactions, Logger log) {
        JPanel panel = column(BG);

        // --- single block: X / Y / Z ----------------------------------------
        JTextField x = field();
        JTextField y = field();
        JTextField z = field();
        JPanel coords = row();
        coords.add(label("X")); coords.add(x);
        coords.add(label("Y")); coords.add(y);
        coords.add(label("Z")); coords.add(z);
        panel.add(coords);

        JButton fill = button("Fill from last observed block");
        fill.addActionListener(e -> {
            Optional<BlockPos> t = interactions.targetedBlock();
            if (t.isEmpty()) {
                log.warn("interaction-test: no observed block yet — interact with one in-game");
                return;
            }
            BlockPos p = t.get();
            x.setText(Integer.toString(p.x()));
            y.setText(Integer.toString(p.y()));
            z.setText(Integer.toString(p.z()));
        });
        panel.add(fill);
        panel.add(Box.createVerticalStrut(6));

        panel.add(blockAction("Use on block", world, interactions, log, x, y, z, Block::use));
        panel.add(Box.createVerticalStrut(4));
        panel.add(blockAction("Hit block", world, interactions, log, x, y, z, Block::hit));
        panel.add(Box.createVerticalStrut(4));
        panel.add(blockAction("Plant on block", world, interactions, log, x, y, z, Block::plant));
        panel.add(Box.createVerticalStrut(4));
        panel.add(blockAction("Water block", world, interactions, log, x, y, z, Block::water));
        panel.add(Box.createVerticalStrut(10));

        // --- scan a radius and use matching blocks --------------------------
        // This is module logic — core only hands out World / Block / Player.
        JTextField radius = field();
        radius.setText("4");
        JTextField match = field();
        match.setColumns(12);
        match.setText("Flower");
        JPanel scanRow = row();
        scanRow.add(label("Radius")); scanRow.add(radius);
        scanRow.add(label("Name")); scanRow.add(match);
        panel.add(scanRow);

        JButton scan = button("Use nearby (scan radius)");
        scan.addActionListener(e -> useNearby(world, log, radius, match));
        panel.add(scan);
        panel.add(Box.createVerticalStrut(4));

        JButton breakScan = button("Break nearby (scan radius)");
        breakScan.addActionListener(e -> breakNearby(world, log, radius, match));
        panel.add(breakScan);
        panel.add(Box.createVerticalStrut(4));

        JButton water = button("Water nearby (scan radius)");
        water.addActionListener(e -> waterNearby(world, log, radius));
        panel.add(water);
        panel.add(Box.createVerticalStrut(4));

        JButton plant = button("Plant nearby (scan radius)");
        plant.addActionListener(e -> plantNearby(world, log, radius));
        panel.add(plant);
        return panel;
    }

    /**
     * Module-side logic: switch to the seed item, plant on every empty tilled-soil
     * block in range, switch back. As with watering, the module just issues the
     * calls — core's forge queue serializes the chains.
     */
    private static void plantNearby(World world, Logger log, JTextField radiusField) {
        Optional<Player> maybePlayer = world.player();
        if (maybePlayer.isEmpty()) {
            log.warn("interaction-test: no session — join a world first");
            return;
        }
        Player player = maybePlayer.get();
        int radius;
        try {
            radius = Integer.parseInt(radiusField.getText().trim());
        } catch (NumberFormatException ex) {
            log.warn("interaction-test: enter an integer radius first");
            return;
        }
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
        int px = (int) Math.floor(pos.x());
        int py = (int) Math.floor(pos.y());
        int pz = (int) Math.floor(pos.z());

        // Tilled soil with air directly above — room for the crop block.
        List<Block> soil = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
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
            log.info("interaction-test: 'Plant nearby' — no empty tilled soil within {}", radius);
            return;
        }

        int original = player.activeHotbarSlot();
        log.info("interaction-test: 'Plant nearby' radius {} — {} soil block(s)",
                radius, soil.size());
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
    private static void waterNearby(World world, Logger log, JTextField radiusField) {
        Optional<Player> maybePlayer = world.player();
        if (maybePlayer.isEmpty()) {
            log.warn("interaction-test: no session — join a world first");
            return;
        }
        Player player = maybePlayer.get();
        int radius;
        try {
            radius = Integer.parseInt(radiusField.getText().trim());
        } catch (NumberFormatException ex) {
            log.warn("interaction-test: enter an integer radius first");
            return;
        }
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
        int px = (int) Math.floor(pos.x());
        int py = (int) Math.floor(pos.y());
        int pz = (int) Math.floor(pos.z());

        List<Block> soil = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
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
            log.info("interaction-test: 'Water nearby' — no tilled soil within {}", radius);
            return;
        }

        int original = player.activeHotbarSlot();
        log.info("interaction-test: 'Water nearby' radius {} — {} soil block(s)",
                radius, soil.size());
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
    private static void useNearby(World world, Logger log, JTextField radiusField,
                                  JTextField matchField) {
        Optional<Player> maybePlayer = world.player();
        if (maybePlayer.isEmpty()) {
            log.warn("interaction-test: no session — join a world first");
            return;
        }
        int radius;
        try {
            radius = Integer.parseInt(radiusField.getText().trim());
        } catch (NumberFormatException ex) {
            log.warn("interaction-test: enter an integer radius first");
            return;
        }
        String name = matchField.getText().trim();
        Vec3 pos = maybePlayer.get().position();
        if (pos == null) {
            log.warn("interaction-test: player position unknown");
            return;
        }
        int px = (int) Math.floor(pos.x());
        int py = (int) Math.floor(pos.y());
        int pz = (int) Math.floor(pos.z());

        int used = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
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
                radius, name, used);
    }

    /**
     * Module-side logic: scan a cube around the player and break every non-air
     * block in range. The Name field filters by block-type name (empty matches
     * all). One hit may not break a block, so each target is re-hit until the
     * world shows it as air ({@link #hitUntilGone}).
     */
    private static void breakNearby(World world, Logger log, JTextField radiusField,
                                    JTextField matchField) {
        Optional<Player> maybePlayer = world.player();
        if (maybePlayer.isEmpty()) {
            log.warn("interaction-test: no session — join a world first");
            return;
        }
        int radius;
        try {
            radius = Integer.parseInt(radiusField.getText().trim());
        } catch (NumberFormatException ex) {
            log.warn("interaction-test: enter an integer radius first");
            return;
        }
        String name = matchField.getText().trim();
        Vec3 pos = maybePlayer.get().position();
        if (pos == null) {
            log.warn("interaction-test: player position unknown");
            return;
        }
        int px = (int) Math.floor(pos.x());
        int py = (int) Math.floor(pos.y());
        int pz = (int) Math.floor(pos.z());

        List<BlockPos> targets = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -radius; dy <= radius; dy++) {
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
                + "hitting until clear", radius, name, targets.size());
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

    /** A button that resolves the X/Y/Z block from the world and runs {@code action}. */
    private JButton blockAction(String label, World world, InteractionControl interactions,
                                Logger log, JTextField x, JTextField y, JTextField z,
                                Consumer<Block> action) {
        JButton b = button(label);
        b.addActionListener(e -> {
            if (!interactions.available()) {
                log.warn("interaction-test: no session — join a world first");
                return;
            }
            Optional<BlockPos> pos = parse(x, y, z, log);
            if (pos.isEmpty()) {
                return;
            }
            BlockPos p = pos.get();
            log.info("interaction-test: '{}' at ({},{},{})", label, p.x(), p.y(), p.z());
            action.accept(world.blockAt(p.x(), p.y(), p.z()));
        });
        return b;
    }

    private static Optional<BlockPos> parse(JTextField x, JTextField y, JTextField z, Logger log) {
        try {
            return Optional.of(new BlockPos(
                    Integer.parseInt(x.getText().trim()),
                    Integer.parseInt(y.getText().trim()),
                    Integer.parseInt(z.getText().trim())));
        } catch (NumberFormatException ex) {
            log.warn("interaction-test: enter integer X / Y / Z coordinates first");
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // Swing helpers
    // ------------------------------------------------------------------

    private static JPanel column(Color bg) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(bg);
        return p;
    }

    private static JPanel row() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        p.setBackground(BG);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(FG);
        return l;
    }

    private static JTextField field() {
        JTextField f = new JTextField(4);
        f.setBackground(FIELD_BG);
        f.setForeground(FG);
        f.setCaretColor(FG);
        return f;
    }

    private static JButton button(String label) {
        JButton b = new JButton(label);
        // Swing look-and-feels ignore setBackground on the default button paint;
        // disabling the LAF content fill and painting our own keeps the dark
        // background (and the white text legible) on every platform.
        b.setContentAreaFilled(false);
        b.setOpaque(true);
        b.setBackground(BUTTON_BG);
        b.setForeground(FG);
        b.setFocusPainted(false);
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        b.setBorder(BorderFactory.createLineBorder(new Color(90, 90, 95)));
        return b;
    }
}
