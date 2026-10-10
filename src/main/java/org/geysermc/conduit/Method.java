package org.geysermc.conduit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Reads a conduit method from calls made around the value, such as a method on its class, a call
 * made on it after it was built, or the chain that built it.
 */
@Target({})
public @interface Method {
    /**
     * Reads the return value of a method on the target class. A method with the same name as the
     * conduit method is used if there is one, otherwise the zero-argument instance method at the
     * given position in declaration order. Falls back to the constructor argument at that index if
     * neither can be evaluated.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Index {
        /**
         * The position of the method to read, in declaration order.
         *
         * @return the method position, counted from zero
         */
        int value();
    }

    /**
     * Matches calls with the given name made on the value after it was built.
     *
     * <p>A {@code boolean} return reports whether any were made, a {@code List} collects every
     * match, and any other type takes the last one. Add {@link Argument.Index} to read one of the
     * call's arguments rather than the call itself.
     *
     * <pre>{@code
     * // palette.add(red); palette.add(blue);
     * @Method.Calls("add")
     * @Argument.Index(0)
     * List<Color> colors();
     * }</pre>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Calls {
        /**
         * The name of the calls to match.
         *
         * @return the method name
         */
        String value();
    }

    /**
     * Matches calls with the given name in the fluent chain that produced the value, including the
     * static factory at its root. Results are shaped the same way as {@link Calls}.
     *
     * <pre>{@code
     * // Color.builder().red(255).green(128)
     * @Method.Chain("red")
     * @Argument.Index(0)
     * Integer red();
     * }</pre>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Chain {
        /**
         * The name of the calls to match in the chain.
         *
         * @return the method name
         */
        String value();
    }
}
