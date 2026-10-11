package org.geysermc.conduit.fixture;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

public record AnimalEvent(BiConsumer<String, Supplier<String>> registrar) {
    public void register(String name, Supplier<String> sound) {
        this.registrar.accept(name, sound);
    }
}
