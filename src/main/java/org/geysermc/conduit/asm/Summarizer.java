package org.geysermc.conduit.asm;

import org.geysermc.conduit.Summary;
import org.geysermc.conduit.Value;
import org.geysermc.conduit.Value.Operator;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;

public final class Summarizer {
    private static final int INLINE_DEPTH = 8;
    private static final int BACK_JUMPS = 4096;
    private static final int STEP_LIMIT = 1 << 20;
    private static final int ARRAY_LIMIT = 4096;
    private static final int MERGE_SEARCH = 64;

    private static final Value TOP = new Value.Unknown("top");
    private static final Value TRUE = Value.literal(true);
    private static final Value FALSE = Value.literal(false);
    private static final Value ZERO = Value.literal(0);

    private static final Operator[] ARITHMETIC = {
            Operator.ADD, Operator.SUBTRACT, Operator.MULTIPLY, Operator.DIVIDE, Operator.REMAINDER
    };
    private static final Operator[] SHIFTS = {Operator.SHIFT_LEFT, Operator.SHIFT_RIGHT, Operator.UNSIGNED_SHIFT_RIGHT};
    private static final Operator[] BITWISE = {Operator.AND, Operator.OR, Operator.XOR};
    private static final Operator[] JUMPS = {
            Operator.EQUAL, Operator.NOT_EQUAL, Operator.LESS, Operator.GREATER_OR_EQUAL, Operator.GREATER, Operator.LESS_OR_EQUAL
    };

    private final ConduitClassLoader loader;
    private final Map<String, ClassNode> classes;
    private final Map<String, Map<String, Value>> staticFields;
    private final CallScanner statics;
    private final Opaque opaque;
    private final Set<String> active = new HashSet<>();
    private Map<String, Value> constructed;
    private String root;
    private int steps;

    public Summarizer(ConduitClassLoader loader, Opaque opaque) {
        this(loader, new HashMap<>(), new HashMap<>(), opaque);
    }

    private Summarizer(ConduitClassLoader loader, Map<String, ClassNode> classes, Map<String, Map<String, Value>> staticFields, Opaque opaque) {
        this.loader = loader;
        this.classes = classes;
        this.staticFields = staticFields;
        this.opaque = opaque;
        this.statics = new CallScanner(loader, (owner, name, descriptor) -> false, match -> {
        });
        this.statics.opaque(opaque);
    }

    public Summary summarize(String className, String methodName, String descriptor) {
        return this.summarize(className, methodName, descriptor, null);
    }

    public Summary summarize(String className, String methodName, String descriptor, List<Value> arguments) {
        this.root = className.replace('.', '/');
        Resolved target = this.resolve(this.root, methodName, descriptor, arguments == null ? -1 : arguments.size());
        if (target == null && arguments != null) {
            target = this.resolve(this.root, methodName, descriptor);
        }
        if (target == null) {
            return null;
        }

        MethodNode method = target.method();
        Value[] locals = new Value[Math.max(method.maxLocals, 1)];
        int slot = 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            locals[slot++] = new Value.This();
        }

        Type[] types = Type.getArgumentTypes(method.desc);
        for (int i = 0; i < types.length; i++) {
            locals[slot] = arguments == null || i >= arguments.size() ? new Value.Parameter(i) : arguments.get(i);
            if (types[i].getSize() == 2) {
                locals[slot + 1] = TOP;
            }
            slot += types[i].getSize();
        }

        State exit;
        try {
            exit = this.walk(target, new State(locals), 0);
        } catch (Exhausted e) {
            return null;
        }

        if (exit == null) {
            return new Summary(null, List.of(), List.of());
        }

        Value returned = Type.getReturnType(method.desc).getSort() == Type.VOID || exit.stack.isEmpty()
                ? null
                : exit.stack.getFirst();

        List<Summary.Assignment> assignments = new ArrayList<>(exit.writes.size());
        exit.writes.forEach((key, write) -> assignments.add(
                new Summary.Assignment(key.target(), write.className(), key.field(), write.value())));

