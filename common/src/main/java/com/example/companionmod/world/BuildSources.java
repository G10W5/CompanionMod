package com.example.companionmod.world;

import com.example.companionmod.CompanionMod;
import com.example.companionmod.build.BlkState;
import com.example.companionmod.build.BuildPlan;
import com.example.companionmod.build.BuildSpec;
import com.example.companionmod.build.Generators;
import com.example.companionmod.build.Schematic;
import com.example.companionmod.build.SchematicNbt;
import com.example.companionmod.build.SpecParser;
import com.example.companionmod.build.Substitution;
import com.example.companionmod.build.Transform;
import com.example.companionmod.entity.task.BuildJob;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Loads a design from its saved source: a generator spec (JSON) or an .nbt file in config/companionmod/schematics. */
public final class BuildSources {
    private BuildSources() {}

    public static final int MAX_BLOCKS = 40_000;
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    /** @param data canonical source (clamped spec JSON, or the sanitised file name) */
    public record Loaded(Schematic schematic, String error, String data, String label) {
        static Loaded fail(String msg) { return new Loaded(null, msg, "", ""); }
    }

    public static Path dir() {
        return CompanionMod.configDir().resolve("companionmod").resolve("schematics");
    }

    public static String sanitize(String name) {
        String s = name == null ? "" : name.trim().replaceAll("(?i)\\.nbt$", "");
        s = s.replaceAll("[^A-Za-z0-9_\\- ]", "_").trim();
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    /** File names (without .nbt) in the schematics folder, sorted. */
    public static List<String> listFiles() {
        Path d = dir();
        if (!Files.isDirectory(d)) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(d)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString();
                if (n.toLowerCase().endsWith(".nbt")) out.add(n.substring(0, n.length() - 4));
            });
        } catch (IOException ignored) { }
        Collections.sort(out);
        return out;
    }

    public static boolean hasFile(String name) {
        return !name.isBlank() && Files.isRegularFile(dir().resolve(name + ".nbt"));
    }

    /** The older block-by-block JSON schematics handled by CompanionBuildGoal. */
    public static boolean hasLegacyJson(String name) {
        return !name.isBlank() && Files.isRegularFile(dir().resolve(name + ".json"));
    }

    public static Loaded load(String type, String data) {
        return "file".equals(type) ? fromFile(data) : fromSpec(data);
    }

    public static Loaded fromSpec(String specJson) {
        Optional<BuildSpec> spec = SpecParser.parse(specJson);
        if (spec.isEmpty()) return Loaded.fail("I could not understand that design.");
        BuildSpec s = spec.get();
        Schematic sch = Generators.generate(s, BlkState.POLICY);
        return new Loaded(sch, "", SpecParser.toJson(s), s.name());
    }

    public static Loaded fromFile(String rawName) {
        String name = sanitize(rawName);
        if (name.isEmpty()) return Loaded.fail("Which schematic file?");
        Path base = dir().toAbsolutePath().normalize();
        Path f = base.resolve(name + ".nbt").normalize();
        if (!f.startsWith(base) || !Files.isRegularFile(f)) {
            return Loaded.fail("No schematic named " + name + ". Put " + name + ".nbt in config/companionmod/schematics.");
        }
        try {
            if (Files.size(f) > MAX_FILE_BYTES) return Loaded.fail("That schematic file is too big.");
            Schematic sch = SchematicNbt.read(f);
            if (sch.count() == 0) return Loaded.fail("That schematic has no blocks.");
            if (sch.count() > MAX_BLOCKS) return Loaded.fail("That schematic has more than " + MAX_BLOCKS + " blocks.");
            return new Loaded(sch, "", name, name);
        } catch (IOException | RuntimeException e) {
            return Loaded.fail("I could not read " + name + ".nbt.");
        }
    }

    /**
     * Rebuilds the job's transient plan from its saved source: load, mirror/rotate, apply the saved material
     * swaps, order for placing. Returns false (with job.error set) when the source cannot be loaded any more.
     */
    public static boolean prepare(BuildJob job) {
        Loaded l = load(job.type, job.data);
        if (l.schematic() == null) {
            job.error = l.error();
            return false;
        }
        Schematic t = Transform.apply(l.schematic(), job.turns, job.mirror);
        job.list = BuildPlan.order(t, Substitution.parse(job.subs));
        job.sx = t.sizeX();
        job.sy = t.sizeY();
        job.sz = t.sizeZ();
        job.cells.clear();
        for (BuildPlan.Placement p : job.list) job.cells.add(BlockPos.asLong(job.cx + p.dx(), job.cy + p.dy(), job.cz + p.dz()));
        job.states.clear();
        job.error = "";
        return true;
    }
}
