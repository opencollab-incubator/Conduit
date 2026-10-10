package org.geysermc.conduit.asm;

import org.geysermc.conduit.Conduit;
import org.geysermc.conduit.ConduitContext;
import org.geysermc.conduit.Value;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendererAccessorTest {
    @Conduit("org.geysermc.conduit.asm.FakeRenderer")
    interface Renderer {
        String getTextureLocation();
    }

    @Conduit("org.geysermc.conduit.asm.FakeRenderer")
    interface AllTextures {
        List<String> getTextureLocation();
    }

    @Conduit("org.geysermc.conduit.asm.FakeStateRendererBase")
    interface StateTextures {
        List<String> getTextureLocation();
    }

    @Conduit("java.lang.Object")
    interface NoTextures {
        List<String> getTextureLocation();
    }

    private final ClassLoader loader = context().classLoader();

    @Test
    void staticFieldReturnedFromMethodWithArguments() {
        assertEquals("mod:textures/entity/boar.png", this.view("FakeRenderer", Renderer.class).getTextureLocation());
    }

    @Test
    void inheritedAccessor() {
        assertEquals("mod:textures/entity/boar.png", this.view("FakeInheritedRenderer", Renderer.class).getTextureLocation());
    }

    @Test
    void everyBranch() {
        List<String> all = this.view("FakeVariantRenderer", AllTextures.class).getTextureLocation();
        assertEquals(3, all.size(), all::toString);
        assertTrue(all.contains("mod:textures/entity/bird_blue.png"), all::toString);
    }

    @Test
    void undeclaredAccessorIsEmpty() {
        Object proxy = ConduitWrapper.coerce(new Value.Constructed("java/lang/Object", List.of()), NoTextures.class, this.loader);
        assertEquals(List.of(), ((NoTextures) proxy).getTextureLocation());
    }

    @Test
    void fieldAssignedElsewhereInHierarchy() {
        assertEquals(List.of("mod:textures/models/boar.png"),
                this.view("FakeStateRenderer", StateTextures.class).getTextureLocation());
    }

    private <T> T view(String renderer, Class<T> conduit) {
        Value value = new Value.Constructed("org/geysermc/conduit/asm/" + renderer, List.of());
        return conduit.cast(ConduitWrapper.coerce(value, conduit, this.loader));
    }

    private static ConduitContext context() {
        try {
            return ConduitContext.load(Path.of(FakeRenderer.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
