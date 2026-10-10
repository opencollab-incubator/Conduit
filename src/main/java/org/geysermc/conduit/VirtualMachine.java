package org.geysermc.conduit;

import org.geysermc.conduit.asm.CallScanner;
import org.geysermc.conduit.asm.ConduitWrapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Scans every jar in a {@link ConduitContext} and dispatches matching calls to {@link Intercept}
 * handlers.
 *
 * <pre>{@code
 * VirtualMachine vm = VirtualMachineFactory.create(context).await();
 * vm.intercept(ColorRegistry.class);
 * vm.capture(ColorRegistry.CAPTURE, color -> colors.add(color));
 * vm.execute();
 * }</pre>
 *
 * <p>Nothing in the scanned jars is loaded or run. Context jars are read to follow calls into
 * them, but are not scanned themselves.
 */
public class VirtualMachine {
    private final ConduitContext context;
    private final Map<String, Class<?>> handlers = new HashMap<>();
    private final List<Runnable> captures = new ArrayList<>();

    VirtualMachine(ConduitContext context) {
        this.context = context;
    }

    /**
     * Registers intercept handlers. Each must be annotated with {@link Intercept}, and any
     * {@link Intercept.Capture} fields on it are created if they are still null.
     *
     * @param handlerClasses the handlers
     * @return this machine
     * @throws IllegalArgumentException if a class has no {@link Intercept} annotation
     */
    public VirtualMachine intercept(Class<?>... handlerClasses) {
        for (Class<?> handler : handlerClasses) {
            Intercept intercept = handler.getAnnotation(Intercept.class);
            if (intercept == null) {
                throw new IllegalArgumentException(handler.getName() + " has no @Intercept annotation");
            }

            this.handlers.put(intercept.value().replace('.', '/'), handler);
            wireCaptureFields(handler);
        }

        return this;
    }

    /**
     * Listens on a channel for the next {@link #execute()} only. Channels are static and shared
     * between machines, so the listener is removed once the run finishes.
     *
     * @param channel the channel, usually a handler's {@link Intercept.Capture} field
     * @param listener the listener
     * @param <T> the type of item emitted
     * @return this machine
     */
    public <T> VirtualMachine capture(CaptureChannel<T> channel, Consumer<T> listener) {
        channel.listen(listener);
        this.captures.add(() -> channel.silence(listener));
        return this;
    }

    /**
     * Scans the context, invoking handlers as matching calls are found.
     */
    public void execute() {
        CallScanner scanner = new CallScanner(
                this.context.classLoader(),
                (owner, name, _) -> {
                    Class<?> h = this.handlers.get(owner);
                    return h != null && findHandlerMethod(h, name, -1) != null;
                },
                this::dispatch
        );

        try {
            scanner.scanAll();
        } finally {
            this.captures.forEach(Runnable::run);
            this.captures.clear();
        }
    }

    /**
     * Runs {@link #execute()} on the common pool.
     *
     * @return a future completing when the scan finishes
     */
    public CompletableFuture<Void> executeUninterruptibly() {
        return CompletableFuture.runAsync(this::execute);
    }

    private void dispatch(CallScanner.CallMatch match) {
        Class<?> handler = this.handlers.get(match.owner());
        if (handler == null) {
            return;
        }

        Method method = findHandlerMethod(handler, match.name(), match.args().size());
        if (method == null) {
            return;
        }

        Class<?>[] paramTypes = method.getParameterTypes();
        Type[] genericTypes = method.getGenericParameterTypes();
        Object[] realArgs = new Object[match.args().size()];
        for (int i = 0; i < realArgs.length; i++) {
            realArgs[i] = ConduitWrapper.coerce(match.args().get(i), paramTypes[i], genericTypes[i], this.context.classLoader());
        }

        try {
            method.setAccessible(true);
            method.invoke(null, realArgs);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static Method findHandlerMethod(Class<?> handler, String name, int arity) {
        for (Method m : handler.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) {
                continue;
            }

            if (!m.getName().equals(name)) {
                continue;
            }

            if (arity >= 0 && m.getParameterCount() != arity) {
                continue;
            }

            return m;
        }

        return null;
    }

    private static void wireCaptureFields(Class<?> handler) {
        for (Field field : handler.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            if (!field.isAnnotationPresent(Intercept.Capture.class)) {
                continue;
            }

            field.setAccessible(true);
            try {
                if (field.get(null) == null) {
                    field.set(null, CaptureChannel.create());
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to wire @Intercept.Capture field " + field, e);
            }
        }
    }
}
