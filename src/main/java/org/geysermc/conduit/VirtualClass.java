package org.geysermc.conduit;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A class found by {@link ConduitContext#scanClasses}, by name.
 *
 * <p>Nothing is loaded until {@link #resolve()} or {@link #isAssignableFrom} is called. Those load
 * the class through the context, without initializing it.
 */
public class VirtualClass {
    private final String className;
    private final ConduitContext context;

    /**
     * Refers to a class by its loaded type.
     *
     * @param clazz the class
     * @param context the context to load it from
     */
    public VirtualClass(@NotNull Class<?> clazz, @NotNull ConduitContext context) {
        this.className = clazz.getName();
        this.context = context;
    }

    /**
     * Refers to a class by name, without loading it.
     *
     * @param className the binary name of the class
     * @param context the context to load it from
     */
    public VirtualClass(@NotNull String className, @NotNull ConduitContext context) {
        this.className = className;
        this.context = context;
    }

    /**
     * The binary name of the class, such as {@code com.example.Color}.
     *
     * @return the binary name of the class
     */
    @NotNull
    public String className() {
        return this.className;
    }

    /**
     * Loads the class from the context.
     *
     * @return the class
     * @throws ClassNotFoundException if it can't be loaded
     */
    @NotNull
    public Class<?> resolve() throws ClassNotFoundException {
        return this.context.classLoader().loadClass(this.className);
    }

    /**
     * Loads the class and checks whether it is a subtype of another.
     *
     * @param interfaceName the binary name of the supertype
     * @return whether the class extends or implements it, false if either can't be loaded
     */
    public boolean isAssignableFrom(@NotNull String interfaceName) {
        try {
            Class<?> clazz = this.resolve();
            Class<?> interfaceClass = this.context.classLoader().loadClass(interfaceName);
            return interfaceClass.isAssignableFrom(clazz);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof VirtualClass other)) {
            return false;
        }

        return this.className.equals(other.className) && this.context.equals(other.context);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.className, this.context);
    }
}
