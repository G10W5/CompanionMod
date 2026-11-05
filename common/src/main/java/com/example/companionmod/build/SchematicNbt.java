package com.example.companionmod.build;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Vanilla structure-file format (the same format Create's .nbt schematics use).
 * Block-entity data (chest contents, signs) is intentionally dropped on import: blocks only.
 *
 * 26.1-CHECK: this is the only class in the package that touches Minecraft. The tag getters below use the
 * Optional-free "getXOr / getXOrEmpty" style introduced in 1.21.5; adjust names if 26.1.2 differs.
 */
public final class SchematicNbt {
    private SchematicNbt() {}

    public static int currentDataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();   // 26.1-CHECK
    }

    // ---- export ------------------------------------------------------------------

    public static CompoundTag toNbt(Schematic schem) {
        Schematic s = schem.normalized();
        CompoundTag root = new CompoundTag();

        ListTag size = new ListTag();
        size.add(IntTag.valueOf(s.sizeX()));
        size.add(IntTag.valueOf(s.sizeY()));
        size.add(IntTag.valueOf(s.sizeZ()));
        root.put("size", size);

        Map<Blk, Integer> index = new LinkedHashMap<>();
        ListTag palette = new ListTag();
        ListTag blocks = new ListTag();
        for (var e : s.buildOrder()) {                       // attachments last, bottom-up: Create-friendly order
            int state = index.computeIfAbsent(e.getValue(), b -> {
                CompoundTag entry = new CompoundTag();
                entry.putString("Name", b.id());
                if (!b.props().isEmpty()) {
                    CompoundTag props = new CompoundTag();
                    b.props().forEach(props::putString);
                    entry.put("Properties", props);
                }
                palette.add(entry);
                return palette.size() - 1;
            });
            CompoundTag bt = new CompoundTag();
            ListTag pos = new ListTag();
            pos.add(IntTag.valueOf(e.getKey().x()));
            pos.add(IntTag.valueOf(e.getKey().y()));
            pos.add(IntTag.valueOf(e.getKey().z()));
            bt.put("pos", pos);
            bt.putInt("state", state);
            blocks.add(bt);
        }
        root.put("palette", palette);
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        root.putInt("DataVersion", currentDataVersion());
        return root;
    }

    public static void write(Schematic s, Path file) throws IOException {
        NbtIo.writeCompressed(toNbt(s), file);
    }

    // ---- import ------------------------------------------------------------------

    public static Schematic read(Path file) throws IOException {
        return fromNbt(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
    }

    public static Schematic fromNbt(CompoundTag root) {
        ListTag palTag = root.getListOrEmpty("palette");
        if (palTag.isEmpty()) {                               // vanilla shipwreck/igloo style: "palettes"
            ListTag all = root.getListOrEmpty("palettes");
            if (!all.isEmpty()) palTag = all.getListOrEmpty(0);
        }
        Blk[] palette = new Blk[palTag.size()];
        for (int i = 0; i < palette.length; i++) {
            CompoundTag e = palTag.getCompoundOrEmpty(i);
            Map<String, String> props = new LinkedHashMap<>();
            CompoundTag p = e.getCompoundOrEmpty("Properties");
            for (String k : p.keySet()) props.put(k, p.getStringOr(k, ""));
            palette[i] = new Blk(e.getStringOr("Name", "minecraft:air"), props);
        }
        Schematic out = new Schematic();
        ListTag blocks = root.getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompoundOrEmpty(i);
            ListTag pos = b.getListOrEmpty("pos");
            int st = b.getIntOr("state", -1);
            if (st < 0 || st >= palette.length || pos.size() < 3) continue;
            out.set(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0), palette[st]);
        }
        return out.normalized();
    }
}
