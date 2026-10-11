package org.geysermc.conduit.asm;

import org.geysermc.conduit.Conduit;
import org.geysermc.conduit.Trace;
import org.geysermc.conduit.Value;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConduitWrapperTest {
    private static final String LAYER = "com/example/Layer";
    private static final String MESH = "com/example/Mesh";
    private static final String MATERIAL = "com/example/Material";
    private static final String PART = "com/example/Part";
    private static final String CUBES = "com/example/Cubes";
    private static final String POSE = "com/example/Pose";

    private static final Value MESH_VALUE = new Value.Constructed(MESH, List.of());
    private static final Value MATERIAL_VALUE = new Value.Constructed(MATERIAL, List.of(Value.literal(64), Value.literal(32)));

    @Conduit("com.example.Layer")
    interface Layer {
        @Conduit.Bind(arg = 0, descriptor = "(Lcom/example/Mesh;Lcom/example/Material;)V", producer = "<init>")
        @Conduit.Bind(arg = 0, descriptor = "(Lcom/example/Mesh;II)Lcom/example/Layer;", producer = "create")
        Mesh mesh();

        @Conduit.Bind(arg = 1, descriptor = "(Lcom/example/Mesh;Lcom/example/Material;)V", producer = "<init>")
        @Conduit.Bind(arg = 1, span = 2, descriptor = "(Lcom/example/Mesh;II)Lcom/example/Layer;", producer = "create")
        Material material();
    }

    @Conduit("com.example.Material")
    interface Material {
        @Conduit.Bind(arg = 0, descriptor = "(II)V", producer = "<init>")
        Integer width();

        @Conduit.Bind(arg = 1, descriptor = "(II)V", producer = "<init>")
        Integer height();
    }

    @Conduit("com.example.Mesh")
    interface Mesh {
        @Conduit.Bind(calls = "getRoot")
        Part root();
    }

    @Conduit("com.example.Part")
    interface Part {
        @Conduit.Bind(arg = 2, descriptor = "(Ljava/lang/String;Lcom/example/Cubes;Lcom/example/Pose;)Lcom/example/Part;", producer = "addChild")
        Pose pose();

        @Conduit.Bind(from = 1, chain = "addBox")
        List<Cube> cubes();

        @Conduit.Bind(key = 0, calls = "addChild")
        Map<String, Part> children();
    }

    @Conduit("com.example.Pose")
    interface Pose {
        @Conduit.Bind(arg = 0, descriptor = "(FFF)Lcom/example/Pose;", producer = "offset")
        Float x();

        @Conduit.Bind(arg = 0, descriptor = "(FFF)Lcom/example/Pose;", producer = "rotation")
        Float xRot();
    }

    @Conduit("com.example.Cube")
    interface Cube {
        @Conduit.Bind(arg = 0, chain = "texOffs")
        Integer u();

        @Conduit.Bind(arg = 3, span = 3, descriptor = "(FFFFFF)Lcom/example/Cubes;", producer = "addBox")
        Vector size();

        @Conduit.Bind(chain = "mirror")
        Boolean mirror();
    }

    @Conduit("com.example.Cube")
    interface NamedCube {
        @Conduit.Bind(arg = 0, span = 3, descriptor = "(FFFFFFZ)Lcom/example/Cubes;", producer = "addBox")
        @Conduit.Bind(arg = 1, span = 3, descriptor = "(Ljava/lang/String;FFFFFF)Lcom/example/Cubes;", producer = "addBox")
        Vector origin();
    }

    @Conduit("com.example.Vector")
    interface Vector {
        @Conduit.Bind(arg = 0, descriptor = "(FFF)V", producer = "<init>")
        Float x();

        @Conduit.Bind(arg = 2, descriptor = "(FFF)V", producer = "<init>")
        Float z();
    }

    @Test
    void constructorArguments() {
        Material material = layer(new Value.Constructed(LAYER, List.of(MESH_VALUE, MATERIAL_VALUE))).material();
        assertEquals(64, material.width());
        assertEquals(32, material.height());
    }

    @Test
    void factoryArgumentsGroupedBySpan() {
        Material material = layer(factory()).material();
        assertEquals(64, material.width());
        assertEquals(32, material.height());
    }

    @Test
    void unknownOverloadDoesNotResolve() {
        assertNull(layer(new Value.StaticCall(LAYER, "create", List.of(MESH_VALUE))).material());
    }

    @Test
    void producerTellsSameShapedFactoriesApart() {
        Pose offset = pose("offset");
        assertEquals(1f, offset.x());
        assertNull(offset.xRot());

        Pose rotation = pose("rotation");
        assertNull(rotation.x());
        assertEquals(1f, rotation.xRot());
    }

    @Test
    void rewrappedSupplier() {
        Value original = new Value.Lambda("get", "()Ljava/lang/Object;", LAYER, "<init>", "()V", false,
                List.of(MESH_VALUE, MATERIAL_VALUE));
        Value rewrapped = new Value.Lambda("create", "()Ljava/lang/Object;",
                "java/util/function/Supplier", "get", "()Ljava/lang/Object;", false, List.of(original));

        Object supplied = ((Supplier<?>) ConduitWrapper.coerce(rewrapped, Supplier.class, null)).get();
        assertEquals(LAYER, assertInstanceOf(Value.Constructed.class, supplied).className());
    }

    @Test
    void supplierOfACallThatCantBeFollowedReturnsTheCall() throws ReflectiveOperationException {
        Value reference = new Value.Lambda("get", "()Ljava/lang/Object;", "com/example/Missing", "create", "()Lcom/example/Layer;",
                true, List.of());
        java.lang.reflect.Type supplierOfTrace = ConduitWrapperTest.class.getDeclaredField("traceSupplier").getGenericType();

        Object supplier = ConduitWrapper.coerce(reference, Supplier.class, supplierOfTrace, new ConduitClassLoader());
        Trace trace = assertInstanceOf(Trace.class, ((Supplier<?>) supplier).get());
        assertEquals("Missing.create()", String.valueOf(trace.returned()));
    }

    @SuppressWarnings("unused")
    private Supplier<Trace> traceSupplier;

    @Test
    void overloadsWithTheSameArgumentCountAreToldApartByConstants() {
        Value named = new Value.InstanceCall(CUBES, "addBox", new Value.StaticCall(CUBES, "create", List.of()), List.of(
                Value.literal("box"), Value.literal(-1f), Value.literal(-2f), Value.literal(-3f),
                Value.literal(1f), Value.literal(2f), Value.literal(3f)));

        NamedCube cube = (NamedCube) ConduitWrapper.coerce(named, NamedCube.class, null);
        assertEquals(-1f, cube.origin().x());
    }

    @Test
    void chainedSetterNearestTheValueApplies() {
        Value first = new Value.InstanceCall(CUBES, "addBox", new Value.InstanceCall(CUBES, "texOffs",
                new Value.StaticCall(CUBES, "create", List.of()), List.of(Value.literal(0), Value.literal(0))), List.of(
                Value.literal(-4f), Value.literal(-4f), Value.literal(-6f), Value.literal(8f), Value.literal(8f), Value.literal(6f)));
        Value second = new Value.InstanceCall(CUBES, "addBox", new Value.InstanceCall(CUBES, "texOffs", first,
                List.of(Value.literal(22), Value.literal(0))), List.of(
                Value.literal(-5f), Value.literal(-5f), Value.literal(-4f), Value.literal(1f), Value.literal(3f), Value.literal(1f)));

        assertEquals(22, ((Cube) ConduitWrapper.coerce(second, Cube.class, null)).u());
        assertEquals(0, ((Cube) ConduitWrapper.coerce(first, Cube.class, null)).u());
    }

    @Test
    void recordedCalls() {
        Value.InstanceCall root = new Value.InstanceCall(MESH, "getRoot", MESH_VALUE, List.of());
        Value.InstanceCall texOffs = new Value.InstanceCall(CUBES, "texOffs",
                new Value.StaticCall(CUBES, "create", List.of()), List.of(Value.literal(0), Value.literal(16)));
        Value.InstanceCall mirror = new Value.InstanceCall(CUBES, "mirror", texOffs, List.of());
        Value.InstanceCall addBox = new Value.InstanceCall(CUBES, "addBox", mirror, List.of(
                Value.literal(-2f), Value.literal(-1f), Value.literal(-4f),
                Value.literal(4f), Value.literal(4f), Value.literal(6f)));
        Value.InstanceCall head = new Value.InstanceCall(PART, "addChild", root, List.of(
                Value.literal("head"), addBox, offset(0f, 15f, -4f)));
        Value.InstanceCall ear = new Value.InstanceCall(PART, "addChild", head, List.of(
                Value.literal("ear"), addBox, offset(0f, 0f, 0f)));

        Layer layer = (Layer) ConduitWrapper.coerce(factory(), Layer.class, Layer.class, null,
                List.of(root, texOffs, mirror, addBox, head, ear));

        Map<String, Part> parts = layer.mesh().root().children();
        assertEquals(Set.of("head"), parts.keySet());

        Part part = parts.get("head");
        assertEquals(0f, part.pose().x());
        assertEquals(Set.of("ear"), part.children().keySet());

        List<Cube> cubes = part.cubes();
        assertEquals(1, cubes.size());

        Cube cube = cubes.getFirst();
        assertEquals(0, cube.u());
        assertEquals(4f, cube.size().x());
        assertEquals(6f, cube.size().z());
        assertTrue(cube.mirror());
    }

    @Test
    void childrenAddedThroughARefetchedPart() {
        Value.InstanceCall root = new Value.InstanceCall(MESH, "getRoot", MESH_VALUE, List.of());
        Value.InstanceCall body = new Value.InstanceCall(PART, "addChild", root, List.of(
                Value.literal("body"), new Value.StaticCall(CUBES, "create", List.of()), offset(0f, 24f, 0f)));
        Value.InstanceCall refetched = new Value.InstanceCall(PART, "getChild", root, List.of(Value.literal("body")));
        Value.InstanceCall tail = new Value.InstanceCall(PART, "addChild", refetched, List.of(
                Value.literal("tail"), new Value.StaticCall(CUBES, "create", List.of()), offset(0f, 0f, 4f)));

        Layer layer = (Layer) ConduitWrapper.coerce(factory(), Layer.class, Layer.class, null, List.of(root, body, refetched, tail));

        assertEquals(Set.of("tail"), layer.mesh().root().children().get("body").children().keySet());
    }

    private static Value factory() {
        return new Value.StaticCall(LAYER, "create", List.of(MESH_VALUE, Value.literal(64), Value.literal(32)));
    }

    private static Value offset(float x, float y, float z) {
        return new Value.StaticCall(POSE, "offset", List.of(Value.literal(x), Value.literal(y), Value.literal(z)));
    }

    private static Pose pose(String factory) {
        Value value = new Value.StaticCall(POSE, factory, List.of(Value.literal(1f), Value.literal(2f), Value.literal(3f)));
        return (Pose) ConduitWrapper.coerce(value, Pose.class, null);
    }

    private static Layer layer(Value value) {
        return (Layer) ConduitWrapper.coerce(value, Layer.class, null);
    }
}
