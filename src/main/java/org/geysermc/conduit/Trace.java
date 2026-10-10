package org.geysermc.conduit;

import org.geysermc.conduit.asm.ConduitWrapper;

import java.util.List;

/**
 * The result of evaluating a lambda or method reference, along with every instance call made
 * while doing so.
 *
 * <p>Take a {@code Supplier<Trace>} in an {@link Intercept} handler to receive this instead of a
 * conduit, for when the calls themselves matter as much as the value returned.
 *
 * @param returned the value returned
 * @param calls the instance calls made, in order
 * @param loader the loader the value was read from
 */
public record Trace(Value returned, List<Value.InstanceCall> calls, ClassLoader loader) {

    /**
     * Views the returned value through a conduit.
     *
     * @param conduit the conduit interface
     * @param <T> the conduit type
     * @return the conduit, or null if the value can't be viewed as one
     */
    public <T> T as(Class<T> conduit) {
        Object coerced = ConduitWrapper.coerce(this.returned, conduit, this.loader);
        return conduit.isInstance(coerced) ? conduit.cast(coerced) : null;
    }

    /**
     * Filters the recorded calls down to those made to one method.
     *
     * @param method the method name
     * @return the recorded calls to methods with that name
     */
    public List<Value.InstanceCall> calls(String method) {
        return this.calls.stream().filter(call -> call.methodName().equals(method)).toList();
    }
}
