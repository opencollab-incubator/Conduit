package org.geysermc.conduit.fixture;

public class ColorRegistry {
    public static void register(String name, Color color) {
    }

    public static Color registerAndGet(String name, Color color) {
        register(name, color);
        return color;
    }
}
