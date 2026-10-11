package org.geysermc.conduit;

import org.geysermc.conduit.asm.ConduitWrapper;

/**
 * Utilities for working with conduit instances.
 */
public final class Conduits {
    private Conduits() {
    }

    /**
     * Views a value through a conduit, such as one found in a {@link Summary}.
     *
     * @param value the value
     * @param conduit the conduit interface
     * @param context the context the value was read from
     * @param <T> the conduit type
     * @return the conduit, or null if the value can't be viewed as one
     */
    public static <T> T as(Value value, Class<T> conduit, ConduitContext context) {
        Object coerced = ConduitWrapper.coerce(value, conduit, context.classLoader());
        return conduit.isInstance(coerced) ? conduit.cast(coerced) : null;
    }

    /**
     * Views a conduit with a known class standing in for the type variable its target is declared
     * with.
     *
     * <p>A generic class shared between several types, such as a {@code Handler<T>} registered once
     * per type, can't say which type a value belongs to, so anything it reads from {@code T} stays
     * unresolved. The code registering it usually does know, and this passes that on.
     *
     * <pre>{@code
     * Handler handler = Conduits.withTypeArgument(registration.handler(), "com.example.Sheep");
     * List<String> textures = handler.texture();
     * }</pre>
     *
     * <p>A type argument the class declares itself, such as {@code Handler<Sheep>}, takes precedence.
     *
     * @param conduit the conduit
     * @param className the binary name of the class standing in for the type variable
     * @param <T> the conduit type
     * @return a conduit over the same value, or {@code conduit} unchanged if it is null or not a conduit
     */
    @SuppressWarnings("unchecked")
    public static <T> T withTypeArgument(T conduit, String className) {
        return (T) ConduitWrapper.withTypeArgument(conduit, className);
    }
}
