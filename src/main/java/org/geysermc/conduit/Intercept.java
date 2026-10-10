package org.geysermc.conduit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a class as a handler for calls the scanned code makes to an API.
 *
 * <p>The handler mirrors the API it intercepts. Whenever the scanner finds a call to the target
 * class, it reconstructs the arguments and invokes the handler's static method with the same name
 * and number of parameters. For an instance method, the receiver is passed as the first argument.
 *
 * <pre>{@code
 * @Intercept("com.example.api.ColorRegistry")
 * public class ColorRegistry {
 *     @Intercept.Capture
 *     public static CaptureChannel<Color> CAPTURE;
 *
 *     public static void register(String name, Color color) {
 *         CAPTURE.emit(color);
 *     }
 * }
 * }</pre>
 *
 * <p>Each argument is converted to the handler's parameter type:
 *
 * <ul>
 *     <li>A {@link Conduit} interface wraps the reconstructed value.</li>
 *     <li>A {@code Supplier<T>} evaluates the lambda or method reference that was passed, and only
 *     when {@code get()} is called. {@code T} may be a conduit, or {@link Trace} for the raw
 *     result.</li>
 *     <li>Strings, numbers and booleans are passed through when they are compile-time
 *     constants, and are {@code null} otherwise.</li>
 *     <li>{@link Value} receives the reconstructed value as is.</li>
 * </ul>
 *
 * <p>A matched call is not stepped into. If a handler intercepts both a wrapper API and the API it
 * wraps, a registration through the wrapper is reported once, not twice.
 *
 * @see VirtualMachine#intercept(Class[])
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Intercept {
    /**
     * The binary name of the class whose calls are intercepted.
     *
     * @return the target class name
     */
    String value();

    /**
     * Marks a static {@link CaptureChannel} field on a handler to be created when the handler is
     * registered. Results are handed out through it, see {@link VirtualMachine#capture}.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Capture {
    }
}
