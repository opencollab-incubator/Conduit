package org.geysermc.conduit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Reads a conduit method from an argument of the call that built the value, or from a member of
 * the target class under another name.
 */
@Target({})
public @interface Argument {
    /**
     * Reads the argument at the given index of the constructor or factory call that produced the
     * value. Paired with {@link Method.Calls} or {@link Method.Chain}, reads it from the matched
     * call instead.
     *
     * <pre>{@code
     * // new Color(255, 128, 0)
     * @Argument.Index(1)
     * Integer green();
     * }</pre>
     *
     * <p>If the return type is a conduit and the argument at that index doesn't match it, the
     * arguments starting there are regrouped to fit the return type's own {@code Argument.Index}
     * methods. That covers a class flattening another class's fields into its constructor.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Index {
        /**
         * The index of the argument to read.
         *
         * @return the argument index, counted from zero
         */
        int value();
    }

    /**
     * Looks the method up on the target class under a different name. Useful when a field or
     * method in the scanned class has a name that would make a poor Java method name, or clashes
     * with another one.
     *
     * <pre>{@code
     * @Argument.Named("r")
     * Integer red();
     * }</pre>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Named {
        /**
         * The name to look up on the target class in place of the method's own.
         *
         * @return the field or method name on the target class
         */
        String value();
    }
}
