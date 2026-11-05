package com.example.companionmod.build;

import java.util.ArrayList;
import java.util.List;

/**
 * Square or round tower. Local frame: footprint n x n at x,z in [0,n), centre column c = n/2.
 * Front (door) is z=0 facing north; the ladder is on the inside of the back wall.
 */
public final class TowerGenerator implements Generator {

    @Override
    public Schematic generate(BuildSpec sp, Materials m) {
        Schematic s = new Schematic();
        int n = sp.width(), h = sp.height(), c = n / 2;
        boolean round = "round".equals(sp.shape());

        boolean[][] in = new boolean[n][n], edge = new boolean[n][n];
        for (int x = 0; x < n; x++)
            for (int z = 0; z < n; z++)
                in[x][z] = !round || 4 * ((x - c) * (x - c) + (z - c) * (z - c)) <= n * n;
        for (int x = 0; x < n; x++)
            for (int z = 0; z < n; z++)
                edge[x][z] = in[x][z] && (!at(in, x - 1, z) || !at(in, x + 1, z) || !at(in, x, z - 1) || !at(in, x, z + 1));

        Blk wall = Blk.of(m.wall()), floor = Blk.of(m.floor()), found = Blk.of(m.foundation());
        Blk post = Parts.trim(m, "y");

        // ladder column: inside the back wall
        int lz = n - 2;
        if (round) { lz = 0; for (int z = 0; z < n; z++) if (in[c][z]) lz = z; lz -= 1; }

        // foundation
        for (int x = 0; x < n; x++)
            for (int z = 0; z < n; z++)
                if (in[x][z]) s.set(x, 0, z, found);

        // walls
        for (int y = 1; y <= h; y++)
            for (int x = 0; x < n; x++)
                for (int z = 0; z < n; z++) {
                    if (!edge[x][z]) continue;
                    boolean corner = !round && (x == 0 || x == n - 1) && (z == 0 || z == n - 1);
                    s.set(x, y, z, corner ? post : wall);
                }

        // floors every 6 blocks, plus the roof platform at y=h
        List<Integer> fy = new ArrayList<>(List.of(0));
        for (int y = 6; y <= h - 3; y += 6) fy.add(y);
        fy.add(h);
        for (int i = 1; i < fy.size(); i++)
            for (int x = 0; x < n; x++)
                for (int z = 0; z < n; z++)
                    if (in[x][z] && !edge[x][z] && !(x == c && z == lz)) s.set(x, fy.get(i), z, floor);

        // ladder (faces north, attached to the wall behind it)
        Blk ladder = Blk.of("ladder").with("facing", "north");
        for (int y = 1; y <= h; y++) s.set(c, y, lz, ladder);

        // arrow slits, 2 high, on front / left / right
        for (int y0 = 3; y0 <= h - 4; y0 += 6) {
            for (int dy = 0; dy < 2; dy++) {
                if (edge[c][0]) Parts.setWindow(s, c, y0 + dy, 0, m, true);
                if (edge[0][c]) Parts.setWindow(s, 0, y0 + dy, c, m, false);
                if (edge[n - 1][c]) Parts.setWindow(s, n - 1, y0 + dy, c, m, false);
            }
        }

        // door
        s.remove(c, 1, 0);
        s.remove(c, 2, 0);
        Parts.door(s, c, 1, 0, m);

        // one light per storey in the middle
        if (!m.light().equals("air"))
            for (int i = 0; i < fy.size() - 1; i++) {
                if (Parts.hangs(m)) s.set(c, fy.get(i + 1) - 1, c, Parts.light(m, true));
                else s.set(c, fy.get(i) + 1, c, Parts.light(m, false));
            }

        // battlements: every other perimeter block
        for (int x = 0; x < n; x++)
            for (int z = 0; z < n; z++)
                if (edge[x][z] && (x + z) % 2 == 0) s.set(x, h + 1, z, post);
        return s;
    }

    private static boolean at(boolean[][] a, int x, int z) {
        return x >= 0 && z >= 0 && x < a.length && z < a.length && a[x][z];
    }
}
