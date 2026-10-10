package org.geysermc.conduit.asm;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record ClassInfo(@NotNull String name, @Nullable String superName, @NotNull List<String> interfaces) {
    public @NotNull String binaryName() {
        return this.name.replace('/', '.');
    }
}
