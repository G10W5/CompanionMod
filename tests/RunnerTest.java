import com.example.companionmod.build.*;
import com.example.companionmod.build.BuildPlan.Placement;
import com.example.companionmod.build.BuildRunner.*;
import java.util.*;

public class RunnerTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "PASS  " : "FAIL  ") + what); if (!ok) fails++; }

    static class FakeEnv implements Env {
        final Map<String, Blk> world = new HashMap<>();
        final Map<String, Integer> items = new HashMap<>();
        boolean creative; int entityBlockedCalls = 0; Set<String> entityCells = new HashSet<>(); boolean slowReach; int reachCalls = 0;
        Set<String> unreachable = new HashSet<>(); int placedCount;
        static String k(int x, int y, int z) { return x + "," + y + "," + z; }
        public Inspect inspect(int x, int y, int z, Blk want) {
            Blk b = world.get(k(x,y,z));
            if (b != null) return b.id().equals(want.id()) ? Inspect.ALREADY : Inspect.OCCUPIED;
            if (entityCells.contains(k(x,y,z)) && entityBlockedCalls-- > 0) return Inspect.ENTITY;
            return Inspect.FREE;
        }
        public boolean creative() { return creative; }
        public String itemId(Blk b) { return b.id(); }
        public int have(String id) { return items.getOrDefault(id, 0); }
        public void take(String id, int n) { items.merge(id, -n, Integer::sum); }
        String lastTarget = ""; boolean arrived;
        public Reach reach(int x, int y, int z) {
            reachCalls++;
            String t = k(x,y,z);
            if (unreachable.contains(t)) return Reach.GAVE_UP;
            if (slowReach && !t.equals(lastTarget)) { lastTarget = t; return Reach.MOVING; }
            lastTarget = "";
            return Reach.IN_REACH;
        }
        public void place(int x, int y, int z, Blk w) { world.put(k(x,y,z), w); placedCount++; }
    }

    static Status runToStop(BuildRunner r, int maxTicks) {
        Status s = Status.WORKING;
        for (int t = 0; t < maxTicks; t++) { s = r.tick(3, 400); if (s != Status.WORKING) return s; }
        return s;
    }

    public static void main(String[] a) {
        BuildSpec spec = new BuildSpec("house","manor",11,9,16,2,"gable","square",Map.of());
        Schematic s = Generators.generate(spec, BlockPolicy.ANY);
        List<Placement> order = BuildPlan.order(s, Map.of());
        System.out.println("blocks=" + s.count() + " order=" + order.size());

        // order checks
        int firstAttach = -1; for (int i = 0; i < order.size(); i++) if (Schematic.isAttachment(order.get(i).blk())) { firstAttach = i; break; }
        boolean allAfter = true; for (int i = firstAttach; i < order.size(); i++) if (!Schematic.isAttachment(order.get(i).blk())) allAfter = false;
        check(firstAttach > 0 && allAfter, "attachments (doors/lanterns) are placed after all structure");
        boolean bottomUp = true; for (int i = 1; i < firstAttach; i++) if (order.get(i).dy() < order.get(i-1).dy()) bottomUp = false;
        check(bottomUp, "structure placed bottom-up");
        int lowerDoor=-1, upperDoor=-1; for (int i=0;i<order.size();i++){ var b=order.get(i).blk(); if(b.path().endsWith("_door")) { if(b.props().get("half").equals("lower")) lowerDoor=i; else upperDoor=i; } }
        check(lowerDoor >= 0 && lowerDoor < upperDoor, "lower door half before upper half");
        // serpentine adjacency within a layer: average step distance small
        long sum = 0; int n = 0; for (int i = 1; i < firstAttach; i++) { var p=order.get(i-1); var q=order.get(i); if (p.dy()==q.dy()) { sum += Math.abs(p.dx()-q.dx())+Math.abs(p.dz()-q.dz()); n++; } }
        System.out.printf("avg walk step in a layer: %.2f blocks%n", (double) sum / n);
        check((double) sum / n < 3.0, "serpentine keeps consecutive blocks close");

        // 1. creative, full build, slow reach
        FakeEnv e = new FakeEnv(); e.creative = true; e.slowReach = true;
        BuildRunner r = new BuildRunner(order, 100, 64, 100, e);
        Status st = runToStop(r, 100000);
        check(st == Status.DONE && e.placedCount == order.size() && r.placed == order.size(), "creative build places every block (" + e.placedCount + "/" + order.size() + ")");

        // 2. limited materials then refill
        Map<String,Integer> need = s.itemCounts();
        FakeEnv e2 = new FakeEnv(); for (var en : need.entrySet()) e2.items.put(en.getKey(), en.getValue());
        e2.items.put("minecraft:stone_bricks", need.get("minecraft:stone_bricks") / 2);
        r = new BuildRunner(order, 0, 0, 0, e2);
        st = runToStop(r, 100000);
        int short_ = need.get("minecraft:stone_bricks") - need.get("minecraft:stone_bricks") / 2;
        check(st == Status.WAITING_MATERIALS && r.missing().getOrDefault("minecraft:stone_bricks", 0) == short_,
              "ran out of stone bricks -> waiting, missing=" + r.missing());
        check(e2.placedCount == order.size() - short_, "everything else was still built while waiting (" + e2.placedCount + ")");
        e2.items.put("minecraft:stone_bricks", short_);
        r.resume(); st = runToStop(r, 100000);
        check(st == Status.DONE && e2.placedCount == order.size() && e2.world.size() == order.size(), "refill + resume finishes the build");
        check(e2.items.values().stream().allMatch(v -> v == 0), "exact material accounting (door/slab rules) leaves zero items: " + e2.items);

        // 3. occupied cell + unreachable cell
        FakeEnv e3 = new FakeEnv(); e3.creative = true;
        var p0 = order.get(10); e3.world.put(FakeEnv.k(p0.dx(), p0.dy(), p0.dz()), Blk.of("minecraft:dirt"));
        var p1 = order.get(20); e3.unreachable.add(FakeEnv.k(p1.dx(), p1.dy(), p1.dz()));
        r = new BuildRunner(order, 0, 0, 0, e3); st = runToStop(r, 100000);
        check(st == Status.STALLED && r.skipped == 0 && e3.placedCount == order.size() - 2,
                "occupied + unreachable cells are REPORTED as STALLED, never skipped (st=" + st + ", skipped=" + r.skipped + ")");

        // 4. creature standing in a cell for a few checks
        FakeEnv e4 = new FakeEnv(); e4.creative = true; e4.entityBlockedCalls = 3; var p2 = order.get(30); e4.entityCells.add(FakeEnv.k(p2.dx(), p2.dy(), p2.dz()));
        r = new BuildRunner(order, 0, 0, 0, e4); int w4 = 0;
        for (int i = 0; i < 100000; i++) { st = r.tick(3, 400); if (st == Status.WAITING_ENTITY) { w4++; r.resume(); } else if (st == Status.DONE) break; }
        check(st == Status.DONE && e4.placedCount == order.size() && w4 >= 1, "a cell blocked by a creature waits, is retried, then placed (waits=" + w4 + ")");

        // 5. creature never leaves -> waits, then gives up without hanging
        FakeEnv e5 = new FakeEnv(); e5.creative = true; e5.entityBlockedCalls = Integer.MAX_VALUE; e5.entityCells.add(FakeEnv.k(p2.dx(), p2.dy(), p2.dz()));
        r = new BuildRunner(order, 0, 0, 0, e5); int waits = 0;
        for (int i = 0; i < 100000; i++) { st = r.tick(3, 400); if (st == Status.WAITING_ENTITY) { waits++; r.resume(); } else if (st == Status.DONE || st == Status.STALLED) break; }
        check(st == Status.STALLED && waits == BuildRunner.MAX_ENTITY_RETRIES && e5.placedCount == order.size() - 1, "permanent blocker: " + waits + " retries then STALLED (cell kept, reported)");

        // 6. scan budget: an already-built structure is swept without placing, in many ticks, never one huge tick
        FakeEnv e6 = new FakeEnv(); e6.creative = true; for (var p : order) e6.world.put(FakeEnv.k(p.dx(), p.dy(), p.dz()), p.blk());
        r = new BuildRunner(order, 0, 0, 0, e6); int ticks = 0; do { st = r.tick(3, 400); ticks++; } while (st == Status.WORKING && ticks < 1000);
        check(st == Status.DONE && ticks >= order.size() / 400 && e6.placedCount == 0, "already-built sweep spread over " + ticks + " ticks, places nothing");

        // 7. substitution
        Map<String,Integer> nd = new HashMap<>(Map.of("minecraft:stone_bricks", 300, "minecraft:spruce_stairs", 80, "minecraft:oak_door", 1, "minecraft:lantern", 2));
        Map<String,Integer> hv = new HashMap<>(Map.of("minecraft:stone_bricks", 20, "minecraft:cobblestone", 500, "minecraft:oak_stairs", 100, "minecraft:spruce_stairs", 10, "minecraft:dirt", 999));
        var res = Substitution.plan(nd, hv);
        System.out.println("remap=" + res.remap() + " missing=" + res.missing());
        check("minecraft:cobblestone".equals(res.remap().get("minecraft:stone_bricks")), "stone bricks -> cobblestone (not dirt)");
        check("minecraft:oak_stairs".equals(res.remap().get("minecraft:spruce_stairs")), "spruce stairs -> oak stairs");
        check(res.missing().containsKey("minecraft:oak_door") && res.missing().containsKey("minecraft:lantern") && res.missing().size() == 2, "no door/lantern on hand -> reported missing, never swapped for random items");
        check(Substitution.parse(Substitution.serialize(res.remap())).equals(res.remap()), "remap survives save/load string");
        var res2 = Substitution.plan(Map.of("minecraft:stone_bricks", 300), Map.of("minecraft:stone_bricks", 20, "minecraft:cobblestone", 100));
        check(res2.remap().isEmpty() && res2.missing().get("minecraft:stone_bricks") == 280, "alternative too small -> no swap, honest shortage");

        // 8. remap applied by ordering
        List<Placement> sub = BuildPlan.order(s, res.remap());
        check(sub.stream().noneMatch(p -> p.blk().id().equals("minecraft:stone_bricks")) && sub.stream().anyMatch(p -> p.blk().id().equals("minecraft:cobblestone")), "remap replaces ids in the plan");
        check(sub.stream().filter(p -> p.blk().path().endsWith("stairs")).allMatch(p -> p.blk().props().containsKey("facing")), "stairs keep their facing after remap");

        // 9. transform.apply: mirror then rotate, door survives
        Schematic t = Transform.apply(s, 3, true);
        check(t.count() == s.count(), "apply() keeps every block");
        System.out.println(fails == 0 ? "\nALL PASSED" : "\nFAILURES: " + fails);
    }
}
