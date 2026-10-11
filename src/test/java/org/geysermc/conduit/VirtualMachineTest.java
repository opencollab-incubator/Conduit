package org.geysermc.conduit;

import org.geysermc.conduit.fixture.Farm;
import org.geysermc.conduit.fixture.AnimalEvent;
import org.geysermc.conduit.fixture.Palette;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VirtualMachineTest {
    @Conduit("org.geysermc.conduit.fixture.Color")
    public interface Color {
        Integer red();

        Integer green();

        Integer blue();
    }

    @Intercept("org.geysermc.conduit.fixture.ColorRegistry")
    public static class ColorRegistry {
        @Intercept.Capture
        public static CaptureChannel<String> CAPTURE;

        public static void register(String name, Color color) {
            CAPTURE.emit(name + "=" + color.red() + "," + color.green() + "," + color.blue());
        }

        public static void registerAndGet(String name, Color color) {
            register(name, color);
        }
    }

    @Intercept("org.geysermc.conduit.fixture.AnimalApi")
    public static class AnimalApi {
        @Intercept.Capture
        public static CaptureChannel<String> CAPTURE;

        public static void registerAnimal(String name, Supplier<String> sound) {
            CAPTURE.emit(name + "=" + sound.get());
        }
    }

    @Test
    void interceptsEachRegistrationOnce(@TempDir Path directory) throws IOException {
        Path jar = jar(directory.resolve("palette.jar"), Palette.class, org.geysermc.conduit.fixture.ColorRegistry.class,
                org.geysermc.conduit.fixture.Color.class);

        List<String> registered = new ArrayList<>();
        VirtualMachine vm = VirtualMachineFactory.create(ConduitContext.load(jar)).await();
        vm.intercept(ColorRegistry.class);
        vm.capture(ColorRegistry.CAPTURE, registered::add);
        vm.execute();

        assertEquals(List.of("sky=135,206,235", "grass=86,125,70"), registered);
    }

    @Test
    void followsCallsForwardedThroughAFunctionHeldInAField(@TempDir Path directory) throws IOException {
        Path jar = jar(directory.resolve("farm.jar"), org.geysermc.conduit.fixture.AnimalApi.class, AnimalEvent.class, Farm.class);

        List<String> registered = new ArrayList<>();
        VirtualMachine vm = VirtualMachineFactory.create(ConduitContext.load(jar)).await();
        vm.intercept(AnimalApi.class);
        vm.capture(AnimalApi.CAPTURE, registered::add);
        vm.execute();

        // Each call goes through both registrars: the lambda, and the method reference to the API itself.
        assertEquals(List.of("cow=moo", "cow=moo",
                "sheep=baa", "sheep=baa"), registered);
    }

    private static Path jar(Path jar, Class<?>... fixtures) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            for (Class<?> fixture : fixtures) {
                String entry = fixture.getName().replace('.', '/') + ".class";
                jarOut.putNextEntry(new JarEntry(entry));
                try (InputStream in = fixture.getClassLoader().getResourceAsStream(entry)) {
                    in.transferTo(jarOut);
                }
            }
        }
        return jar;
    }
}
