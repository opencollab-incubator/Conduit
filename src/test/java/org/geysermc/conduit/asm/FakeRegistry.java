package org.geysermc.conduit.asm;

import java.util.function.Supplier;

public class FakeRegistry {
    public <T> FakeEntry<T> register(String name, Supplier<T> supplier) {
        return new FakeEntry<>(name, supplier);
    }

    static FakeProduct product() {
        return FakeProduct.Builder.of("sheep").build();
    }
}

class FakeEntry<T> {
    private final String name;
    private final Supplier<T> supplier;

    FakeEntry(String name, Supplier<T> supplier) {
        this.name = name;
        this.supplier = supplier;
    }

    public T get() {
        return null;
    }
}

class FakeProduct {
    private final String name;

    FakeProduct(String name) {
        this.name = name;
    }

    static class Builder {
        private final String name;
        private int size;

        private Builder(String name) {
            this.name = name;
        }

        Builder size(int size) {
            this.size = size;
            return this;
        }

        static Builder of(String name) {
            return new Builder(name);
        }

        FakeProduct build() {
            return new FakeProduct(this.name);
        }
    }
}

class FakeGenericRenderer<T extends FakeEntity> extends FakeStateRendererBase<T> {
}

class FakeSheep extends FakeEntity {
    @Override
    public String getTexture() {
        return modelTexture("sheep.png");
    }
}

class FakeHolder {
    private final FakeEntry<String> entry;

    FakeHolder(FakeEntry<String> entry) {
        this.entry = entry;
    }
}

final class FakeKey {
    private static final java.util.concurrent.ConcurrentMap<Intern, FakeKey> VALUES = new java.util.concurrent.ConcurrentHashMap<>();

    private final String registry;
    private final String id;

    private FakeKey(String registry, String id) {
        this.registry = registry;
        this.id = id;
    }

    static FakeKey create(String registry, String id) {
        return VALUES.computeIfAbsent(new Intern(registry, id), key -> new FakeKey(key.registry, key.id));
    }

    private record Intern(String registry, String id) {
    }
}
