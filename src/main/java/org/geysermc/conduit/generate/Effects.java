package org.geysermc.conduit.generate;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Effects {

    record Expr(Kind kind, int parameter, String field, String type, List<Expr> arguments) {
        static final Expr THIS = new Expr(Kind.THIS, -1, null, null, List.of());
        static final Expr OTHER = new Expr(Kind.OTHER, -1, null, null, List.of());

        static Expr call(String owner, String name, Expr receiver) {
            return new Expr(Kind.CALL, -1, name, owner, List.of(receiver));
        }

        Expr receiver() {
            return this.arguments().isEmpty() ? OTHER : this.arguments().get(0);
        }

        static Expr parameter(int index) {
            return new Expr(Kind.PARAMETER, index, null, null, List.of());
        }

        static Expr field(String name) {
            return new Expr(Kind.FIELD, -1, name, null, List.of());
        }

        enum Kind {
            PARAMETER,
            FIELD,
            THIS,
            CONSTRUCTED,
            CALL,
            OTHER
        }
    }

    record Summary(String name, String descriptor, boolean returnsThis, String returnsField,
                   Map<String, Integer> assigns, Set<String> flags,
                   Map<String, List<Expr>> appends,
                   List<Construction> constructions, List<Construction> delegates, Construction forwards,
                   int returnsParameter
    ) {
    }

    record Construction(String type, List<Expr> arguments) {
    }

    private Effects() {
    }

    static List<Summary> of(ClassNode node) {
        List<Summary> summaries = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if ("<init>".equals(method.name)) {
                continue;
            }
            summaries.add(summarise(node, method));
        }

        return summaries;
    }

    static Construction selfDelegate(ClassNode node, MethodNode constructor) {
        for (Construction construction : summarise(node, constructor).constructions()) {
            if (construction.type().equals(node.name)) {
                return construction;
            }
        }

        return null;
    }

    static Map<String, Expr> constructorFields(ClassNode node, MethodNode constructor) {
        Map<String, Expr> fields = new LinkedHashMap<>();
        interpret(node, constructor, fields::put, null, null, passThroughOf(node));
        return fields;
    }

    private static Map<String, Integer> passThroughOf(ClassNode node) {
        Map<String, Integer> passThrough = new LinkedHashMap<>();
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0) {
                continue;
            }

            passThrough.put(method.name + method.desc, summarise(node, method, Map.of()).returnsParameter());
        }

        return passThrough;
    }

    private static Summary summarise(ClassNode node, MethodNode method) {
        return summarise(node, method, passThroughOf(node));
    }

    private static Summary summarise(ClassNode node, MethodNode method, Map<String, Integer> passThrough) {
        Map<String, Integer> assigns = new LinkedHashMap<>();
        Set<String> flags = new LinkedHashSet<>();
        Map<String, List<Expr>> appends = new LinkedHashMap<>();
        List<Construction> constructions = new ArrayList<>();
        List<Construction> delegates = new ArrayList<>();

        boolean[] returnsThis = {false};
        String[] returnsField = {null};
        int[] returnsParameter = {-1};
        List<Construction> statics = new ArrayList<>();
        interpret(node, method,
                (field, value) -> {
                    if (value.kind() == Expr.Kind.PARAMETER) {
                        assigns.put(field, value.parameter());
                    } else if (Type.getArgumentTypes(method.desc).length == 0) {
                        flags.add(field);
                    }
                },
                (field, arguments) -> appends.computeIfAbsent(field, key -> arguments),
                new Sink(constructions, delegates, returnsThis, returnsField, statics, returnsParameter),
                passThrough
        );

        Construction forwards = returnsThis[0] || returnsField[0] != null || statics.isEmpty()
                ? null
                : statics.getLast();

        return new Summary(
                method.name, method.desc, returnsThis[0], returnsField[0],
                assigns, flags, appends, constructions, delegates, forwards, returnsParameter[0]
        );
    }

    private record Sink(List<Construction> constructions, List<Construction> delegates,
                        boolean[] returnsThis, String[] returnsField, List<Construction> statics,
                        int[] returnsParameter
    ) {
    }

    private interface Assignment {
        void accept(String field, Expr value);
    }

    private interface Append {
        void accept(String field, List<Expr> arguments);
    }

    private static void interpret(ClassNode node, MethodNode method, Assignment assignment, Append append,
                                  Sink sink, Map<String, Integer> passThrough) {
        Deque<Expr> stack = new ArrayDeque<>();
        Type[] parameters = Type.getArgumentTypes(method.desc);
        Map<Integer, Expr> locals = new LinkedHashMap<>();
        boolean instance = (method.access & Opcodes.ACC_STATIC) == 0;
        if (instance) {
            locals.put(0, Expr.THIS);
        }

        int slot = instance ? 1 : 0;
        for (int i = 0; i < parameters.length; i++) {
            locals.put(slot, Expr.parameter(i));
            slot += parameters[i].getSize();
        }

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op < 0) {
                continue;
            }

            switch (op) {
                case Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.LLOAD ->
                        stack.push(locals.getOrDefault(((VarInsnNode) insn).var, Expr.OTHER));

                case Opcodes.ASTORE, Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.LSTORE ->
                        locals.put(((VarInsnNode) insn).var, pop(stack));

                case Opcodes.DUP -> {
                    if (!stack.isEmpty()) {
                        stack.push(stack.peek());
                    }
                }

                case Opcodes.POP -> pop(stack);

                case Opcodes.GETFIELD -> {
                    Expr receiver = pop(stack);
                    FieldInsnNode field = (FieldInsnNode) insn;
                    stack.push(receiver.kind() == Expr.Kind.THIS && node.name.equals(field.owner)
                            ? Expr.field(field.name)
                            : Expr.OTHER);
                }

                case Opcodes.PUTFIELD -> {
                    Expr value = pop(stack);
                    Expr receiver = pop(stack);
                    FieldInsnNode field = (FieldInsnNode) insn;
                    if (receiver.kind() == Expr.Kind.THIS && node.name.equals(field.owner)) {
                        assignment.accept(field.name, value);
                    }
                }

                case Opcodes.NEW -> stack.push(new Expr(Expr.Kind.CONSTRUCTED, -1, null,
                        ((TypeInsnNode) insn).desc, List.of()));

                case Opcodes.INVOKESPECIAL, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESTATIC -> {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    List<Expr> arguments = popAll(stack, Type.getArgumentTypes(call.desc).length);
                    Expr receiver = op == Opcodes.INVOKESTATIC ? Expr.OTHER : pop(stack);

                    if ("<init>".equals(call.name)) {
                        if (!stack.isEmpty() && stack.peek().kind() == Expr.Kind.CONSTRUCTED) {
                            pop(stack);
                        }
                        Expr constructed = new Expr(Expr.Kind.CONSTRUCTED, -1, null, call.owner, arguments);
                        stack.push(constructed);
                        if (sink != null) {
                            sink.constructions().add(new Construction(call.owner, arguments));
                        }
                        break;
                    }

                    if (sink != null && op == Opcodes.INVOKESTATIC) {
                        sink.statics().add(new Construction(call.name, arguments));
                    }

                    if (append != null && receiver.kind() == Expr.Kind.FIELD
                            && ("add".equals(call.name) || "put".equals(call.name))) {
                        append.accept(receiver.field(), arguments);
                    }

                    if (sink != null && receiver.kind() == Expr.Kind.THIS && node.name.equals(call.owner)) {
                        sink.delegates().add(new Construction(call.name, arguments));
                    }

                    if (Type.getReturnType(call.desc).getSort() != Type.VOID) {
                        Integer handedBack = op == Opcodes.INVOKESTATIC && node.name.equals(call.owner)
                                ? passThrough.get(call.name + call.desc)
                                : null;
                        if (handedBack != null && handedBack >= 0 && handedBack < arguments.size()) {
                            stack.push(arguments.get(handedBack));
                        } else if (op == Opcodes.INVOKESTATIC && passesThrough(call.desc, arguments)) {
                            stack.push(arguments.getFirst());
                        } else if (receiver.kind() == Expr.Kind.THIS && node.name.equals(call.owner)) {
                            stack.push(Expr.THIS);
                        } else if (receiver.kind() == Expr.Kind.PARAMETER) {
                            stack.push(Expr.call(call.owner, call.name, receiver));
                        } else {
                            stack.push(Expr.OTHER);
                        }
                    }
                }

                case Opcodes.ARETURN -> {
                    Expr returned = pop(stack);
                    if (sink == null) {
                        break;
                    }

                    if (returned.kind() == Expr.Kind.THIS) {
                        sink.returnsThis()[0] = true;
                    }

                    if (returned.kind() == Expr.Kind.FIELD) {
                        sink.returnsField()[0] = returned.field();
                    }

                    if (returned.kind() == Expr.Kind.PARAMETER) {
                        sink.returnsParameter()[0] = returned.parameter();
                    }
                }

                default -> {
                    if (produces(op)) {
                        stack.push(Expr.OTHER);
                    }
                }
            }
        }
    }

    private static boolean passesThrough(String descriptor, List<Expr> arguments) {
        if (arguments.isEmpty()) {
            return false;
        }

        Expr first = arguments.get(0);
        if (first.kind() != Expr.Kind.PARAMETER && first.kind() != Expr.Kind.FIELD) {
            return false;
        }

        Type[] parameters = Type.getArgumentTypes(descriptor);
        return arguments.size() == 1
                || parameters.length > 0 && Type.getReturnType(descriptor).equals(parameters[0]);
    }

    private static boolean produces(int op) {
        return op >= Opcodes.ACONST_NULL && op <= Opcodes.LDC
                || op == Opcodes.GETSTATIC
                || op >= Opcodes.IADD && op <= Opcodes.DREM;
    }

    private static Expr pop(Deque<Expr> stack) {
        return stack.isEmpty() ? Expr.OTHER : stack.pop();
    }

    private static List<Expr> popAll(Deque<Expr> stack, int count) {
        List<Expr> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.addFirst(pop(stack));
        }

        return out;
    }
}
