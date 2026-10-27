package caleon.modules;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Human-like schematic builder (vanilla structure .nbt files).
 *
 * Instead of sending placement packets directly, it:
 *  1. "thinks" for a few ticks before each block,
 *  2. smoothly turns the camera toward a face of a neighbouring block (eased, jittery, mouse-sensitivity snapped),
 *  3. waits until the crosshair is REALLY on that face,
 *  4. pauses a random moment, then right-clicks (swing + useItemOn) like a player would.
 *
 * Commands:
 *   /printer load <name>   - loads .minecraft/schematics/<name>.nbt
 *   /printer origin        - sets the build origin to the block you're standing on (+1 up)
 *   /printer toggle        - start / stop
 *   /printer speed <1-10>  - 1 = slow & very human, 10 = fast
 *
 * Plug into Caleon Client instead: call SchematicBuilder.tick(mc) from your module's onTick.
 */
public class SchematicBuilder implements ClientModInitializer {

    record Entry(BlockPos rel, BlockState state) {}

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Map<BlockPos, Long> RECENT = new HashMap<>();   // placed / failed recently
    private static final RandomSource RNG = RandomSource.create();
    private static final double REACH = 4.4;

    private static BlockPos origin = BlockPos.ZERO;
    private static boolean enabled = false;
    private static int speed = 4;

