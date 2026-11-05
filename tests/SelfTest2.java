import com.example.companionmod.build.*;
import com.google.gson.*;
import java.util.*;

public class SelfTest2 {
    public static void main(String[] a) {
        // 1. schema is valid JSON
        JsonObject schema = JsonParser.parseString(BuildSpecSchema.SCHEMA).getAsJsonObject();
        System.out.println("schema ok, props=" + schema.getAsJsonObject("properties").keySet());

        // 2. parser: clean, fenced, junk, garbage
        String clean = "{\"type\":\"house\",\"name\":\"cabin\",\"width\":7,\"depth\":7,\"height\":10,\"floors\":1,\"roof\":\"gable\",\"shape\":\"square\",\"materials\":{\"wall\":\"spruce_planks\",\"roof\":\"dark_oak_planks\"}}";
        System.out.println("clean  -> " + SpecParser.parse(clean).orElse(null));
        System.out.println("fenced -> " + SpecParser.parse("Sure!\n```json\n" + clean + "\n```").map(BuildSpec::name).orElse("EMPTY"));
        System.out.println("junk   -> " + SpecParser.parse("{\"type\":\"castle\",\"name\":\"big keep\",\"width\":\"12\",\"floors\":null,\"materials\":[1,2]}").orElse(null));
        System.out.println("garbage-> " + SpecParser.parse("I cannot do that").isPresent() + " / " + SpecParser.parse("{broken").isPresent());
        for (String t : List.of("a small wooden cabin", "a tall round stone watchtower", "a big two story brick mansion with a dark oak roof", "modern flat roof house"))
            System.out.println("heur '" + t + "' -> " + SpecParser.heuristic(t));
        System.out.println("fallback -> " + SpecParser.parseOrHeuristic("nonsense", "tiny sandstone tower").type());

        // 3. rotation: 4 turns == identity; facing rotates; walking path survives
        BuildSpec sp = new BuildSpec("house","m",11,9,16,2,"gable","square",Map.of());
        Schematic s = Generators.generate(sp, BlockPolicy.ANY);
        Schematic r4 = Transform.rotate(s, 4), r1 = Transform.rotate(s, 1), r2 = Transform.rotate(Transform.rotate(s,1),1);
        System.out.println("rot x4 identical: " + s.blocks().equals(r4.blocks()) + "  rot(1)+rot(1)==rot(2): " + r2.blocks().equals(Transform.rotate(s,2).blocks()));
        System.out.printf("size %dx%dx%d -> rot1 %dx%dx%d%n", s.sizeX(), s.sizeY(), s.sizeZ(), r1.sizeX(), r1.sizeY(), r1.sizeZ());
        for (var e : r1.blocks().entrySet()) if (e.getValue().path().endsWith("_door") && e.getValue().props().get("half").equals("lower"))
            System.out.println("door after 1 CW turn at " + e.getKey() + " " + e.getValue());
        Schematic m = Transform.mirrorX(s);
        System.out.println("mirror x2 identical: " + s.blocks().equals(Transform.mirrorX(m).blocks()));
        // pane connections after rotation
        for (var e : r1.blocks().entrySet()) if (e.getValue().path().equals("glass_pane")) { System.out.println("pane after rot: " + e.getValue() + " at " + e.getKey()); break; }
        System.out.println("BOM keys: " + s.itemCounts().keySet());
    }
}
