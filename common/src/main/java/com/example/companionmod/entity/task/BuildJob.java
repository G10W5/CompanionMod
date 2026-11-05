package com.example.companionmod.entity.task;

import com.example.companionmod.build.Blk;
import com.example.companionmod.build.BuildPlan;
import com.example.companionmod.build.BuildRunner;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The companion's current "build this design" job, saved with the entity like MineTask. Only the SOURCE is saved
 * (the design spec JSON or the .nbt file name, plus where / how it is placed and which materials were swapped),
 * never the block list: after a reload the plan is regenerated and the sweep simply skips what is already built.
 */
public final class BuildJob {
    public String type = "";          // "spec" | "file"; blank = no job
    public String data = "";          // spec JSON or file name
    public String label = "", subs = "";
    public int cx, cy, cz, turns, placedTotal;
    public boolean mirror, paused;

    // transient, rebuilt by BuildSources.prepare
    public List<BuildPlan.Placement> list = new ArrayList<>();
    public final Set<Long> cells = new HashSet<>();
    public final Map<Blk, BlockState> states = new HashMap<>();
    public BuildRunner runner;
    public int sx, sy, sz;
    public String error = "";

    public boolean exists() { return !type.isEmpty(); }
    public boolean running() { return exists() && !paused; }
    public BlockPos corner() { return new BlockPos(cx, cy, cz); }
    public BlockPos center() { return new BlockPos(cx + sx / 2, cy, cz + sz / 2); }

    public void start(String label, String type, String data, int cx, int cy, int cz, int turns, boolean mirror, String subs) {
        cancel();
        this.label = label;
        this.type = type;
        this.data = data;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.turns = turns;
        this.mirror = mirror;
        this.subs = subs == null ? "" : subs;
    }

    public void pause() { if (exists()) paused = true; }
    public boolean resume() { if (!exists()) return false; paused = false; return true; }

    public void cancel() {
        type = "";
        data = "";
        label = "";
        subs = "";
        paused = false;
        placedTotal = 0;
        list = new ArrayList<>();
        cells.clear();
        states.clear();
        runner = null;
        error = "";
    }

    public String describe() {
        String n = label.isBlank() ? "build" : label;
        if (paused) return "Build paused (" + n + ") || say 'continue'";
        return runner == null ? "Building " + n : "Building " + n + " " + runner.percent() + "%";
    }

    public void save(ValueOutput out) {
        if (!exists()) return;
        out.putString("BpType", type);
        out.putString("BpData", data);
        out.putString("BpLabel", label);
        out.putString("BpSubs", subs);
        out.putInt("BpX", cx);
        out.putInt("BpY", cy);
        out.putInt("BpZ", cz);
        out.putInt("BpTurns", turns);
        out.putBoolean("BpMirror", mirror);
        out.putBoolean("BpPaused", paused);
        out.putInt("BpPlaced", placedTotal);
    }

    public void load(ValueInput in) {
        type = in.getStringOr("BpType", "");
        data = in.getStringOr("BpData", "");
        label = in.getStringOr("BpLabel", "");
        subs = in.getStringOr("BpSubs", "");
        cx = in.getIntOr("BpX", 0);
        cy = in.getIntOr("BpY", 0);
        cz = in.getIntOr("BpZ", 0);
        turns = in.getIntOr("BpTurns", 0);
        mirror = in.getBooleanOr("BpMirror", false);
        paused = in.getBooleanOr("BpPaused", false);
        placedTotal = in.getIntOr("BpPlaced", 0);
        runner = null;
        if (type.isEmpty() || data.isEmpty()) type = "";
    }
}
