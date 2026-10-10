package org.geysermc.conduit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Hands results from an {@link Intercept} handler to whoever is listening.
 *
 * <p>Declare one as a static field marked {@link Intercept.Capture} and it is created when the
 * handler is registered. Listen through {@link VirtualMachine#capture}, which scopes the listener
 * to a single run.
 *
 * @param <T> the type of item emitted
 */
public class CaptureChannel<T> {
    private final List<Consumer<T>> listeners = new ArrayList<>();

    private CaptureChannel() {
    }

    /**
     * Sends an item to every listener. A listener that throws doesn't stop the others.
     *
     * @param item the item
     */
    public void emit(T item) {
        for (Consumer<T> listener : this.listeners) {
            try {
                listener.accept(item);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Adds a listener until it is {@link #silence silenced}.
     *
     * @param listener the listener
     */
    public void listen(Consumer<T> listener) {
        this.listeners.add(listener);
    }

    /**
     * Removes a listener.
     *
     * @param listener the listener
     * @return whether it was registered
     */
    public boolean silence(Consumer<T> listener) {
        return this.listeners.remove(listener);
    }

    /**
     * Creates an empty channel.
     *
     * @param <T> the type of item emitted
     * @return a new channel with no listeners
     */
    public static <T> CaptureChannel<T> create() {
        return new CaptureChannel<>();
    }
}
