package com.example.companionmod.build;

/**
 * Local frame: x = width (east), z = depth (south), y = up. Front face is z=0, door faces north.
 * Origin cell (0,0,0) is the north-west foundation corner; roof overhang uses -1..w / -1..d.
 * Storey height is 4 (floor + 3 air). y=0 is foundation/ground floor.
 */
public final class HouseGenerator implements Generator {

    @Override
    public Schematic generate(BuildSpec sp, Materials m) {
        Schematic s = new Schematic();
        int w = sp.width(), d = sp.depth(), fl = sp.floors(), top = fl * 4;
        Blk wall = Blk.of(m.wall()), floor = Blk.of(m.floor()), found = Blk.of(m.foundation());
        Blk post = Parts.trim(m, "y"), beamX = Parts.trim(m, "x"), beamZ = Parts.trim(m, "z");
        int doorX = w / 2;

        // foundation + ground floor
        for (int x = 0; x < w; x++)
            for (int z = 0; z < d; z++)
                s.set(x, 0, z, perimeter(x, z, w, d) ? found : floor);

        // walls: corner posts, horizontal beams on every floor line, wall elsewhere
        for (int y = 1; y <= top; y++)
            for (int x = 0; x < w; x++)
                for (int z = 0; z < d; z++) {
                    if (!perimeter(x, z, w, d)) continue;
                    boolean corner = (x == 0 || x == w - 1) && (z == 0 || z == d - 1);
                    Blk b = wall;
                    if (corner) b = post;
                    else if (y % 4 == 0) b = (z == 0 || z == d - 1) ? beamX : beamZ;
                    s.set(x, y, z, b);
                }

        // interior floors / ceiling
        for (int st = 1; st <= fl; st++)
            for (int x = 1; x < w - 1; x++)
                for (int z = 1; z < d - 1; z++)
                    s.set(x, 4 * st, z, floor);

        // windows (one block high, 2 blocks above each floor)
        for (int st = 0; st < fl; st++) {
            int wy = 4 * st + 2;
            for (int x = 2; x <= w - 3; x += 3) {
                if (!(st == 0 && Math.abs(x - doorX) <= 1)) Parts.setWindow(s, x, wy, 0, m, true);
                Parts.setWindow(s, x, wy, d - 1, m, true);
            }
            for (int z = 2; z <= d - 3; z += 3) {
                Parts.setWindow(s, 0, wy, z, m, false);
                Parts.setWindow(s, w - 1, wy, z, m, false);
            }
        }

        // front door
        s.remove(doorX, 1, 0);
        s.remove(doorX, 2, 0);
        Parts.door(s, doorX, 1, 0, m);

        // staircases: alternate sides so the arrival point never sits on the next flight's hole
        for (int st = 0; st < fl - 1; st++) {
            boolean left = st % 2 == 0;
            int x = left ? 1 : w - 2;
            int upper = 4 * (st + 1);
            for (int k = 0; k < 3; k++) s.remove(x, upper, left ? 1 + k : d - 2 - k);
            for (int k = 0; k < 4; k++)
                s.set(x, 4 * st + 1 + k, left ? 1 + k : d - 2 - k, Parts.stair(m, left ? "south" : "north"));
        }

        // lighting: one light per storey in the middle of the room
        if (!m.light().equals("air"))
            for (int st = 0; st < fl; st++) {
                if (Parts.hangs(m)) s.set(w / 2, 4 * (st + 1) - 1, d / 2, Parts.light(m, true));
                else s.set(w / 2, 4 * st + 1, d / 2, Parts.light(m, false));
            }

        // roof
        if ("flat".equals(sp.roof())) {
            Blk slab = Blk.of(Materials.slabOf(m.roof())).with("type", "bottom");
            for (int x = 0; x < w; x++)
                for (int z = 0; z < d; z++)
                    if (perimeter(x, z, w, d)) s.set(x, top + 1, z, slab);
        } else {
            gableRoof(s, w, d, top, m, wall);
        }
        return s;
    }

    /** Ridge runs along X; slopes face -z and +z; stairs face the ridge. 1-block overhang all round. */
    private void gableRoof(Schematic s, int w, int d, int top, Materials m, Blk wall) {
        Blk full = Blk.of(m.roof());
        for (int i = 0; ; i++) {
            int zl = -1 + i, zh = d - i, gap = zh - zl, y = top + i;
            if (gap < 0) break;
            if (gap == 0) {
                for (int x = -1; x <= w; x++) s.set(x, y, zl, full);
                break;
            }
            for (int x = -1; x <= w; x++) {
                s.set(x, y, zl, Parts.stair(m, "south"));
                s.set(x, y, zh, Parts.stair(m, "north"));
            }
            if (i >= 1)
                for (int z = zl + 1; z <= zh - 1; z++) {   // gable end walls
                    s.set(0, y, z, wall);
                    s.set(w - 1, y, z, wall);
                }
            if (gap == 1) break;
        }
    }

    private static boolean perimeter(int x, int z, int w, int d) {
        return x == 0 || x == w - 1 || z == 0 || z == d - 1;
    }
}