    // current target
    private static Entry target;
    private static BlockPos targetWorld, clickBlock;
    private static Direction clickFace;
    private static Vec3 aimPoint;
    private static int delay, settle, stuck;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(SchematicBuilder::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
            ClientCommands.literal("printer")
                .then(ClientCommands.literal("load")
                    .then(ClientCommands.argument("name", StringArgumentType.word()).executes(c -> {
                        String name = StringArgumentType.getString(c, "name");
                        try {
                            int n = load(FabricLoader.getInstance().getGameDir().resolve("schematics").resolve(name + ".nbt"));
                            c.getSource().sendFeedback(Component.literal("Loaded " + n + " blocks"));
                        } catch (Exception e) {
                            c.getSource().sendFeedback(Component.literal("Failed: " + e.getMessage()));
                        }
                        return 1;
                    })))
                .then(ClientCommands.literal("origin").executes(c -> {
                    LocalPlayer p = Minecraft.getInstance().player;
                    if (p != null) origin = p.blockPosition();
                    c.getSource().sendFeedback(Component.literal("Origin set to " + origin.toShortString()));
                    return 1;
                }))
                .then(ClientCommands.literal("toggle").executes(c -> {
                    enabled = !enabled;
                    target = null;
                    c.getSource().sendFeedback(Component.literal("Printer " + (enabled ? "ON" : "OFF")));
                    return 1;
                }))
                .then(ClientCommands.literal("speed")
                    .then(ClientCommands.argument("n", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 10))
                        .executes(c -> {
                            speed = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "n");
                            c.getSource().sendFeedback(Component.literal("Speed " + speed));
                            return 1;
                        })))
        ));
    }

    // ---------------------------------------------------------------- loading

    public static int load(Path file) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) throw new IllegalStateException("join a world first");
        if (!Files.exists(file)) throw new IllegalStateException("not found: " + file);

        var blocks = mc.level.registryAccess().lookupOrThrow(Registries.BLOCK);
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());

        ListTag paletteTag = root.getListOrEmpty("palette");
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < paletteTag.size(); i++)
            palette.add(NbtUtils.readBlockState(blocks, paletteTag.getCompoundOrEmpty(i)));

        ENTRIES.clear();
        RECENT.clear();
        ListTag list = root.getListOrEmpty("blocks");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag b = list.getCompoundOrEmpty(i);
            ListTag p = b.getListOrEmpty("pos");
            BlockState s = palette.get(b.getIntOr("state", 0));
            if (s.isAir() || s.getBlock().asItem() == null) continue;
            ENTRIES.add(new Entry(new BlockPos(p.getIntOr(0, 0), p.getIntOr(1, 0), p.getIntOr(2, 0)), s));
        }
        // bottom-up, then closest to origin, so it builds like a person would
        ENTRIES.sort(Comparator.<Entry>comparingInt(e -> e.rel().getY())
            .thenComparingInt(e -> e.rel().getX() + e.rel().getZ()));
        return ENTRIES.size();
    }

    // ---------------------------------------------------------------- main loop

    public static void tick(Minecraft mc) {
        if (!enabled || ENTRIES.isEmpty() || mc.player == null || mc.level == null
            || mc.gameMode == null || screenOpen(mc)) return;

        if (delay > 0) { delay--; return; }

        Level level = mc.level;
        LocalPlayer p = mc.player;
        long now = level.getGameTime();
        RECENT.values().removeIf(t -> t < now);

        // 1. choose a target
        if (target == null) {
            if (!pickTarget(mc, now)) { delay = 10; return; }
            delay = thinkTicks();            // "looking at the blueprint"
            stuck = 0;
            settle = 0;
            return;
        }

        // target already built / blocked meanwhile?
        BlockState cur = level.getBlockState(targetWorld);
        if (cur.is(target.state().getBlock()) || !cur.canBeReplaced()) { target = null; return; }

        // 2. make sure the right item is in hand
        if (!holdItem(p, target.state())) { RECENT.put(targetWorld, now + 100); target = null; return; }

        // 3. turn the camera like a hand on a mouse
        Vec3 eye = p.getEyePosition();
        if (eye.distanceTo(aimPoint) > REACH) { RECENT.put(targetWorld, now + 60); target = null; return; }
        double dx = aimPoint.x - eye.x, dy = aimPoint.y - eye.y, dz = aimPoint.z - eye.z;
        float tYaw = (float) (Mth.atan2(dz, dx) * 57.29577951308232) - 90f;
        float tPitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 57.29577951308232);
        float dYaw = Mth.wrapDegrees(tYaw - p.getYRot());
        float dPitch = Mth.clamp(tPitch, -90f, 90f) - p.getXRot();
        float dist = Mth.sqrt(dYaw * dYaw + dPitch * dPitch);

        if (dist > 0.4f) {
            float maxStep = 4f + speed * 2.2f;                                    // deg / tick cap
            float step = Math.min(dist, Math.min(maxStep, Math.max(0.8f, dist * (0.12f + speed * 0.02f))));
            step *= 0.8f + RNG.nextFloat() * 0.4f;                                // uneven hand speed
            float r = Math.min(1f, step / dist);
            float ny = dYaw * r + (RNG.nextFloat() - 0.5f) * 0.25f;               // tiny tremor
            float np = dPitch * r + (RNG.nextFloat() - 0.5f) * 0.25f;
            // snap to what a real mouse at this sensitivity can produce
            double s = mc.options.sensitivity().get() * 0.6 + 0.2;
            float gcd = (float) (s * s * s * 8.0 * 0.15);
            ny = Math.round(ny / gcd) * gcd;
            np = Math.round(np / gcd) * gcd;
            p.setYRot(p.getYRot() + ny);
            p.setXRot(Mth.clamp(p.getXRot() + np, -90f, 90f));
        }

        // 4. give up if we can't get the crosshair there
        if (++stuck > 100) { RECENT.put(targetWorld, now + 200); target = null; return; }

        // 5. only click when the crosshair is genuinely on the right face
        if (mc.hitResult instanceof BlockHitResult hit
            && hit.getBlockPos().equals(clickBlock) && hit.getDirection() == clickFace) {
            if (settle == 0) settle = 2 + RNG.nextInt(5 + (10 - speed));          // short pause before clicking
            if (--settle > 0) return;

            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
            swingHand(p);
            RECENT.put(targetWorld, now + 20);
            target = null;
            delay = 2 + RNG.nextInt(2 + (11 - speed));                            // click cadence
            if (RNG.nextInt(14) == 0) delay += 6 + RNG.nextInt(12);               // occasional "look away"
        } else {
            settle = 0;
        }
    }


    // ---------------------------------------------------------------- 26.3 compat helpers
    // (names/signatures moved around in 26.3, so these look things up at runtime)

    /** True when any GUI screen (chat, inventory, pause...) is open. */
    private static boolean screenOpen(Minecraft mc) {
        try {
            for (String n : new String[]{"getScreen", "screen"}) {
                try {
                    Method m = Minecraft.class.getMethod(n);
                    if (m.getParameterCount() == 0) return m.invoke(mc) != null;
                } catch (NoSuchMethodException ignored) {}
            }
            for (String n : new String[]{"screen", "currentScreen"}) {
                try {
                    Field f = Minecraft.class.getDeclaredField(n);
                    f.setAccessible(true);
                    return f.get(mc) != null;
                } catch (NoSuchFieldException ignored) {}
            }
            // fallback: cursor is only released while a screen is open
            Object mh = Minecraft.class.getField("mouseHandler").get(mc);
            return !(boolean) mh.getClass().getMethod("isMouseGrabbed").invoke(mh);
        } catch (Throwable t) {
            return false;
        }
    }

    /** swing(InteractionHand) became swing(hand, SwingAnimation, boolean) in 26.3. */
    private static void swingHand(LocalPlayer p) {
        try {
            for (Method m : LivingEntity.class.getMethods()) {
                if (!m.getName().equals("swing")) continue;
                Class<?>[] t = m.getParameterTypes();
                if (t.length == 1) { m.invoke(p, InteractionHand.MAIN_HAND); return; }
                if (t.length == 3 && t[1].isEnum()) {
                    Object[] all = t[1].getEnumConstants();
                    Object pick = all[0];
                    for (Object c : all) {
                        String n = c.toString().toUpperCase();
                        if (n.contains("INTERACT") || n.contains("USE")) { pick = c; break; }
                    }
                    m.invoke(p, InteractionHand.MAIN_HAND, pick, true);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- helpers

    private static int thinkTicks() { return 1 + RNG.nextInt(Math.max(2, 9 - speed)); }

    private static boolean pickTarget(Minecraft mc, long now) {
        Level level = mc.level;
        Vec3 eye = mc.player.getEyePosition();
        Entry best = null; BlockPos bestWorld = null; double bestD = Double.MAX_VALUE;
        int bestY = Integer.MAX_VALUE;

        for (Entry e : ENTRIES) {
            BlockPos w = origin.offset(e.rel());
            if (RECENT.containsKey(w)) continue;
            if (Vec3.atCenterOf(w).distanceTo(eye) > REACH + 1) continue;
            BlockState cur = level.getBlockState(w);
            if (cur.is(e.state().getBlock()) || !cur.canBeReplaced()) continue;
            if (e.rel().getY() > bestY) continue;                 // lowest layer first
            double d = Vec3.atCenterOf(w).distanceToSqr(eye);
            if (e.rel().getY() < bestY || d < bestD) { best = e; bestWorld = w; bestD = d; bestY = e.rel().getY(); }
        }
        if (best == null) return false;

        // find a solid neighbour to click against
        List<Direction> dirs = new ArrayList<>(List.of(Direction.values()));
        Collections.shuffle(dirs);
        for (Direction d : dirs) {
            BlockPos n = bestWorld.relative(d);
            BlockState ns = level.getBlockState(n);
            if (ns.isAir() || ns.canBeReplaced() || !ns.getFluidState().isEmpty()) continue;
            if (ns.getBlock() instanceof EntityBlock) continue;   // would open a GUI instead of placing
            Direction face = d.getOpposite();
            Vec3 pt = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            double ox = face.getAxis() == Direction.Axis.X ? 0 : (RNG.nextDouble() - 0.5) * 0.5;
            double oy = face.getAxis() == Direction.Axis.Y ? 0 : (RNG.nextDouble() - 0.5) * 0.5;
            double oz = face.getAxis() == Direction.Axis.Z ? 0 : (RNG.nextDouble() - 0.5) * 0.5;
            Vec3 aim = pt.add(ox, oy, oz);
            if (aim.distanceTo(eye) > REACH) continue;

            target = best; targetWorld = bestWorld; clickBlock = n; clickFace = face; aimPoint = aim;
            return true;
        }
        RECENT.put(bestWorld, now + 100);   // nothing to click against yet, try later
        return false;
    }

    private static boolean holdItem(LocalPlayer p, BlockState state) {
        var item = state.getBlock().asItem();
        var inv = p.getInventory();
        if (inv.getItem(inv.getSelectedSlot()).is(item)) return true;
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.getItem(i);
            if (st.is(item)) { inv.setSelectedSlot(i); delay = 2 + RNG.nextInt(3); return true; }
        }
        return false;   // not in hotbar
    }
}
