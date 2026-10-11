package org.geysermc.conduit.generate;

import org.geysermc.conduit.ConduitContext;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConduitGeneratorTest {
    @Test
    void builderStateIsBoundThroughItsStaticFactory() throws Exception {
        ConduitGenerator generator = new ConduitGenerator(context());
        Path directory = Files.createTempDirectory("conduit-generated");
        generator.generateAll(directory, "generated", List.of(
                "org.geysermc.conduit.asm.FakeProduct", "org.geysermc.conduit.asm.FakeProduct$Builder"));

        String product = Files.readString(directory.resolve("FakeProduct.java"));
        assertTrue(product.contains("@Conduit.Bind(arg = 0, chain = \"of\""), product);
    }

    @Test
    void internedValuesAreBoundThroughTheirFactory() {
        String key = new ConduitGenerator(context()).generate("org.geysermc.conduit.asm.FakeKey", "generated");
        assertTrue(key.contains("@Conduit.Bind(arg = 1, descriptor = \"(Ljava/lang/String;Ljava/lang/String;)Lorg/geysermc/conduit/asm/FakeKey;\", producer = \"create\")"), key);
    }

    @Test
    void onlyJdkTypesKeepTypeArguments() throws Exception {
        Path directory = Files.createTempDirectory("conduit-generated");
        new ConduitGenerator(context()).generateAll(directory, "generated", List.of(
                "org.geysermc.conduit.asm.FakeHolder", "org.geysermc.conduit.asm.FakeEntry"));

        String holder = Files.readString(directory.resolve("FakeHolder.java"));
        assertTrue(holder.contains("FakeEntry entry();"), holder);

        String entry = Files.readString(directory.resolve("FakeEntry.java"));
        assertTrue(entry.contains("Supplier<Object> supplier();"), entry);
    }

    private static ConduitContext context() {
        try {
            return ConduitContext.load(Path.of(ConduitGeneratorTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
