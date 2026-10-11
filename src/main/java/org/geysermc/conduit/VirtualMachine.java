package org.geysermc.conduit;

import org.geysermc.conduit.asm.CallScanner;
import org.geysermc.conduit.asm.ConduitWrapper;
import org.geysermc.conduit.asm.Opaque;
import org.geysermc.conduit.asm.Summarizer;
import org.jetbrains.annotations.Nullable;

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
    private Opaque opaque = Opaque.NONE;

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
     * Stops summaries and traces from following code into some classes, keeping calls to them as
     * calls. Useful when the context contains an API whose calls should be read, not its internals.
     *
     * @param names binary or internal class names, or package prefixes ending in a dot or slash
     * @return this machine
     */
    public VirtualMachine opaque(String... names) {
        this.opaque = Opaque.of(names);
        return this;
    }

    /**
     * Works out what a method does without running it: the fields it leaves assigned, the calls
     * it makes, and what it returns, all in terms of its inputs. Calls on {@code this} and static
     * calls into the context are followed; anything else is kept as a call.
     *
     * <pre>{@code
     * Summary summary = vm.summarize("com.example.Dial", "turn", null);
     * Value angle = summary.assigned(new Value.This(), "angle");
     * }</pre>
     *
     * @param className the binary or internal name of the class to start the lookup from
     * @param methodName the method, found on the class or inherited
     * @param descriptor the method descriptor, or null for the first non-bridge method of that name
     * @return the summary, or null if the method can't be found or is too large to follow
     */
    @Nullable
    public Summary summarize(String className, String methodName, @Nullable String descriptor) {
        return new Summarizer(this.context.classLoader(), this.opaque).summarize(className, methodName, descriptor);
    }

    /**
     * Works out what the constructor call that built a value did, with the arguments it was given
     * standing in for its parameters. The constructor is chosen by the number of arguments, or is the
     * first one declared if none matches, as for a constructor reference that was never called.
     *
     * @param constructed the constructed value, such as one a handler received
     * @return the summary, or null if the constructor can't be found or is too large to follow
     */
    @Nullable
    public Summary summarize(Value.Constructed constructed) {
        return new Summarizer(this.context.classLoader(), this.opaque)
                .summarize(constructed.className(), "<init>", null, constructed.args());
    }

    /**
     * Evaluates a call to a static method, recording every instance call made along the way.
     *
     * @param className the binary or internal name of the class declaring the method
     * @param methodName the method
     * @param args the arguments, chosen between overloads by count
     * @return the trace, with a null value returned if the method couldn't be followed
     */
    public Trace trace(String className, String methodName, List<Value> args) {
        CallScanner scanner = new CallScanner(this.context.classLoader(), (owner, name, descriptor) -> false, match -> {
        });
        scanner.opaque(this.opaque);
        return scanner.traceCall(className.replace('.', '/'), methodName, null, args);
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
