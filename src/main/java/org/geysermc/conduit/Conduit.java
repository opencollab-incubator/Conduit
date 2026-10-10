package org.geysermc.conduit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an interface as a view over a class in the scanned jar.
 *
 * <p>Conduit never loads or runs the classes it scans, so a value it reconstructs (a constructor
 * call, a factory call, a builder chain) is not an instance of anything. A conduit gives that value
 * a typed face. Each method on the interface reads one piece of it, worked out from the bytecode
 * that built it.
 *
 * <pre>{@code
 * @Conduit("com.example.Color")
 * public interface Color {
 *     int red();
 *     int green();
 *     int blue();
 * }
 * }</pre>
 *
 * <p>A conduit can be returned from another conduit, or taken as a parameter by an
 * {@link Intercept} handler, and is wrapped as needed.
 *
 * <h2>Resolving a method</h2>
 *
 * <p>Each call on a conduit is resolved against the reconstructed value, in this order:
 *
 * <ol>
 *     <li>{@link Implementation} returns the class the value resolves to.</li>
 *     <li>{@link Bind} entries, tried in the order they are declared.</li>
 *     <li>{@link Method.Calls} and {@link Method.Chain}, narrowed by {@link Argument.Index}.</li>
 *     <li>{@link Method.Index}, then {@link Argument.Index} on its own.</li>
 *     <li>Otherwise the method name (or {@link Argument.Named}) is looked up on the target class
 *     as a field assigned in its constructor, or as a method whose return value can be
 *     evaluated. A {@code get} or {@code is} prefix is dropped when looking for a field.</li>
 *     <li>Failing all of that, the method's position in the interface is used as an argument
 *     index into the constructor or factory call.</li>
 * </ol>
 *
 * <p>A method that cannot be resolved returns {@code null}, or an empty {@code List}, {@code Set}
 * or {@code Map} for collection types. Boxed types such as {@code Integer} over
 * {@code int} are preferred since a primitive cannot hold the missing case.
 *
 * <p>Conduits are usually generated from the real class rather than written by hand. See
 * {@code org.geysermc.conduit.generate.ConduitGenerator}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Conduit {
    /**
     * The binary name of the class this conduit mirrors, such as {@code "com.example.Color"} or
     * {@code "com.example.Outer$Inner"}.
     *
     * @return the target class name
     */
    String value();

    /**
     * Describes one place a conduit method's value can come from.
     *
     * <p>Repeat the annotation to describe more than one. Entries are tried in the order they are
     * declared and the first that produces something wins, so a single method can read an object
     * however the scanned code happened to build it:
     *
     * <pre>{@code
     * @Conduit("com.example.Mesh")
     * public interface Mesh {
     *     // new Mesh(root)
     *     @Conduit.Bind(arg = 0, descriptor = "(Lcom/example/Part;)V", producer = "<init>")
     *     // Mesh.create(); mesh.getRoot()...
     *     @Conduit.Bind(calls = "getRoot")
     *     Part root();
     * }
     * }</pre>
     *
     * <p>An entry first picks the calls it applies to, then picks a value out of each one:
     *
     * <ul>
     *     <li>With {@link #calls()} or {@link #chain()}, the matching calls on or behind the
     *     value.</li>
     *     <li>Otherwise, the call that produced the value itself, such as its constructor or
     *     factory.</li>
     * </ul>
     *
     * <p>From each call, {@link #arg()} selects an argument, or the call itself when unset. A
     * {@code List} return collects one result per call, a {@code Map} return is keyed by
     * {@link #key()}, a {@code boolean} return reports whether any call matched, and any other
     * type takes the first call that resolves.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @Repeatable(Binds.class)
    @interface Bind {
        /**
         * The index of the argument holding the value, counted from zero. Unset ({@code -1})
         * means the matched call itself.
         *
         * @return the argument index
         */
        int arg() default -1;

        /**
         * How many arguments, starting at {@link #arg()}, make up the value. When more than one,
         * they are gathered as if passed to the return type's own constructor, so three floats
         * can be read back as a vector conduit.
         *
         * @return the number of arguments
         */
        int span() default 1;

        /**
         * Starts from an argument of this conduit's value rather than the value itself. Useful
         * when the data was attached to something the value was given, rather than to the value.
         *
         * @return the argument index to start from, or {@code -1} for the value itself
         */
        int from() default -1;

        /**
         * For a {@code Map} return, the argument of each call to use as the entry's key.
         *
         * @return the argument index of the key
         */
        int key() default -1;

        /**
         * Matches calls made on the value after it was built, by method name. For example
         * {@code calls = "addChild"} matches each {@code value.addChild(...)}.
         *
         * @return the method name
         */
        String calls() default "";

        /**
         * Matches calls in the fluent chain that produced the value, by method name. For a value
         * built as {@code Builder.create().size(4).name("a")}, {@code chain = "size"} matches the
         * {@code size(4)} call. The root of the chain can be matched too, by name for a static
         * factory or as {@code "<init>"} for a constructor.
         *
         * @return the method name
         */
        String chain() default "";

        /**
         * The JVM descriptor of the method or constructor this entry describes, such as
         * {@code "(FFF)V"}. Calls taking a different number of arguments are skipped, which
         * keeps overloads with different shapes from answering for each other.
         *
         * @return the descriptor
         */
        String descriptor() default "";

        /**
         * The name of the method that must have produced the value for this entry to apply, or
         * {@code "<init>"} for a constructor. Overloads and sibling factories often share a
         * descriptor, so this is what tells {@code offset(x, y, z)} apart from
         * {@code rotation(x, y, z)}.
         *
         * @return the producing method's name
         */
        String producer() default "";
    }

    /**
     * Container for repeated {@link Bind} entries. Not used directly.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Binds {
        /**
         * The repeated entries, in declaration order.
         *
         * @return the repeated entries
         */
        Bind[] value();
    }

    /**
     * Marks a method that returns the binary name of the class implementing this conduit's
     * target, or {@code null} if it cannot be told.
     *
     * <p>Meant for functional interfaces, which carry no state of their own. At a call site they
     * are a lambda, a method reference or a constructor call, and this is how to find out what
     * that points at. For {@code MyRenderer::new} it returns {@code "com.example.MyRenderer"}.
     *
     * <pre>{@code
     * @Conduit("com.example.RendererFactory")
     * public interface RendererFactory {
     *     @Conduit.Implementation
     *     String implementation();
     * }
     * }</pre>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Implementation {
    }
}
