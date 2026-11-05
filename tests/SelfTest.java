import com.example.companionmod.build.*;
import java.util.*;

public class SelfTest {
    static char sym(Blk b) {
        if (b == null || b.isAir()) return '.';
        String p = b.path();
        if (p.endsWith("_stairs")) switch (b.props().get("facing")) { case "north": return '^'; case "south": return 'v'; case "east": return '>'; default: return '<'; }
        if (p.endsWith("_door")) return b.props().get("half").equals("lower") ? 'D' : 'd';
        if (p.endsWith("_pane") || p.equals("iron_bars")) return '|';
        if (p.endsWith("lantern") || p.endsWith("torch")) return 'L';
        if (p.equals("ladder")) return 'H';
        if (p.endsWith("_log")) return 'T';
        if (p.endsWith("_slab")) return '_';
        if (p.endsWith("_planks") && !p.equals("spruce_planks")) return '=';
        if (p.equals("cobblestone")) return 'c';
        return '#';
    }
    static void layer(Schematic s, int y) {
        System.out.println("-- y=" + y);
        for (int z = 0; z < s.sizeZ(); z++) { StringBuilder sb = new StringBuilder();
            for (int x = 0; x < s.sizeX(); x++) sb.append(sym(s.blocks().get(new Schematic.Pos(x, y, z)))); System.out.println(sb); }
    }
    static void sliceX(Schematic s, int x) {
        System.out.println("-- section x=" + x + " (rows top->bottom, cols z)");
        for (int y = s.sizeY() - 1; y >= 0; y--) { StringBuilder sb = new StringBuilder();
            for (int z = 0; z < s.sizeZ(); z++) sb.append(sym(s.blocks().get(new Schematic.Pos(x, y, z)))); System.out.println(String.format("%2d ", y) + sb); }
    }
    static int disconnected(Schematic s) {
        Set<Schematic.Pos> seen = new HashSet<>(); Deque<Schematic.Pos> q = new ArrayDeque<>();
        for (var p : s.blocks().keySet()) if (p.y() == 0) { seen.add(p); q.add(p); }
        int[][] d = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        while (!q.isEmpty()) { var p = q.poll(); for (int[] v : d) { var n = new Schematic.Pos(p.x()+v[0], p.y()+v[1], p.z()+v[2]);
            if (s.blocks().containsKey(n) && seen.add(n)) q.add(n); } }
        return s.count() - seen.size();
    }
    static boolean air(Schematic s, int x, int y, int z) { return !s.blocks().containsKey(new Schematic.Pos(x,y,z)) || Schematic.isAttachment(s.get(x,y,z)) && !s.get(x,y,z).path().endsWith("_door") && false; }
    static boolean free(Schematic s, int x, int y, int z) { Blk b = s.get(x,y,z); return b.isAir() || b.path().endsWith("lantern") || b.path().endsWith("torch"); }
    static boolean solid(Schematic s, int x, int y, int z) { Blk b = s.get(x,y,z); return !b.isAir() && !free(s,x,y,z) && !b.path().equals("ladder"); }
    /** BFS from just inside the door; returns highest standing y reached. */
    static int reachMaxY(Schematic s, int sx, int sy, int sz) {
        Set<List<Integer>> seen = new HashSet<>(); Deque<int[]> q = new ArrayDeque<>(); q.add(new int[]{sx,sy,sz}); seen.add(List.of(sx,sy,sz)); int max = sy;
        int[][] d = {{1,0},{-1,0},{0,1},{0,-1}};
        while (!q.isEmpty()) { int[] p = q.poll(); max = Math.max(max, p[1]);
            for (int[] v : d) { int nx = p[0]+v[0], nz = p[2]+v[1];
                for (int dy = 1; dy >= -3; dy--) { int ny = p[1]+dy;
                    if (dy == 1 && !(free(s,p[0],p[1]+2,p[2]))) continue;
                    if (free(s,nx,ny,nz) && free(s,nx,ny+1,nz) && solid(s,nx,ny-1,nz) && nx>=0&&nz>=0&&nx<s.sizeX()&&nz<s.sizeZ()) {
                        if (seen.add(List.of(nx,ny,nz))) q.add(new int[]{nx,ny,nz}); break; } } } }
        return max;
    }
    public static void main(String[] a) {
        Map<String,String> mats = Map.of("wall","stone_bricks","roof","spruce_planks","trim","dark_oak_log","floor","oak_planks");
        BuildSpec h1 = new BuildSpec("house","cottage",9,7,16,1,"gable","square",mats);
        BuildSpec h2 = new BuildSpec("house","manor",11,9,16,3,"gable","square",mats);
        BuildSpec h3 = new BuildSpec("house","flat",7,7,16,2,"flat","square",Map.of());
        BuildSpec h4 = new BuildSpec("house","even",8,8,16,2,"gable","square",Map.of());
        BuildSpec t1 = new BuildSpec("tower","keep",7,7,22,1,"gable","square",Map.of("wall","cobblestone"));
        BuildSpec t2 = new BuildSpec("tower","round",9,9,26,1,"gable","round",Map.of("wall","stone_bricks","window","iron_bars"));
        BuildSpec junk = new BuildSpec("castle","$$ !!",1000,-5,999,99,"pyramid","hex",Map.of("wall","unobtainium","roof","glass","window","<script>"));
        for (BuildSpec sp : List.of(h1,h2,h3,h4,t1,t2,junk)) {
            Schematic s = Generators.generate(sp, id -> !id.contains("unobtainium") && !id.equals("minecraft:glass_stairs") && !id.equals("minecraft:glass_slab"));
            BuildSpec c = sp.clamped();
            int doorX = c.type().equals("house") ? c.width()/2 : c.width()/2;
            // door x in normalised coords is offset by overhang for gable houses
            int off = c.type().equals("house") && c.roof().equals("gable") ? 1 : 0;
            int zOff = off;
            System.out.printf("%s %s: size %dx%dx%d blocks=%d disconnected=%d items=%s%n", c.type(), c.name(), s.sizeX(), s.sizeY(), s.sizeZ(), s.count(), disconnected(s), s.itemCounts());
            if (c.type().equals("house")) {
                int top = c.floors()*4;
                int reach = reachMaxY(s, doorX+off, 1, 1+zOff);
                System.out.println("   walkable up to y=" + reach + " (storey base y of top floor = " + (4*(c.floors()-1)+1) + ")  -> " + (reach >= 4*(c.floors()-1)+1 ? "OK" : "FAIL"));
            }
        }
        // visual dumps
        Schematic s = Generators.generate(h2, BlockPolicy.ANY);
        for (int y : new int[]{0,1,2,3,4,5,8,12,13,14}) if (y < s.sizeY()) layer(s, y);
        sliceX(s, 5);
        sliceX(s, 1);
        Schematic t = Generators.generate(t2, BlockPolicy.ANY);
        for (int y : new int[]{0,1,3,6,26,27}) layer(t, y);
        sliceX(t, 4);
    }
}
