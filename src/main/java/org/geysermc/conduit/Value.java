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
     * The object a summarised method was called on.
     */
    record This() implements Value {

        @Override
        public String toString() {
            return "this";
        }
    }

    /**
     * An argument of a summarised method, standing for whatever the caller passes.
     *
     * @param index the position among the declared parameters, from zero, not counting {@code this}
     */
    record Parameter(int index) implements Value {

        @Override
        public String toString() {
            return "arg" + this.index;
        }
    }

    /**
     * A field read from an object, holding whatever the field contained when the method began.
     *
     * @param receiver the object the field is read from
     * @param className the class the field was referenced through
     * @param fieldName the field
     */
    record InstanceField(Value receiver, String className, String fieldName) implements Value {

        @Override
        public String toString() {
            return this.receiver + "." + this.fieldName;
        }
    }

    /**
     * An operation over values that are not all constant. Comparisons and logical operators
     * produce a boolean.
     *
     * @param operator the operator
     * @param operands the operands, in source order
     */
    record Operation(Operator operator, List<Value> operands) implements Value {

        @Override
        public String toString() {
            if (this.operator == Operator.INDEX) {
                return this.operands.get(0) + "[" + this.operands.get(1) + "]";
            }

            if (this.operands.size() == 1) {
                return this.operator.symbol + "(" + this.operands.getFirst() + ")";
            }

            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < this.operands.size(); i++) {
                if (i > 0) {
                    sb.append(' ').append(this.operator.symbol).append(' ');
                }

                sb.append(this.operands.get(i));
            }

            return sb.append(')').toString();
        }
    }

    /**
     * An operator of an {@link Operation}.
     */
    enum Operator {
        ADD("+"),
        SUBTRACT("-"),
        MULTIPLY("*"),
        DIVIDE("/"),
        REMAINDER("%"),
        NEGATE("-"),
        AND("&"),
        OR("|"),
        XOR("^"),
        SHIFT_LEFT("<<"),
        SHIFT_RIGHT(">>"),
        UNSIGNED_SHIFT_RIGHT(">>>"),
        /** Converts a floating point number to an integer, rounding towards zero. */
        TRUNCATE("(int)"),
        /** A three-way comparison of two numbers: negative, zero or positive. */
        COMPARE("<=>"),
        EQUAL("=="),
        NOT_EQUAL("!="),
        LESS("<"),
        LESS_OR_EQUAL("<="),
        GREATER(">"),
        GREATER_OR_EQUAL(">="),
        LOGICAL_AND("&&"),
        LOGICAL_OR("||"),
        LOGICAL_NOT("!"),
        /** The length of an array. */
        LENGTH("length"),
        /** An element of an array: the array, then the index. */
        INDEX("[]"),
        /** Strings joined in order, from concatenation with values that aren't constant. */
        CONCAT("+");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }
    }

    /**
     * A value that depends on a branch, as in {@code condition ? then : otherwise}.
     *
     * @param condition a boolean value
     * @param then the value when the condition holds
     * @param otherwise the value when it does not
     */
    record Conditional(Value condition, Value then, Value otherwise) implements Value {

        @Override
        public String toString() {
            return "(" + this.condition + " ? " + this.then + " : " + this.otherwise + ")";
        }
    }

    /**
     * Whether a value is an instance of a class, as a boolean.
     *
     * @param value the value tested
     * @param className the class tested against
     */
    record InstanceOf(Value value, String className) implements Value {

        @Override
        public String toString() {
            return this.value + " instanceof " + shortName(this.className);
        }
    }

    /**
     * An array created with a constant length, and what was stored in it.
     *
     * @param componentType the descriptor of the element type
     * @param elements the elements, defaulting to zero or null where nothing was stored
     */
    record Array(String componentType, List<Value> elements) implements Value {

        @Override
        public String toString() {
            return "[" + join(this.elements) + "]";
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
