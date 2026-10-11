package org.geysermc.conduit.asm;

import java.util.Set;
import java.util.function.Predicate;

public record Opaque(Set<String> classes, Set<String> packages) implements Predicate<String> {
    public static final Opaque NONE = new Opaque(Set.of(), Set.of());

    public static Opaque of(String... names) {
        Set<String> classes = new java.util.HashSet<>();
        Set<String> packages = new java.util.HashSet<>();
        for (String name : names) {
            String internal = name.replace('.', '/');
            (internal.endsWith("/") ? packages : classes).add(internal);
        }

        return new Opaque(Set.copyOf(classes), Set.copyOf(packages));
    }

    @Override
    public boolean test(String owner) {
        if (this.classes.contains(owner)) {
            return true;
        }

        for (String prefix : this.packages) {
            if (owner.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
