package org.geysermc.conduit.fixture;

public class Palette {
    static void init() {
        ColorRegistry.register("sky", new Color(135, 206, 235));
        ColorRegistry.registerAndGet("grass", new Color(86, 125, 70));
    }
}
