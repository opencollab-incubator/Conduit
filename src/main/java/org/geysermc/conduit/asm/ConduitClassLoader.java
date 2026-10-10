package org.geysermc.conduit.asm;

import org.jetbrains.annotations.NotNull;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

public class ConduitClassLoader extends URLClassLoader {
    private final List<URL> contextJars = new ArrayList<>();

    public ConduitClassLoader(@NotNull URL @NotNull ... urls) {
        super(urls, ConduitClassLoader.class.getClassLoader());
    }

    public void addJar(@NotNull URL url) {
        this.addJar(url, false);
    }

    public void addJar(@NotNull URL url, boolean contextJar) {
        super.addURL(url);

        if (contextJar) {
            this.contextJars.add(url);
        }
    }

    public boolean isContextJar(URL url) {
        return this.contextJars.contains(url);
    }
}
