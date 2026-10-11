package org.geysermc.conduit.asm;

import org.geysermc.conduit.Conduit;
import org.geysermc.conduit.ConduitContext;
import org.geysermc.conduit.Conduits;
import org.geysermc.conduit.Value;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryEntryTest {
    @Conduit("org.geysermc.conduit.asm.FakeProduct")
    interface Product {
        @Conduit.Bind(arg = 0, chain = "<init>", descriptor = "(Ljava/lang/String;)V")
        @Conduit.Bind(arg = 0, chain = "of", descriptor = "(Ljava/lang/String;)Lorg/geysermc/conduit/asm/FakeProduct$Builder;")
        String name();
    }

    @Conduit("org.geysermc.conduit.asm.FakeStateRendererBase")
    interface Renderer {
        List<String> getTextureLocation();
    }

    private final ClassLoader loader = context().classLoader();

    @Test
    void registryEntryResolvesToWhatItsSupplierBuilds() {
        Value supplier = new Value.Lambda("get", "()Ljava/util/function/Supplier;", "org/geysermc/conduit/asm/FakeRegistry",
                "product", "()Lorg/geysermc/conduit/asm/FakeProduct;", true, List.of());
        Value registration = new Value.InstanceCall("org/geysermc/conduit/asm/FakeRegistry", "register",
                new Value.Constructed("org/geysermc/conduit/asm/FakeRegistry", List.of()), List.of(Value.literal("sheep"), supplier));
        Value entry = new Value.InstanceCall("org/geysermc/conduit/asm/FakeEntry", "get", registration, List.of());

        Product product = (Product) ConduitWrapper.coerce(entry, Product.class, this.loader);
        assertEquals("sheep", product.name());
    }

    @Test
    void typeArgumentStandsInForATypeVariable() {
        Value value = new Value.Constructed("org/geysermc/conduit/asm/FakeGenericRenderer", List.of());
        Renderer renderer = (Renderer) ConduitWrapper.coerce(value, Renderer.class, this.loader);
        assertTrue(renderer.getTextureLocation().stream().noneMatch(texture -> texture.startsWith("mod:")));

        Renderer sheep = Conduits.withTypeArgument(renderer, "org.geysermc.conduit.asm.FakeSheep");
        assertEquals(List.of("mod:textures/models/sheep.png"), sheep.getTextureLocation());
    }

    private static ConduitContext context() {
        try {
            return ConduitContext.load(Path.of(FakeRegistry.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
