package org.geysermc.conduit;

import java.util.List;

/**
 * A value as reconstructed from bytecode, describing how it was built rather than what it is.
 *
 * <p>Most code reads values through a {@link Conduit} instead. This is what a {@link Trace} holds,
 * and what a conduit method or handler parameter declared as {@code Value} receives.
 *
 * <p>Class names are internal names, such as {@code com/example/Color}.
 */
public sealed interface Value {

    /**
     * Wraps a constant as a value.
     *
     * @param v a constant, or null
     * @return the constant as a value
     */
    static Value literal(Object v) {
        return new Literal(v);
    }

    /**
     * A compile-time constant, such as a string or a boxed number, or null.
     *
     * @param value the constant
     */
    record Literal(Object value) implements Value {
        @Override
        public String toString() {
            if (this.value == null) {
                return "null";
            }

            if (this.value instanceof String s) {
                return "\"" + s + "\"";
            }

            return String.valueOf(this.value);
        }
    }

    /**
     * A constructor call, {@code new className(args)}.
     *
     * @param className the class constructed
     * @param args the constructor arguments
     */
    record Constructed(String className, List<Value> args) implements Value {

        @Override
        public String toString() {
            return "new " + shortName(this.className) + "(" + join(this.args) + ")";
        }
    }

    /**
     * A static call, {@code className.methodName(args)}.
     *
     * @param className the class declaring the method
     * @param methodName the method name
     * @param args the arguments
     */
    record StaticCall(String className, String methodName, List<Value> args) implements Value {

        @Override
        public String toString() {
            return shortName(this.className) + "." + this.methodName + "(" + join(this.args) + ")";
        }
    }

    /**
     * An instance call, {@code receiver.methodName(args)}.
     *
     * @param className the class declaring the method
     * @param methodName the method name
     * @param receiver the value the method was called on
     * @param args the arguments
     */
    record InstanceCall(String className, String methodName, Value receiver, List<Value> args) implements Value {

        @Override
        public String toString() {
            return this.receiver + "." + this.methodName + "(" + join(this.args) + ")";
        }
    }

    /**
     * A field read that could not be resolved further.
     *
     * @param className the class declaring the field
     * @param fieldName the field name
     */
    record Field(String className, String fieldName) implements Value {

        @Override
        public String toString() {
            return shortName(this.className) + "." + this.fieldName;
        }
    }

    /**
     * An object between {@code new} and its constructor call.
     *
     * @param className the class being constructed
     */
    record Uninitialized(String className) implements Value {

        @Override
        public String toString() {
            return "<uninit " + shortName(this.className) + ">";
        }
    }

    /**
     * A lambda or method reference, with the method it was compiled to and what it captured.
     *
     * @param name the functional interface method
     * @param descriptor the descriptor of the call site
     * @param implOwner the class declaring the implementation, or null if unknown
     * @param implMethod the implementation method, {@code <init>} for a constructor reference
     * @param implDescriptor the implementation method's descriptor
     * @param implStatic whether the implementation is static
     * @param captures the values captured at the call site
     */
    record Lambda(String name, String descriptor, String implOwner, String implMethod,
                  String implDescriptor, boolean implStatic, List<Value> captures) implements Value {

        @Override
        public String toString() {
            if (this.implOwner == null) {
                return "<lambda " + this.name + this.descriptor + ">";
            }
            return "<lambda " + shortName(this.implOwner) + "." + this.implMethod + this.implDescriptor + ">";
        }
    }

    /**
     * Every value a branching method can return. A {@code List} conduit method receives all of them.
     *
     * @param values the values, without duplicates
     */
    record Multiple(List<Value> values) implements Value {

        @Override
        public String toString() {
            return this.values.toString();
        }
    }

    /**
     * Anything the interpreter could not reconstruct.
     *
     * @param reason a short note on why, for debugging
     */
    record Unknown(String reason) implements Value {

        @Override
        public String toString() {
            return "?" + this.reason + "?";
        }
    }

    private static String shortName(String internal) {
        int slash = internal.lastIndexOf('/');
        return slash >= 0 ? internal.substring(slash + 1) : internal;
    }

    private static String join(List<Value> args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }

            sb.append(args.get(i));
        }

        return sb.toString();
    }
}
