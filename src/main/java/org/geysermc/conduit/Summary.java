package org.geysermc.conduit;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * What a method does from its bytecode without running it.
 *
 * <p>Values are expressed over the method's inputs, which are: {@link Value.This}, {@link Value.Parameter},
 * and then {@link Value.InstanceField} for a field as it was before the method ran. Branches become
 * {@link Value.Conditional}, and a field assigned on only some paths keeps its earlier value on the
 * others.
 *
 * <pre>{@code
 * Summary summary = vm.summarize("com.example.Dial", "turn", null);
 * for (Summary.Assignment assignment : summary.assignments()) {
 *     System.out.println(assignment.target() + "." + assignment.field() + " = " + assignment.value());
 * }
 * }</pre>
 *
 * @param returned the value returned, or null for a void method or one that never returns
 * @param assignments the fields assigned, each once with its final value, in the order first assigned
 * @param invocations the calls made that return nothing and could not be followed, in order
 */
public record Summary(@Nullable Value returned, List<Assignment> assignments, List<Invocation> invocations) {

    /**
     * Finds the final value of a field assigned on an object.
     *
     * @param target the object, or null for a static field
     * @param field the field name
     * @return the assigned value, or null if the method leaves it untouched
     */
    @Nullable
    public Value assigned(@Nullable Value target, String field) {
        for (Assignment assignment : this.assignments) {
            if (Objects.equals(assignment.target(), target) && assignment.field().equals(field)) {
                return assignment.value();
            }
        }

        return null;
    }

    /**
     * A field left assigned once the method returns.
     *
     * @param target the object assigned to, or null for a static field
     * @param className the class the field was referenced through
     * @param field the field name
     * @param value the value it holds afterwards
     */
    public record Assignment(@Nullable Value target, String className, String field, Value value) {
    }

    /**
     * A call that returns nothing, made into code that could not be followed.
     *
     * @param condition when the call is made, or null if always
     * @param call the call, a {@link Value.InstanceCall} or {@link Value.StaticCall}
     */
    public record Invocation(@Nullable Value condition, Value call) {
    }
}
