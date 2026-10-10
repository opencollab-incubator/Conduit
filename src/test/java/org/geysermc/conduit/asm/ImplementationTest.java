package org.geysermc.conduit.asm;

import org.geysermc.conduit.Conduit;
import org.geysermc.conduit.Value;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ImplementationTest {
    @Conduit("com.example.RendererFactory")
    interface Factory {
        @Conduit.Implementation
        String implementation();
    }

    @Test
    void constructorReference() {
        Value lambda = new Value.Lambda("create", "()Ljava/lang/Object;", "com/example/Renderer", "<init>",
                "()V", false, List.of());
        assertEquals("com.example.Renderer", of(lambda));
    }

    @Test
    void constructed() {
        assertEquals("com.example.Renderer", of(new Value.Constructed("com/example/Renderer", List.of())));
    }

    @Test
    void otherValuesHaveNoImplementation() {
        assertNull(of(new Value.Field("com/example/Registry", "RENDERERS")));
    }

    private static String of(Value value) {
        return ((Factory) ConduitWrapper.coerce(value, Factory.class, null)).implementation();
    }
}
