package org.geysermc.conduit.fixture;

public final class Farm {
    private Farm() {
    }

    public static void open() {
        fire(new AnimalEvent((name, sound) -> AnimalApi.registerAnimal(name, sound::get)));
        fire(new AnimalEvent(AnimalApi::registerAnimal));
    }

    public static void registerAnimals(AnimalEvent event) {
        event.register("cow", Farm::moo);
        event.register("sheep", () -> "baa");
    }

    private static void fire(AnimalEvent event) {
    }

    private static String moo() {
        return "moo";
    }
}
