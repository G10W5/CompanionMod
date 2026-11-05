package com.example.companionmod.world;

import com.example.companionmod.build.BlkState;
import com.example.companionmod.build.BuildSpec;
import com.example.companionmod.build.Schematic;
import com.example.companionmod.build.SpecParser;
import com.example.companionmod.build.Substitution;
import com.example.companionmod.build.Transform;
import com.example.companionmod.entity.BlueprintSupply;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.entity.CompanionProtection;
import com.example.companionmod.net.BuildOp;
import com.example.companionmod.registry.ModContent;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of the build feature.
 *
 * Flow: a design arrives (generator spec from the client's AI call, an .nbt file name, or a voice order) and
 * becomes the player's PENDING design. While it is pending, an outline of its footprint follows where the player
 * is looking (particles, so no client rendering code and it works on both loaders). The player rotates / mirrors /
 * raises it with the clickable /cbuild controls and finally presses [Place], which checks the materials, applies
 * material swaps and hands a BuildJob to the companion.
 */
public final class BuildManager {
    private BuildManager() {}

    private static final int MAX_DISTANCE = 160;
    private static final int PENDING_TTL_TICKS = 20 * 60 * 10;

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_OP = new ConcurrentHashMap<>();

    private static final class Pending {
        final String label, type, data;
        final Schematic base;
        final boolean hasFront;
        final int created;
        int turns = -1;                 // -1 = choose so the front faces the player
        boolean mirror, substitute = true;
        int dy;
        BlockPos anchor;
        int lastDraw = -100;
        private Schematic cache;
        private int cacheTurns = -2;
        private boolean cacheMirror;

        Pending(String label, String type, String data, Schematic base, int created) {
            this.label = label;
            this.type = type;
            this.data = data;
            this.base = base;
            this.hasFront = "spec".equals(type);
            this.created = created;
        }

        Schematic transformed() {
            if (cache == null || cacheTurns != turns || cacheMirror != mirror) {
                cache = Transform.apply(base, Math.max(0, turns), mirror);
                cacheTurns = turns;
                cacheMirror = mirror;
            }
            return cache;
        }
    }

    // ------------------------------------------------------------------ incoming design (packet / voice)

    /** Called from CommandHandler for action "build_op". The client is untrusted: everything is re-validated. */
    public static void handlePacket(ServerPlayer p, String json) {
        long now = System.currentTimeMillis();
        Long last = LAST_OP.put(p.getUUID(), now);
        if (last != null && now - last < 400) return;
        JsonObject o;
        try {
            o = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        String op = o.has("op") && o.get("op").isJsonPrimitive() ? o.get("op").getAsString() : "";
        switch (op) {
            case "spec" -> {
                JsonElement se = o.get("spec");
                if (se == null || !se.isJsonObject()) { tell(p, "That design was empty."); return; }
                begin(p, "spec", se.toString());
            }
            case "file" -> begin(p, "file", o.has("name") && o.get("name").isJsonPrimitive() ? o.get("name").getAsString() : "");
            default -> { }
        }
    }

    /**
     * "build a stone tower" by voice / plan step. Returns false when the words name an old JSON schematic, so the
     * existing CompanionBuildGoal keeps handling those.
     */
    public static boolean startFromText(ServerPlayer p, String text) {
        String name = BuildSources.sanitize(text);
        if (BuildSources.hasFile(name)) { begin(p, "file", name); return true; }
        if (BuildSources.hasLegacyJson(name)) return false;
        BuildSpec spec = SpecParser.heuristic(text);
        begin(p, "spec", SpecParser.toJson(spec));
        return true;
    }

    private static void begin(ServerPlayer p, String type, String data) {
        BuildSources.Loaded l = BuildSources.load(type, data);
        if (l.schematic() == null) { tell(p, l.error()); return; }
        Pending pd = new Pending(l.label(), type, l.data(), l.schematic(), p.tickCount);
        PENDING.put(p.getUUID(), pd);
        Schematic base = pd.base.normalized();
        MutableComponent m = Component.literal("[Build] " + pd.label + ": " + base.sizeX() + "x" + base.sizeY() + "x" + base.sizeZ()
                + ", " + base.count() + " blocks. Look at the spot: an outline follows your aim. ");
        m.append(controls());
        p.sendSystemMessage(m);
    }

    // ------------------------------------------------------------------ preview (called from the companion's tick)

    public static void tick(ServerPlayer p) {
        Pending pd = PENDING.get(p.getUUID());
        if (pd == null) return;
        if (p.tickCount - pd.created > PENDING_TTL_TICKS) {
            PENDING.remove(p.getUUID());
            tell(p, "The design expired.");
            return;
        }
        if (p.tickCount - pd.lastDraw < 8) return;
        pd.lastDraw = p.tickCount;
        if (!(p.level() instanceof ServerLevel level)) return;
        if (pd.turns < 0) pd.turns = autoTurns(p);
        HitResult hit = p.pick(30.0, 1.0f, false);                                // 26.1-CHECK: Entity.pick(double,float,boolean)
        if (hit instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) pd.anchor = bh.getBlockPos().immutable();
        if (pd.anchor == null) return;
        drawOutline(level, pd);
    }

    private static final Direction[] CW = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /** The generators put the door on the north face; rotate so it faces the player. */
    private static int autoTurns(ServerPlayer p) {
        Direction front = p.getDirection().getOpposite();
        for (int i = 0; i < 4; i++) if (CW[i] == front) return i;
        return 0;
    }

    private static BlockPos corner(Pending pd, Schematic t) {
        return new BlockPos(pd.anchor.getX() - t.sizeX() / 2, pd.anchor.getY() + pd.dy, pd.anchor.getZ() - t.sizeZ() / 2);
    }

    private static void drawOutline(ServerLevel level, Pending pd) {
        Schematic t = pd.transformed();
        BlockPos c = corner(pd, t);
        double x0 = c.getX(), y0 = c.getY(), z0 = c.getZ();
        double x1 = x0 + t.sizeX(), y1 = y0 + t.sizeY(), z1 = z0 + t.sizeZ();
        double step = Math.max(1.0, 4.0 * (t.sizeX() + t.sizeY() + t.sizeZ()) / 300.0);
        ParticleOptions pt = ParticleTypes.END_ROD;
        for (double[] e : new double[][]{
                {x0, y0, z0, x1, y0, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z0, x1, y1, z0}, {x0, y1, z1, x1, y1, z1},
                {x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1},
                {x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1}}) {
            line(level, pt, e[0], e[1], e[2], e[3], e[4], e[5], step);
        }
        if (pd.hasFront) {                                                        // green marker = the door side
            Direction f = CW[Math.floorMod(pd.turns, 4)];
            double mx = f == Direction.EAST ? x1 : f == Direction.WEST ? x0 : (x0 + x1) / 2;
            double mz = f == Direction.SOUTH ? z1 : f == Direction.NORTH ? z0 : (z0 + z1) / 2;
            for (int i = 0; i < 4; i++) level.sendParticles(ParticleTypes.HAPPY_VILLAGER, mx, y0 + 0.5 + i * 0.6, mz, 1, 0, 0, 0, 0.0);
        }
    }

    private static void line(ServerLevel l, ParticleOptions pt, double x0, double y0, double z0, double x1, double y1, double z1, double step) {
        double len = Math.max(Math.abs(x1 - x0), Math.max(Math.abs(y1 - y0), Math.abs(z1 - z0)));
        int n = (int) Math.max(1, Math.ceil(len / step));
        for (int i = 0; i <= n; i++) {
            double f = (double) i / n;
            l.sendParticles(pt, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, z0 + (z1 - z0) * f, 1, 0, 0, 0, 0.0);   // 26.1-CHECK: sendParticles(options,x,y,z,count,dx,dy,dz,speed)
        }
    }

    // ------------------------------------------------------------------ controls (/cbuild ...)

    public static void rotate(ServerPlayer p) {
        Pending pd = need(p);
        if (pd == null) return;
        if (pd.turns < 0) pd.turns = autoTurns(p);
        pd.turns = (pd.turns + 1) & 3;
        pd.lastDraw = -100;
        tell(p, "Rotated.");
    }

    public static void mirror(ServerPlayer p) {
        Pending pd = need(p);
        if (pd == null) return;
        pd.mirror = !pd.mirror;
        pd.lastDraw = -100;
        tell(p, "Mirrored.");
    }

    public static void shift(ServerPlayer p, int dy) {
        Pending pd = need(p);
        if (pd == null) return;
        pd.dy += dy;
        pd.lastDraw = -100;
        tell(p, "Height offset " + (pd.dy >= 0 ? "+" : "") + pd.dy + ".");
    }

    public static void substitute(ServerPlayer p, boolean on) {
        Pending pd = need(p);
        if (pd == null) return;
        pd.substitute = on;
        tell(p, on ? "I will swap missing materials for similar ones." : "I will only use the exact blocks.");
    }

    public static void cancel(ServerPlayer p) {
        boolean had = PENDING.remove(p.getUUID()) != null;
        CompanionEntity c = ModContent.findCompanion(p.level().getServer(), p.getUUID());
        if (c != null && c.blueprint().exists()) {
            c.cancelBlueprint();
            tell(p, "Build cancelled. Blocks already placed stay.");
        } else if (had) {
            tell(p, "Design discarded.");
        } else {
            tell(p, "Nothing to cancel.");
        }
    }

    public static void stop(ServerPlayer p) {
        CompanionEntity c = ModContent.findCompanion(p.level().getServer(), p.getUUID());
        if (c == null || !c.blueprint().exists()) { tell(p, "Your companion is not building."); return; }
        c.blueprint().pause();
        c.getNavigation().stop();
        tell(p, "Build paused. Use /cbuild resume or say continue.");
    }

    public static void resume(ServerPlayer p) {
        CompanionEntity c = ModContent.findCompanion(p.level().getServer(), p.getUUID());
        if (c == null || !c.blueprint().exists()) { tell(p, "Your companion is not building."); return; }
        c.blueprint().resume();
        tell(p, "Back to building.");
    }

    public static void info(ServerPlayer p) {
        CompanionEntity c = ModContent.findCompanion(p.level().getServer(), p.getUUID());
        Pending pd = PENDING.get(p.getUUID());
        if (pd == null) {
            if (c != null && c.blueprint().exists()) tell(p, "[Build] " + c.blueprint().describe());
            else tell(p, "[Build] No design. Open the radial menu: Work > Build.");
            return;
        }
        Schematic t = pd.transformed();
        Map<String, Integer> need = needs(t);
        tell(p, "[Build] " + pd.label + ": " + t.sizeX() + "x" + t.sizeY() + "x" + t.sizeZ() + ", " + t.count() + " blocks.");
        Map<String, Integer> have = null;
        if (c != null && pd.anchor != null) {
            BlueprintSupply sup = new BlueprintSupply(c);
            sup.setCenter(corner(pd, t).offset(t.sizeX() / 2, 0, t.sizeZ() / 2));
            sup.beginTick((ServerLevel) p.level());
            have = sup.snapshot();
        }
        tell(p, "Needs: " + list(need, 8));
        if (have != null) {
            Map<String, Integer> short_ = shortage(need, have);
            tell(p, short_.isEmpty() ? "I have everything." : "Short on: " + list(short_, 8));
        }
    }

    // ------------------------------------------------------------------ place

    public static void place(ServerPlayer p) {
        Pending pd = need(p);
        if (pd == null) return;
        if (!(p.level() instanceof ServerLevel level)) return;
        if (pd.anchor == null) { tell(p, "Look at the ground where it should go first."); return; }
        CompanionEntity c = ModContent.findCompanion(level.getServer(), p.getUUID());
        if (c == null) { tell(p, "You have no companion nearby. Activate a Companion Shrine."); return; }
        if (c.blueprint().exists()) { tell(p, "Your companion is already building. Use /cbuild cancel first."); return; }

        Schematic t = pd.transformed();
        BlockPos corner = corner(pd, t);
        if (corner.getY() < level.getMinY() || corner.getY() + t.sizeY() > level.getMaxY()) { tell(p, "That does not fit between the world's bottom and top."); return; }
        if (corner.distSqr(p.blockPosition()) > (double) MAX_DISTANCE * MAX_DISTANCE) { tell(p, "That is too far away."); return; }
        if (t.count() > BuildSources.MAX_BLOCKS) { tell(p, "That design is too big."); return; }
        if (!level.mayInteract(p, corner)) { tell(p, "You are not allowed to build there."); return; }      // 26.1-CHECK

        BlockPos center = corner.offset(t.sizeX() / 2, 0, t.sizeZ() / 2);
        Map<String, Integer> need = needs(t);
        BlueprintSupply sup = new BlueprintSupply(c);
        sup.setCenter(center);
        sup.beginTick(level);
        Map<String, Integer> have = sup.snapshot();
        boolean creative = p.isCreative();

        Map<String, String> remap = new TreeMap<>();
        Map<String, Integer> missing = new TreeMap<>();
        if (!creative) {
            if (pd.substitute) {
                Substitution.Result r = Substitution.plan(need, have);
                remap = new TreeMap<>(r.remap());
                missing = r.missing();
            } else {
                missing = shortage(need, have);
            }
        }

        c.startBlueprint(pd.label, pd.type, pd.data, corner, Math.max(0, pd.turns), pd.mirror, Substitution.serialize(remap));
        PENDING.remove(p.getUUID());

        tell(p, "[Build] Starting " + pd.label + " (" + t.count() + " blocks)" + (creative ? " - creative mode, no materials needed." : "."));
        if (!remap.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (var e : remap.entrySet()) sb.append(sb.length() > 0 ? ", " : "").append(pretty(e.getKey())).append(" -> ").append(pretty(e.getValue()));
            tell(p, "Swapping: " + sb);
        }
        if (!missing.isEmpty()) {
            tell(p, "Short on: " + list(missing, 8) + ". I will build what I can, then ask. Put materials in my bag or a chest within 10 blocks.");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Pending need(ServerPlayer p) {
        Pending pd = PENDING.get(p.getUUID());
        if (pd == null) tell(p, "No design yet. Open the radial menu: Work > Build.");
        return pd;
    }

    /** item id -> count the schematic needs. */
    private static Map<String, Integer> needs(Schematic t) {
        Map<String, Integer> m = new TreeMap<>();
        Map<com.example.companionmod.build.Blk, String> idCache = new HashMap<>();
        for (var b : t.blocks().values()) {
            int n = b.itemsNeeded();
            if (n == 0) continue;
            String id = idCache.computeIfAbsent(b, BlkState::itemId);
            if (id != null) m.merge(id, n, Integer::sum);
        }
        return m;
    }

    private static Map<String, Integer> shortage(Map<String, Integer> need, Map<String, Integer> have) {
        Map<String, Integer> m = new TreeMap<>();
        for (var e : need.entrySet()) {
            int d = e.getValue() - have.getOrDefault(e.getKey(), 0);
            if (d > 0) m.put(e.getKey(), d);
        }
        return m;
    }

    private static String list(Map<String, Integer> m, int max) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (var e : m.entrySet()) {
            if (i++ == max) { sb.append(", +").append(m.size() - max).append(" more"); break; }
            sb.append(i > 1 ? ", " : "").append(e.getValue()).append(' ').append(pretty(e.getKey()));
        }
        return sb.length() == 0 ? "nothing" : sb.toString();
    }

    private static String pretty(String id) {
        return id.substring(id.indexOf(':') + 1).replace('_', ' ');
    }

    private static void tell(ServerPlayer p, String text) {
        p.sendSystemMessage(Component.literal(text));
    }

    private static MutableComponent controls() {
        MutableComponent m = Component.literal("");
        String[][] b = {{"Rotate", "/cbuild rotate"}, {"Mirror", "/cbuild mirror"}, {"Up", "/cbuild up"}, {"Down", "/cbuild down"},
                {"Place", "/cbuild place"}, {"Cancel", "/cbuild cancel"}};
        for (String[] e : b) m.append(button(e[0], e[1])).append(Component.literal(" "));
        return m;
    }

    /** 26.1-CHECK: ClickEvent became a set of records in 1.21.5 (ClickEvent.RunCommand). */
    private static Component button(String label, String command) {
        return Component.literal("[" + label + "]")
                .withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand(command)).withColor(ChatFormatting.AQUA));
    }

    // ------------------------------------------------------------------ lifecycle

    public static void forget(UUID player) {
        PENDING.remove(player);
        LAST_OP.remove(player);
    }

    public static void forgetAll() {
        PENDING.clear();
        LAST_OP.clear();
    }

    /** For tests / the screen: is there a pending design? */
    public static Optional<String> pendingLabel(UUID player) {
        Pending pd = PENDING.get(player);
        return pd == null ? Optional.empty() : Optional.of(pd.label);
    }
}
