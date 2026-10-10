package org.geysermc.conduit;

import org.geysermc.conduit.asm.ClassInfo;
import org.geysermc.conduit.asm.ClassScanner;
import org.geysermc.conduit.asm.ConduitClassLoader;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The set of jars Conduit reads from.
 *
 * <p>Jars added with {@link #addJar} are scanned. Context jars, added with {@link #addContextJar},
 * are only read. They let the scanner resolve classes and follow calls into a library without
 * reporting the library's own calls.
 *
 * <pre>{@code
 * ConduitContext context = ConduitContext.load(Path.of("mod.jar"));
 * context.addContextJar(Path.of("library.jar"));
 * }</pre>
 */
public class ConduitContext {
    private final ConduitClassLoader classLoader;

    private ConduitContext() {
        this.classLoader = new ConduitClassLoader();
    }

    /**
     * Adds a jar to scan.
     *
     * @param jarPath the jar
     */
    public void addJar(@NotNull Path jarPath) {
        try {
            this.classLoader.addJar(jarPath.toUri().toURL());
        } catch (Exception e) {
            throw new RuntimeException("Failed to add jar: " + jarPath, e);
        }
    }

    /**
     * Adds jars to scan.
     *
     * @param jarUrl the jars
     */
    public void addJar(URL @NotNull ... jarUrl) {
        for (URL url : jarUrl) {
            this.classLoader.addJar(url);
        }
    }

    /**
     * Adds a jar to read classes from without scanning it.
     *
     * @param jarPath the jar
     */
    public void addContextJar(@NotNull Path jarPath) {
        try {
            this.classLoader.addJar(jarPath.toUri().toURL(), true);
        } catch (Exception e) {
            throw new RuntimeException("Failed to add context jar: " + jarPath, e);
        }
    }

    /**
     * Adds every jar directly inside a directory of another jar, such as {@code META-INF/jars},
     * to scan. Nested directories are not searched.
     *
     * @param outerJar the jar containing the others
     * @param innerPath the directory inside it
     */
    public void addNestedJars(@NotNull Path outerJar, @NotNull String innerPath) {
        String prefix = innerPath.endsWith("/") ? innerPath : innerPath + "/";
        try (JarFile jar = new JarFile(outerJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }

                String name = entry.getName();
                if (!name.startsWith(prefix) || !name.endsWith(".jar")) {
                    continue;
                }
                if (name.indexOf('/', prefix.length()) >= 0) {
                    continue;
                }

                Path temp = Files.createTempFile("conduit-nested-", ".jar");
                temp.toFile().deleteOnExit();
                try (InputStream in = jar.getInputStream(entry)) {
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                }

                this.classLoader.addJar(temp.toUri().toURL());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract nested jars from " + outerJar, e);
        }
    }

    /**
     * Lists every class in the context matching a filter, read from the class files alone.
     *
     * @param filter the filter
     * @return the matching classes
     */
    public List<VirtualClass> scanClasses(@NotNull ClassFilter filter) {
        List<ClassInfo> infos = ClassScanner.scanAll(this.classLoader);
        Function<String, ClassInfo> lookup = ClassScanner.index(infos);
        List<VirtualClass> virtualClasses = new ArrayList<>();
        for (ClassInfo info : infos) {
            if (filter.test(info, lookup)) {
                virtualClasses.add(new VirtualClass(info.binaryName(), this));
            }
        }

        return virtualClasses;
    }

    /**
     * The loader over every jar in the context, used to read class files. Loading a class through it does not run the scanner.
     *
     * @return the loader over every jar in the context
     */
    public ConduitClassLoader classLoader() {
        return this.classLoader;
    }

    /**
     * Creates a context that scans the given jars.
     *
     * @param jarPath the jars to scan
     * @return a new context
     */
    public static ConduitContext load(Path @NotNull ... jarPath) {
        ConduitContext context = new ConduitContext();
        for (Path path : jarPath) {
            context.addJar(path);
        }

        return context;
    }

    /**
     * Creates a context that scans the given jars.
     *
     * @param jarUrl the jars to scan
     * @return a new context
     */
    public static ConduitContext load(URL @NotNull ... jarUrl) {
        ConduitContext context = new ConduitContext();
        context.addJar(jarUrl);
        return context;
    }
}