        return new Summary(returned, List.copyOf(assignments), List.copyOf(exit.calls));
    }

    private State walk(Resolved target, State entry, int depth) {
        MethodNode method = target.method();
        Map<LabelNode, List<State>> pending = new HashMap<>();
        Map<LabelNode, List<Guard>> entered = new HashMap<>();
        Map<AbstractInsnNode, Integer> backJumps = new HashMap<>();
        State exit = null;
        State current = entry;

        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null) {
            if (++this.steps > STEP_LIMIT) {
                throw new Exhausted();
            }

            if (insn instanceof LabelNode label) {
                List<State> incoming = pending.remove(label);
                if (incoming != null) {
                    if (current != null) {
                        incoming.add(current);
                    }
                    current = mergeAll(incoming);
                }
                if (current != null) {
                    entered.put(label, List.copyOf(current.guards));
                }
                insn = insn.getNext();
                continue;
            }

            int op = insn.getOpcode();
            if (current == null || op < 0) {
                insn = insn.getNext();
                continue;
            }

            if (insn instanceof JumpInsnNode jump) {
                if (op == Opcodes.JSR) {
                    current = null;
                    insn = insn.getNext();
                    continue;
                }

                Value condition = condition(op, current);
                Boolean known = truth(condition);
                if (method.instructions.indexOf(jump.label) < method.instructions.indexOf(jump)) {
                    int taken = backJumps.merge(jump, 1, Integer::sum);
                    if (known == Boolean.TRUE && taken <= BACK_JUMPS && current.guards.equals(entered.get(jump.label))) {
                        insn = jump.label;
                        continue;
                    }
                } else if (known == Boolean.TRUE) {
                    pending.computeIfAbsent(jump.label, _ -> new ArrayList<>()).add(current);
                    current = null;
                } else if (known == null) {
                    Fork fork = new Fork(false, condition, negate(condition));
                    State taken = current.copy();
                    taken.guards.add(new Guard(fork, 0));
                    pending.computeIfAbsent(jump.label, _ -> new ArrayList<>()).add(taken);
                    current.guards.add(new Guard(fork, 1));
                }

                insn = insn.getNext();
                continue;
            }

            if (insn instanceof TableSwitchInsnNode table) {
                int[] keys = new int[table.max - table.min + 1];
                for (int i = 0; i < keys.length; i++) {
                    keys[i] = table.min + i;
                }
                switchOn(pending, current, keys, table.labels, table.dflt);
                current = null;
                insn = insn.getNext();
                continue;
            }

            if (insn instanceof LookupSwitchInsnNode lookup) {
                int[] keys = lookup.keys.stream().mapToInt(Integer::intValue).toArray();
                switchOn(pending, current, keys, lookup.labels, lookup.dflt);
                current = null;
                insn = insn.getNext();
                continue;
            }

            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                List<Value> returned = new ArrayList<>(2);
                if (op == Opcodes.LRETURN || op == Opcodes.DRETURN) {
                    returned.add(current.popWide());
                    returned.add(TOP);
                } else if (op != Opcodes.RETURN) {
                    returned.add(current.pop());
                }

                current.stack = returned;
                current.locals = new Value[0];
                exit = exit == null ? current : merge(exit, current);
                current = null;
                insn = insn.getNext();
                continue;
            }

            if (op == Opcodes.ATHROW || op == Opcodes.RET) {
                current = null;
                insn = insn.getNext();
                continue;
            }

            current = this.step(insn, current, depth);
            insn = insn.getNext();
        }

        return exit;
    }

    private static void switchOn(Map<LabelNode, List<State>> pending, State state, int[] keys, List<LabelNode> labels, LabelNode fallback) {
        Value key = state.pop();
        if (key instanceof Value.Literal(Integer known)) {
            LabelNode target = fallback;
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] == known) {
                    target = labels.get(i);
                }
            }
            pending.computeIfAbsent(target, _ -> new ArrayList<>()).add(state);
            return;
        }

        Value[] conditions = new Value[keys.length + 1];
        List<Value> unmatched = new ArrayList<>(keys.length);
        for (int i = 0; i < keys.length; i++) {
            conditions[i] = compare(Operator.EQUAL, key, Value.literal(keys[i]));
            unmatched.add(negate(conditions[i]));
        }
        conditions[keys.length] = all(unmatched);

        Fork fork = new Fork(true, conditions);
        for (int i = 0; i <= keys.length; i++) {
            State arm = state.copy();
            arm.guards.add(new Guard(fork, i));
            pending.computeIfAbsent(i < keys.length ? labels.get(i) : fallback, _ -> new ArrayList<>()).add(arm);
        }
    }

    private State step(AbstractInsnNode insn, State s, int depth) {
        int op = insn.getOpcode();
        Value constant = CallScanner.constant(insn);
        if (constant != null) {
            s.push(constant, constant instanceof Value.Literal(Object v) && (v instanceof Long || v instanceof Double));
            return s;
        }

        switch (op) {
            case Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.ALOAD -> s.push(s.local(((VarInsnNode) insn).var), false);
            case Opcodes.LLOAD, Opcodes.DLOAD -> s.push(s.local(((VarInsnNode) insn).var), true);
            case Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.ASTORE -> s.store(((VarInsnNode) insn).var, s.pop(), false);
            case Opcodes.LSTORE, Opcodes.DSTORE -> s.store(((VarInsnNode) insn).var, s.popWide(), true);
            case Opcodes.IINC -> {
                IincInsnNode iinc = (IincInsnNode) insn;
                s.store(iinc.var, arithmetic(Operator.ADD, Kind.INT, s.local(iinc.var), Value.literal(iinc.incr)), false);
            }

            case Opcodes.POP -> s.pop();
            case Opcodes.POP2 -> {
                s.pop();
                s.pop();
            }
            case Opcodes.DUP -> s.dup(1, 0);
            case Opcodes.DUP_X1 -> s.dup(1, 1);
            case Opcodes.DUP_X2 -> s.dup(1, 2);
            case Opcodes.DUP2 -> s.dup(2, 0);
            case Opcodes.DUP2_X1 -> s.dup(2, 1);
            case Opcodes.DUP2_X2 -> s.dup(2, 2);
            case Opcodes.SWAP -> {
                Value a = s.pop();
                Value b = s.pop();
                s.push(a, false);
                s.push(b, false);
            }

            case Opcodes.GETSTATIC -> {
                FieldInsnNode field = (FieldInsnNode) insn;
                Write written = s.writes.get(new Key(null, field.name));
                s.push(written != null ? written.value() : this.staticField(field.owner, field.name), wide(field.desc));
            }
            case Opcodes.PUTSTATIC -> {
                FieldInsnNode field = (FieldInsnNode) insn;
                s.write(null, field.owner, field.name, s.pop(Type.getType(field.desc)));
            }
            case Opcodes.GETFIELD -> {
                FieldInsnNode field = (FieldInsnNode) insn;
                Value receiver = s.pop();
                Write written = s.writes.get(new Key(receiver, field.name));
                s.push(written != null ? written.value() : new Value.InstanceField(receiver, field.owner, field.name), wide(field.desc));
            }
            case Opcodes.PUTFIELD -> {
                FieldInsnNode field = (FieldInsnNode) insn;
                Value value = s.pop(Type.getType(field.desc));
                s.write(s.pop(), field.owner, field.name, value);
            }

            case Opcodes.NEW -> s.push(new Value.Uninitialized(((TypeInsnNode) insn).desc), false);
            case Opcodes.CHECKCAST -> {
            }
            case Opcodes.INSTANCEOF -> s.push(new Value.InstanceOf(s.pop(), ((TypeInsnNode) insn).desc), false);

            case Opcodes.NEWARRAY -> s.push(array(s.pop(), primitiveDescriptor(((IntInsnNode) insn).operand)), false);
            case Opcodes.ANEWARRAY -> {
                String type = ((TypeInsnNode) insn).desc;
                s.push(array(s.pop(), type.startsWith("[") ? type : "L" + type + ";"), false);
            }
            case Opcodes.MULTIANEWARRAY -> {
                for (int i = 0; i < ((MultiANewArrayInsnNode) insn).dims; i++) {
                    s.pop();
                }
                s.push(new Value.Unknown("array"), false);
            }
            case Opcodes.ARRAYLENGTH -> s.push(this.length(s.pop()), false);
            case Opcodes.IALOAD, Opcodes.FALOAD, Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD,
                 Opcodes.LALOAD, Opcodes.DALOAD -> {
                Value index = s.pop();
                s.push(element(s.pop(), index), op == Opcodes.LALOAD || op == Opcodes.DALOAD);
            }
            case Opcodes.IASTORE, Opcodes.FASTORE, Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE,
                 Opcodes.LASTORE, Opcodes.DASTORE -> {
                Value value = op == Opcodes.LASTORE || op == Opcodes.DASTORE ? s.popWide() : s.pop();
                Value index = s.pop();
                if (s.pop() instanceof Value.Array(_, List<Value> elements)
                        && index instanceof Value.Literal(Integer i) && i >= 0 && i < elements.size()) {
                    elements.set(i, value);
                }
            }

            case Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> s.pop();

            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE -> {
                return this.invoke((MethodInsnNode) insn, s, depth);
            }
            case Opcodes.INVOKEDYNAMIC -> {
                InvokeDynamicInsnNode dynamic = (InvokeDynamicInsnNode) insn;
                List<Value> captures = s.popArguments(dynamic.desc);
                Value made = "java/lang/invoke/StringConcatFactory".equals(dynamic.bsm.getOwner())
                        ? CallScanner.concatenation(dynamic, captures)
                        : CallScanner.lambda(dynamic, captures);
                s.push(made, false);
            }

            default -> {
                if (op >= Opcodes.IADD && op <= Opcodes.DREM) {
                    Kind kind = Kind.of((op - Opcodes.IADD) % 4);
                    binary(s, ARITHMETIC[(op - Opcodes.IADD) / 4], kind, kind);
                } else if (op >= Opcodes.INEG && op <= Opcodes.DNEG) {
                    Kind kind = Kind.of(op - Opcodes.INEG);
                    s.push(negative(kind.pop(s), kind), kind.wide);
                } else if (op >= Opcodes.ISHL && op <= Opcodes.LUSHR) {
                    binary(s, SHIFTS[(op - Opcodes.ISHL) / 2], Kind.of((op - Opcodes.ISHL) % 2), Kind.INT);
                } else if (op >= Opcodes.IAND && op <= Opcodes.LXOR) {
                    Kind kind = Kind.of((op - Opcodes.IAND) % 2);
                    binary(s, BITWISE[(op - Opcodes.IAND) / 2], kind, kind);
                } else if (op >= Opcodes.LCMP && op <= Opcodes.DCMPG) {
                    Kind kind = op == Opcodes.LCMP ? Kind.LONG : op <= Opcodes.FCMPG ? Kind.FLOAT : Kind.DOUBLE;
                    Value right = kind.pop(s);
                    Value left = kind.pop(s);
                    Value folded = fold(Operator.COMPARE, kind, left, right);
                    s.push(folded != null ? folded : new Value.Operation(Operator.COMPARE, List.of(left, right)), false);
                } else if (op >= Opcodes.I2L && op <= Opcodes.I2S) {
                    convert(s, op);
                }
            }
        }

        return s;
    }

    private State invoke(MethodInsnNode call, State s, int depth) {
        int op = call.getOpcode();
        List<Value> arguments = s.popArguments(call.desc);
        Value receiver = op == Opcodes.INVOKESTATIC ? null : s.pop();
        Type returns = Type.getReturnType(call.desc);

        if (call.name.equals("<init>") && receiver instanceof Value.Uninitialized uninitialized) {
            s.replace(uninitialized, new Value.Constructed(call.owner, arguments));
            return s;
        }

        Resolved target = null;
        if (depth < INLINE_DEPTH && !this.opaque.test(call.owner)) {
            if (receiver == null || op == Opcodes.INVOKESPECIAL && receiver instanceof Value.This) {
                target = this.resolve(call.owner, call.name, call.desc);
            } else if (receiver instanceof Value.This) {
                target = this.resolve(this.root, call.name, call.desc);
                if (target == null) {
                    target = this.resolve(call.owner, call.name, call.desc);
                }
            }
        }

        String key = target == null ? null : target.owner().name + "." + call.name + call.desc;
        if (target != null && this.active.add(key)) {
            try {
                State exit = this.walk(target, s.enter(target.method(), receiver, arguments), depth + 1);
                if (exit == null) {
                    return null;
                }

                s.guards = exit.guards;
                s.writes = exit.writes;
                s.calls = exit.calls;
                if (returns.getSort() != Type.VOID) {
                    s.stack.addAll(exit.stack);
                }
                return s;
            } finally {
                this.active.remove(key);
            }
        }

        Value result = receiver == null
                ? new Value.StaticCall(call.owner, call.name, arguments)
                : new Value.InstanceCall(call.owner, call.name, receiver, arguments);
        if (returns.getSort() == Type.VOID) {
            s.calls.add(new Summary.Invocation(s.condition(), result));
        } else {
            s.push(result, returns.getSize() == 2);
        }
        return s;
    }

    private Value staticField(String owner, String name) {
        if (this.opaque.test(owner)) {
            return new Value.Field(owner, name);
        }

        Map<String, Value> fields = this.staticFields.get(owner);
        if (fields == null) {
            fields = new HashMap<>();
            this.staticFields.put(owner, fields);
            Summary summary = new Summarizer(this.loader, this.classes, this.staticFields, this.opaque).summarize(owner, "<clinit>", "()V");
            for (Summary.Assignment assignment : summary == null ? List.<Summary.Assignment>of() : summary.assignments()) {
                if (assignment.target() == null) {
                    fields.put(assignment.field(), assignment.value());
                }
            }
        }

        Value value = fields.get(name);
        return value != null && !(value instanceof Value.Unknown) ? value : this.statics.staticField(owner, name);
    }

    private Value length(Value array) {
        if (array instanceof Value.Array(_, List<Value> elements)) {
            return Value.literal(elements.size());
        }
        if (array instanceof Value.InstanceField(Value.This _, String _, String field)
                && this.constructed(field) instanceof Value.Array(_, List<Value> elements)) {
            return Value.literal(elements.size());
        }

        return new Value.Operation(Operator.LENGTH, List.of(array));
    }

    private Value constructed(String field) {
        if (this.constructed == null) {
            this.constructed = new HashMap<>();
            ClassNode node = this.classNode(this.root);
            for (MethodNode method : node == null ? List.<MethodNode>of() : node.methods) {
                if (!method.name.equals("<init>")) {
                    continue;
                }

                Summarizer nested = new Summarizer(this.loader, this.classes, this.staticFields, this.opaque);
                nested.constructed = Map.of();
                Summary summary = nested.summarize(this.root, method.name, method.desc);
                for (Summary.Assignment assignment : summary == null ? List.<Summary.Assignment>of() : summary.assignments()) {
                    if (assignment.target() instanceof Value.This) {
                        this.constructed.putIfAbsent(assignment.field(), assignment.value());
                    }
                }
            }
        }

        return this.constructed.get(field);
    }

    private static Value element(Value array, Value index) {
        if (array instanceof Value.Array(_, List<Value> elements)
                && index instanceof Value.Literal(Integer i) && i >= 0 && i < elements.size()) {
            return elements.get(i);
        }

        return new Value.Operation(Operator.INDEX, List.of(array, index));
    }

    private static Value array(Value size, String componentType) {
        if (!(size instanceof Value.Literal(Integer length)) || length < 0 || length > ARRAY_LIMIT) {
            return new Value.Unknown("array");
        }

        Value empty = switch (componentType.charAt(0)) {
            case 'J' -> Value.literal(0L);
            case 'F' -> Value.literal(0f);
            case 'D' -> Value.literal(0d);
            case 'L', '[' -> Value.literal(null);
            default -> ZERO;
        };
        return new Value.Array(componentType, new ArrayList<>(Collections.nCopies(length, empty)));
    }

    private static String primitiveDescriptor(int type) {
        return switch (type) {
            case Opcodes.T_BOOLEAN -> "Z";
            case Opcodes.T_CHAR -> "C";
            case Opcodes.T_FLOAT -> "F";
            case Opcodes.T_DOUBLE -> "D";
            case Opcodes.T_BYTE -> "B";
            case Opcodes.T_SHORT -> "S";
            case Opcodes.T_LONG -> "J";
            default -> "I";
        };
    }

    private static void binary(State s, Operator operator, Kind kind, Kind rightKind) {
        Value right = rightKind.pop(s);
        Value left = kind.pop(s);
        s.push(arithmetic(operator, kind, left, right), kind.wide);
    }

    private static Value arithmetic(Operator operator, Kind kind, Value left, Value right) {
        Value folded = fold(operator, kind, left, right);
        return folded != null ? folded : new Value.Operation(operator, List.of(left, right));
    }

    private static Value negative(Value value, Kind kind) {
        if (value instanceof Value.Literal(Number n)) {
            return switch (kind) {
                case INT -> Value.literal(-n.intValue());
                case LONG -> Value.literal(-n.longValue());
                case FLOAT -> Value.literal(-n.floatValue());
                case DOUBLE -> Value.literal(-n.doubleValue());
            };
        }
        if (value instanceof Value.Operation(Operator operator, List<Value> operands) && operator == Operator.NEGATE) {
            return operands.getFirst();
        }

        return new Value.Operation(Operator.NEGATE, List.of(value));
    }

    private static void convert(State s, int op) {
        Kind from = switch (op) {
            case Opcodes.L2I, Opcodes.L2F, Opcodes.L2D -> Kind.LONG;
            case Opcodes.F2I, Opcodes.F2L, Opcodes.F2D -> Kind.FLOAT;
            case Opcodes.D2I, Opcodes.D2L, Opcodes.D2F -> Kind.DOUBLE;
            default -> Kind.INT;
        };
        Kind to = switch (op) {
            case Opcodes.I2L, Opcodes.F2L, Opcodes.D2L -> Kind.LONG;
            case Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> Kind.FLOAT;
            case Opcodes.I2D, Opcodes.L2D, Opcodes.F2D -> Kind.DOUBLE;
            default -> Kind.INT;
        };

        Value value = from.pop(s);
        Value converted;
        if (value instanceof Value.Literal(Number n)) {
            converted = switch (op) {
                case Opcodes.I2B -> Value.literal((int) (byte) n.intValue());
                case Opcodes.I2C -> Value.literal((int) (char) n.intValue());
                case Opcodes.I2S -> Value.literal((int) (short) n.intValue());
                default -> switch (to) {
                    case INT -> Value.literal(n.intValue());
                    case LONG -> Value.literal(n.longValue());
                    case FLOAT -> Value.literal(n.floatValue());
                    case DOUBLE -> Value.literal(n.doubleValue());
                };
            };
        } else if ((from == Kind.FLOAT || from == Kind.DOUBLE) && (to == Kind.INT || to == Kind.LONG)) {
            converted = new Value.Operation(Operator.TRUNCATE, List.of(value));
        } else {
            converted = value;
        }

        s.push(converted, to.wide);
    }

    private static Value fold(Operator operator, Kind kind, Value left, Value right) {
        if (!(left instanceof Value.Literal(Number x)) || !(right instanceof Value.Literal(Number y))) {
            return null;
        }

        Object result = switch (kind) {
            case INT -> {
                int a = x.intValue();
                int b = y.intValue();
                yield switch (operator) {
                    case ADD -> a + b;
                    case SUBTRACT -> a - b;
                    case MULTIPLY -> a * b;
                    case DIVIDE -> b == 0 ? null : a / b;
                    case REMAINDER -> b == 0 ? null : a % b;
                    case AND -> a & b;
                    case OR -> a | b;
                    case XOR -> a ^ b;
                    case SHIFT_LEFT -> a << b;
                    case SHIFT_RIGHT -> a >> b;
                    case UNSIGNED_SHIFT_RIGHT -> a >>> b;
                    case COMPARE -> Integer.compare(a, b);
                    default -> null;
                };
            }
            case LONG -> {
                long a = x.longValue();
                long b = y.longValue();
                yield switch (operator) {
                    case ADD -> a + b;
                    case SUBTRACT -> a - b;
                    case MULTIPLY -> a * b;
                    case DIVIDE -> b == 0 ? null : a / b;
                    case REMAINDER -> b == 0 ? null : a % b;
                    case AND -> a & b;
                    case OR -> a | b;
                    case XOR -> a ^ b;
                    case SHIFT_LEFT -> a << y.intValue();
                    case SHIFT_RIGHT -> a >> y.intValue();
                    case UNSIGNED_SHIFT_RIGHT -> a >>> y.intValue();
                    case COMPARE -> Long.compare(a, b);
                    default -> null;
                };
            }
            case FLOAT -> {
                float a = x.floatValue();
                float b = y.floatValue();
                yield switch (operator) {
                    case ADD -> a + b;
                    case SUBTRACT -> a - b;
                    case MULTIPLY -> a * b;
                    case DIVIDE -> a / b;
                    case REMAINDER -> a % b;
                    case COMPARE -> Float.compare(a, b);
                    default -> null;
                };
            }
            case DOUBLE -> {
                double a = x.doubleValue();
                double b = y.doubleValue();
                yield switch (operator) {
                    case ADD -> a + b;
                    case SUBTRACT -> a - b;
                    case MULTIPLY -> a * b;
                    case DIVIDE -> a / b;
                    case REMAINDER -> a % b;
                    case COMPARE -> Double.compare(a, b);
                    default -> null;
                };
            }
        };

        return result == null ? null : Value.literal(result);
    }

    private static Value condition(int op, State s) {
        if (op == Opcodes.GOTO) {
            return TRUE;
        }
        if (op == Opcodes.IFNULL || op == Opcodes.IFNONNULL) {
            return compare(op == Opcodes.IFNULL ? Operator.EQUAL : Operator.NOT_EQUAL, s.pop(), Value.literal(null));
        }
        if (op == Opcodes.IF_ACMPEQ || op == Opcodes.IF_ACMPNE) {
            Value right = s.pop();
            return compare(op == Opcodes.IF_ACMPEQ ? Operator.EQUAL : Operator.NOT_EQUAL, s.pop(), right);
        }
        if (op >= Opcodes.IF_ICMPEQ) {
            Value right = s.pop();
            return compare(JUMPS[op - Opcodes.IF_ICMPEQ], s.pop(), right);
        }

        return compare(JUMPS[op - Opcodes.IFEQ], s.pop(), ZERO);
    }

    private static Value compare(Operator operator, Value left, Value right) {
        if (ZERO.equals(right)) {
            if (left instanceof Value.Operation(Operator inner, List<Value> operands) && inner == Operator.COMPARE) {
                return compare(operator, operands.get(0), operands.get(1));
            }

            if ((operator == Operator.EQUAL || operator == Operator.NOT_EQUAL) && isBoolean(left)) {
                return operator == Operator.NOT_EQUAL ? left : negate(left);
            }

            if ((operator == Operator.EQUAL || operator == Operator.NOT_EQUAL)
                    && left instanceof Value.Conditional(Value condition, Value then, Value otherwise)
                    && then instanceof Value.Literal(Integer a) && otherwise instanceof Value.Literal(Integer b)
                    && (a == 0) != (b == 0)) {
                return (operator == Operator.NOT_EQUAL) == (a != 0) ? condition : negate(condition);
            }
        }

        if (left instanceof Value.Literal(Number x) && right instanceof Value.Literal(Number y)) {
            int order = Double.compare(x.doubleValue(), y.doubleValue());
            return Value.literal(switch (operator) {
                case EQUAL -> order == 0;
                case NOT_EQUAL -> order != 0;
                case LESS -> order < 0;
                case LESS_OR_EQUAL -> order <= 0;
                case GREATER -> order > 0;
                default -> order >= 0;
            });
        }

        if (right instanceof Value.Literal(Object nothing) && nothing == null) {
            if (left instanceof Value.Literal(Object other)) {
                return Value.literal((other == null) == (operator == Operator.EQUAL));
            }
            if (left instanceof Value.This || left instanceof Value.Constructed
                    || left instanceof Value.Array || left instanceof Value.Lambda) {
                return Value.literal(operator == Operator.NOT_EQUAL);
            }
        }

        return new Value.Operation(operator, List.of(left, right));
    }

    private static boolean isBoolean(Value value) {
        if (value instanceof Value.InstanceOf || value instanceof Value.Literal(Boolean _)) {
            return true;
        }

        return value instanceof Value.Operation(Operator operator, List<Value> _)
                && operator.compareTo(Operator.EQUAL) >= 0 && operator.compareTo(Operator.LOGICAL_NOT) <= 0;
    }

    private static Value negate(Value condition) {
        if (condition instanceof Value.Literal(Boolean b)) {
            return Value.literal(!b);
        }
        if (condition instanceof Value.Operation(Operator operator, List<Value> operands)) {
            Operator flipped = switch (operator) {
                case EQUAL -> Operator.NOT_EQUAL;
                case NOT_EQUAL -> Operator.EQUAL;
                case LESS -> Operator.GREATER_OR_EQUAL;
                case GREATER_OR_EQUAL -> Operator.LESS;
                case GREATER -> Operator.LESS_OR_EQUAL;
                case LESS_OR_EQUAL -> Operator.GREATER;
                default -> null;
            };
            if (flipped != null) {
                return new Value.Operation(flipped, operands);
            }
            if (operator == Operator.LOGICAL_NOT) {
                return operands.getFirst();
            }
        }

        return new Value.Operation(Operator.LOGICAL_NOT, List.of(condition));
    }

    private static Value all(List<Value> conditions) {
        List<Value> kept = new ArrayList<>(conditions.size());
        for (Value condition : conditions) {
            if (FALSE.equals(condition)) {
                return FALSE;
            }
            if (condition instanceof Value.Operation(Operator operator, List<Value> operands) && operator == Operator.LOGICAL_AND) {
                kept.addAll(operands);
            } else if (!TRUE.equals(condition)) {
                kept.add(condition);
            }
        }

        return switch (kept.size()) {
            case 0 -> TRUE;
            case 1 -> kept.getFirst();
            default -> new Value.Operation(Operator.LOGICAL_AND, List.copyOf(kept));
        };
    }

    private static Value any(List<Value> conditions) {
        List<Value> kept = new ArrayList<>(conditions.size());
        for (Value condition : conditions) {
            if (TRUE.equals(condition)) {
                return TRUE;
            }
            if (FALSE.equals(condition)) {
                continue;
            }

            if (condition instanceof Value.Operation(Operator operator, List<Value> operands) && operator == Operator.LOGICAL_AND) {
                List<Value> rest = new ArrayList<>(operands);
                for (Value earlier : kept) {
                    rest.remove(negate(earlier));
                }
                condition = all(rest);
            }
            if (condition instanceof Value.Operation(Operator operator, List<Value> operands) && operator == Operator.LOGICAL_OR) {
                kept.addAll(operands);
            } else {
                kept.add(condition);
            }
        }

        return switch (kept.size()) {
            case 0 -> FALSE;
            case 1 -> kept.getFirst();
            default -> new Value.Operation(Operator.LOGICAL_OR, List.copyOf(kept));
        };
    }

    private static Boolean truth(Value condition) {
        return condition instanceof Value.Literal(Boolean b) ? b : null;
    }

    private static boolean wide(String descriptor) {
        return descriptor.equals("J") || descriptor.equals("D");
    }

    private static State mergeAll(List<State> states) {
        while (states.size() > 1) {
            int first = 0;
            int second = 1;
            int longest = -1;
            boolean search = states.size() <= MERGE_SEARCH;
            for (int i = 0; search && i < states.size(); i++) {
                for (int j = i + 1; j < states.size(); j++) {
                    int shared = sharedGuards(states.get(i), states.get(j));
                    if (shared > longest) {
                        longest = shared;
                        first = i;
                        second = j;
                    }
                }
            }

            State merged = merge(states.get(first), states.get(second));
            states.remove(second);
            states.set(first, merged);
        }

        return states.getFirst();
    }

    private static int sharedGuards(State a, State b) {
        int shared = 0;
        while (shared < a.guards.size() && shared < b.guards.size() && a.guards.get(shared).equals(b.guards.get(shared))) {
            shared++;
        }
        return shared;
    }

    private static State merge(State a, State b) {
        int prefix = sharedGuards(a, b);

        List<Guard> onlyA = a.guards.subList(prefix, a.guards.size());
        List<Guard> onlyB = b.guards.subList(prefix, b.guards.size());
        List<Guard> guards = new ArrayList<>(a.guards.subList(0, prefix));

        Value choose;
        boolean flip = false;
        if (onlyA.isEmpty()) {
            choose = onlyB.isEmpty() ? TRUE : Guard.all(onlyB);
            flip = !onlyB.isEmpty();
        } else if (onlyB.isEmpty()) {
            choose = Guard.all(onlyA);
        } else if (onlyA.size() == 1 && onlyB.size() == 1 && onlyA.getFirst().fork == onlyB.getFirst().fork
                && onlyA.getFirst().fork != null) {
            Guard first = onlyA.getFirst();
            Guard second = onlyB.getFirst();
            BitSet arms = (BitSet) first.arms.clone();
            arms.or(second.arms);
            if (arms.cardinality() < first.fork.conditions.length) {
                guards.add(new Guard(first.fork, arms));
            }
            flip = first.fork.cost(second.arms) < first.fork.cost(first.arms);
            choose = flip ? second.condition() : first.condition();
        } else {
            choose = Guard.all(onlyA);
            guards.add(new Guard(any(List.of(choose, Guard.all(onlyB)))));
        }

        Value condition = choose;
        BinaryOperator<Value> pick = flip ? (x, y) -> select(condition, y, x) : (x, y) -> select(condition, x, y);

        State merged = new State(new Value[Math.min(a.locals.length, b.locals.length)]);
        merged.guards = guards;
        for (int i = 0; i < merged.locals.length; i++) {
            merged.locals[i] = pick.apply(a.locals[i], b.locals[i]);
        }

        merged.stack = new ArrayList<>(a.stack.size());
        for (int i = 0; i < a.stack.size(); i++) {
            merged.stack.add(i < b.stack.size() ? pick.apply(a.stack.get(i), b.stack.get(i)) : a.stack.get(i));
        }

        merged.writes = new LinkedHashMap<>(a.writes);
        for (Map.Entry<Key, Write> entry : b.writes.entrySet()) {
            merged.writes.putIfAbsent(entry.getKey(), null);
        }
        for (Map.Entry<Key, Write> entry : merged.writes.entrySet()) {
            Write left = a.writes.get(entry.getKey());
            Write right = b.writes.get(entry.getKey());
            Write any = left != null ? left : right;
            Value before = entry.getKey().initial(any.className());
            entry.setValue(new Write(any.className(), pick.apply(
                    left != null ? left.value() : before,
                    right != null ? right.value() : before)));
        }

        merged.calls = new ArrayList<>(a.calls);
        Set<Summary.Invocation> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.addAll(a.calls);
        for (Summary.Invocation call : b.calls) {
            if (seen.add(call)) {
                merged.calls.add(call);
            }
        }

        return merged;
    }

    private static boolean same(Value a, Value b) {
        return a == b || (a instanceof Value.Literal || a instanceof Value.InstanceField || a instanceof Value.Field) && a.equals(b);
    }

    private static Value select(Value choose, Value a, Value b) {
        if (same(a, b)) {
            return a;
        }
        if (a == null || b == null || a == TOP || b == TOP) {
            return null;
        }
        if (TRUE.equals(choose)) {
            return a;
        }
        if (FALSE.equals(choose)) {
            return b;
        }

        if (b instanceof Value.Conditional(Value inner, Value then, Value otherwise) && same(a, then)) {
            return new Value.Conditional(any(List.of(choose, inner)), a, otherwise);
        }
        if (a instanceof Value.Conditional(Value inner, Value then, Value otherwise) && same(otherwise, b)) {
            return new Value.Conditional(all(List.of(choose, inner)), then, b);
        }

        return new Value.Conditional(choose, a, b);
    }

    private Resolved resolve(String owner, String name, String descriptor) {
        return this.resolve(owner, name, descriptor, -1);
    }

    private Resolved resolve(String owner, String name, String descriptor, int arity) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(owner);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }

            ClassNode node = this.classNode(current);
            if (node == null) {
                continue;
            }

            for (MethodNode method : node.methods) {
                if (method.name.equals(name)
                        && (descriptor == null ? (method.access & Opcodes.ACC_BRIDGE) == 0 : descriptor.equals(method.desc))
                        && (arity < 0 || Type.getArgumentTypes(method.desc).length == arity)
                        && method.instructions.size() > 0) {
                    return new Resolved(node, method);
                }
            }

            if (node.superName != null) {
                queue.add(node.superName);
            }
            queue.addAll(node.interfaces);
        }

        return null;
    }

    private ClassNode classNode(String name) {
        if (this.classes.containsKey(name)) {
            return this.classes.get(name);
        }

        ClassNode node = null;
        URL url = this.loader.findResource(name + ".class");
        if (url != null) {
            try (InputStream in = url.openStream()) {
                node = new ClassNode();
                new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
            } catch (IOException e) {
                node = null;
            }
        }

        this.classes.put(name, node);
        return node;
    }

    private record Resolved(ClassNode owner, MethodNode method) {
    }

    private record Key(Value target, String field) {
        Value initial(String className) {
            return this.target == null ? new Value.Field(className, this.field) : new Value.InstanceField(this.target, className, this.field);
        }
    }

    private record Write(String className, Value value) {
    }

    private static final class Fork {
        final Value[] conditions;
        private final boolean fallback;

        Fork(boolean fallback, Value... conditions) {
            this.fallback = fallback;
            this.conditions = conditions;
        }

        int cost(BitSet arms) {
            return arms.cardinality() + (this.fallback && arms.get(this.conditions.length - 1) ? this.conditions.length : 0);
        }
    }

    private static final class Guard {
        final Fork fork;
        final BitSet arms;
        private final Value condition;

        Guard(Fork fork, int arm) {
            this.fork = fork;
            this.arms = new BitSet();
            this.arms.set(arm);
            this.condition = null;
        }

        Guard(Fork fork, BitSet arms) {
            this.fork = fork;
            this.arms = arms;
            this.condition = null;
        }

        Guard(Value condition) {
            this.fork = null;
            this.arms = null;
            this.condition = condition;
        }

        Value condition() {
            if (this.fork == null) {
                return this.condition;
            }

            List<Value> conditions = new ArrayList<>();
            this.arms.stream().forEach(arm -> conditions.add(this.fork.conditions[arm]));
            return any(conditions);
        }

        static Value all(List<Guard> guards) {
            List<Value> conditions = new ArrayList<>(guards.size());
            for (Guard guard : guards) {
                conditions.add(guard.condition());
            }
            return Summarizer.all(conditions);
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof Guard guard && this.fork != null
                    && this.fork == guard.fork && this.arms.equals(guard.arms);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this.fork);
        }
    }

    private enum Kind {
        INT(false),
        LONG(true),
        FLOAT(false),
        DOUBLE(true);

        final boolean wide;

        Kind(boolean wide) {
            this.wide = wide;
        }

        static Kind of(int index) {
            return values()[index];
        }

        Value pop(State s) {
            return this.wide ? s.popWide() : s.pop();
        }
    }

    private static final class State {
        List<Guard> guards = new ArrayList<>();
        Value[] locals;
        List<Value> stack = new ArrayList<>();
        Map<Key, Write> writes = new LinkedHashMap<>();
        List<Summary.Invocation> calls = new ArrayList<>();

        State(Value[] locals) {
            this.locals = locals;
        }

        State copy() {
            State copy = new State(this.locals.clone());
            copy.guards = new ArrayList<>(this.guards);
            copy.stack = new ArrayList<>(this.stack);
            copy.writes = new LinkedHashMap<>(this.writes);
            copy.calls = new ArrayList<>(this.calls);
            return copy;
        }

        State enter(MethodNode method, Value receiver, List<Value> arguments) {
            Value[] locals = new Value[Math.max(method.maxLocals, 1)];
            int slot = 0;
            if (receiver != null) {
                locals[slot++] = receiver;
            }

            Type[] types = Type.getArgumentTypes(method.desc);
            for (int i = 0; i < types.length && i < arguments.size(); i++) {
                locals[slot] = arguments.get(i);
                if (types[i].getSize() == 2) {
                    locals[slot + 1] = TOP;
                }
                slot += types[i].getSize();
            }

            State callee = new State(locals);
            callee.guards = this.guards;
            callee.writes = this.writes;
            callee.calls = this.calls;
            return callee;
        }

        Value condition() {
            return this.guards.isEmpty() ? null : Guard.all(this.guards);
        }

        void push(Value value, boolean wide) {
            this.stack.add(value);
            if (wide) {
                this.stack.add(TOP);
            }
        }

        Value pop() {
            return this.stack.isEmpty() ? new Value.Unknown("stack_underflow") : this.stack.removeLast();
        }

        Value popWide() {
            this.pop();
            return this.pop();
        }

        Value pop(Type type) {
            return type.getSize() == 2 ? this.popWide() : this.pop();
        }

        List<Value> popArguments(String descriptor) {
            Type[] types = Type.getArgumentTypes(descriptor);
            List<Value> arguments = new ArrayList<>(types.length);
            for (int i = types.length - 1; i >= 0; i--) {
                arguments.addFirst(this.pop(types[i]));
            }
            return arguments;
        }

        void dup(int count, int skip) {
            int size = this.stack.size();
            if (size < count + skip) {
                return;
            }
            List<Value> top = new ArrayList<>(this.stack.subList(size - count, size));
            this.stack.addAll(size - count - skip, top);
        }

        Value local(int index) {
            Value value = index < this.locals.length ? this.locals[index] : null;
            return value != null ? value : new Value.Unknown("local_" + index);
        }

        void store(int index, Value value, boolean wide) {
            if (index + (wide ? 1 : 0) >= this.locals.length) {
                this.locals = Arrays.copyOf(this.locals, index + 2);
            }
            this.locals[index] = value;
            if (wide) {
                this.locals[index + 1] = TOP;
            }
        }

        void write(Value target, String className, String field, Value value) {
            this.writes.put(new Key(target, field), new Write(className, value));
        }

        void replace(Value from, Value to) {
            this.stack.replaceAll(value -> value == from ? to : value);
            for (int i = 0; i < this.locals.length; i++) {
                if (this.locals[i] == from) {
                    this.locals[i] = to;
                }
            }
        }
    }

    private static final class Exhausted extends RuntimeException {
        Exhausted() {
            super(null, null, false, false);
        }
    }
}
