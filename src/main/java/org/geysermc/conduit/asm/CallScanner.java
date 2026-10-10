package org.geysermc.conduit.asm;

import org.geysermc.conduit.Trace;
import org.geysermc.conduit.Value;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class CallScanner {
    public record CallMatch(String owner, String name, String descriptor, List<Value> args) {
    }

    @FunctionalInterface
    public interface Predicate {
        boolean matches(String owner, String name, String descriptor);
    }

    @FunctionalInterface
    public interface Sink {
        void accept(CallMatch match);
    }

    private static final int FOLLOW_DEPTH = 4;
    private static final int LOOP_DEPTH = 3;
    private static final int LOOP_LIMIT = 512;

    private final ConduitClassLoader loader;
    private final Predicate predicate;
    private final Sink sink;

    private final Map<String, ClassNode> classCache = new HashMap<>();
    private final Map<String, Value> fieldCache = new HashMap<>();
    private final Map<String, Map<String, Value>> initializing = new HashMap<>();
    private final Map<String, Boolean> reachesMatch = new HashMap<>();
    private final Set<String> resolving = new HashSet<>();
    private List<Value.InstanceCall> trace;

    public CallScanner(ConduitClassLoader loader, Predicate predicate, Sink sink) {
        this.loader = loader;
        this.predicate = predicate;
        this.sink = sink;
    }

    public void scanAll() {
        for (URL url : this.loader.getURLs()) {
            try {
                File file = new File(url.toURI());
                if (file.getName().endsWith(".jar") && !this.loader.isContextJar(url)) {
                    this.scanJar(file);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void scanJar(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }

                try (InputStream in = jar.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(in);
                    ClassNode node = new ClassNode();
                    reader.accept(node, ClassReader.SKIP_FRAMES);
                    this.classCache.putIfAbsent(node.name, node);
                    this.scanClass(node);
                } catch (Throwable ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void scanClass(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                continue;
            }
            try {
                this.interpret(method, null);
            } catch (Throwable ignored) {
            }
        }
    }

    private void interpret(MethodNode method, Map<String, Value> fieldsOut) {
        Deque<Value> stack = new ArrayDeque<>();
        Value[] locals = new Value[Math.max(method.maxLocals, 4)];
        int li = 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            locals[li++] = new Value.Unknown("this");
        }
        Type[] argTypes = Type.getArgumentTypes(method.desc);
        for (int i = 0; i < argTypes.length; i++) {
            if (li < locals.length) {
                locals[li] = new Value.Unknown("arg" + i);
            }
            li += argTypes[i].getSize();
        }

        this.walk(method.instructions.getFirst(), null, stack, locals, fieldsOut, 0);
    }

    private Value walk(AbstractInsnNode from, AbstractInsnNode to, Deque<Value> stack, Value[] locals,
                       Map<String, Value> fieldsOut, int depth) {
        for (AbstractInsnNode insn = from; insn != null && insn != to; insn = insn.getNext()) {
            Loop loop = depth < LOOP_DEPTH ? loopAt(insn, locals) : null;
            if (loop != null) {
                for (int value = loop.from(); value < loop.to(); value++) {
                    if (loop.variable() < locals.length) {
                        locals[loop.variable()] = Value.literal(value);
                    }
                    Value returned = this.walk(loop.body(), loop.increment(), stack, locals, fieldsOut, depth + 1);
                    if (returned != null) {
                        return returned;
                    }
                }

                insn = loop.increment();
                continue;
            }

            int op = insn.getOpcode();
            if (op == Opcodes.ARETURN || op == Opcodes.IRETURN || op == Opcodes.LRETURN
                    || op == Opcodes.FRETURN || op == Opcodes.DRETURN) {
                return stack.isEmpty() ? new Value.Unknown("empty_return") : stack.pop();
            }

            try {
                this.step(insn, stack, locals, fieldsOut);
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private record Loop(int variable, int from, int to, AbstractInsnNode body, AbstractInsnNode increment) {
    }

    // Matches for (int i = a; i < b; i++) at its condition, with constant bounds.
    private static Loop loopAt(AbstractInsnNode insn, Value[] locals) {
        if (insn.getOpcode() != Opcodes.ILOAD) {
            return null;
        }

        int variable = ((VarInsnNode) insn).var;
        AbstractInsnNode bound = insn.getNext();
        if (bound == null) {
            return null;
        }

        if (!(constant(bound) instanceof Value.Literal(Integer limit))) {
            return null;
        }

        AbstractInsnNode test = bound.getNext();
        if (!(test instanceof JumpInsnNode jump)) {
            return null;
        }
        if (jump.getOpcode() != Opcodes.IF_ICMPGE && jump.getOpcode() != Opcodes.IF_ICMPGT) {
            return null;
        }

        if (variable >= locals.length
                || !(locals[variable] instanceof Value.Literal start)
                || !(start.value() instanceof Integer first)) {
            return null;
        }

        AbstractInsnNode increment = null;
        for (AbstractInsnNode step = test.getNext(); step != null; step = step.getNext()) {
            if (step instanceof JumpInsnNode back
                    && back.getOpcode() == Opcodes.GOTO
                    && back.label == labelBefore(insn)) {
                break;
            }

            if (step instanceof IincInsnNode iinc && iinc.var == variable) {
                increment = iinc;
            }
        }

        if (increment == null) {
            return null;
        }

        int last = jump.getOpcode() == Opcodes.IF_ICMPGT ? limit + 1 : limit;
        if (last <= first || last - first > LOOP_LIMIT) {
            return null;
        }
        return new Loop(variable, first, last, test.getNext(), increment);
    }

    private static LabelNode labelBefore(AbstractInsnNode insn) {
        for (AbstractInsnNode previous = insn.getPrevious(); previous != null; previous = previous.getPrevious()) {
            if (previous instanceof LabelNode label) {
                return label;
            }
            if (previous.getOpcode() >= 0) {
                return null;
            }
        }

        return null;
    }

    static Value constant(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op == Opcodes.ACONST_NULL) {
            return Value.literal(null);
        }
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
            return Value.literal(op - Opcodes.ICONST_0);
        }
        if (op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1) {
            return Value.literal((long) (op - Opcodes.LCONST_0));
        }
        if (op >= Opcodes.FCONST_0 && op <= Opcodes.FCONST_2) {
            return Value.literal((float) (op - Opcodes.FCONST_0));
        }
        if (op == Opcodes.DCONST_0 || op == Opcodes.DCONST_1) {
            return Value.literal((double) (op - Opcodes.DCONST_0));
        }
        if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) {
            return Value.literal(((IntInsnNode) insn).operand);
        }
        if (op == Opcodes.LDC) {
            return Value.literal(((LdcInsnNode) insn).cst);
        }
        return null;
    }

    private void step(AbstractInsnNode insn, Deque<Value> stack, Value[] locals, Map<String, Value> fieldsOut) {
        int op = insn.getOpcode();
        if (op < 0) {
            return;
        }

        Value constant = constant(insn);
        if (constant != null) {
            stack.push(constant);
            return;
        }

        switch (op) {
            case Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD ->
                    stack.push(safeLocal(locals, ((VarInsnNode) insn).var));

            case Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE -> {
                VarInsnNode v = (VarInsnNode) insn;
                if (v.var < locals.length) {
                    locals[v.var] = safePop(stack);
                } else {
                    safePop(stack);
                }
            }

            case Opcodes.DUP -> {
                if (!stack.isEmpty()) {
                    stack.push(stack.peek());
                }
            }
            case Opcodes.POP -> safePop(stack);
            case Opcodes.POP2 -> {
                safePop(stack);
                safePop(stack);
            }
            case Opcodes.SWAP -> {
                if (stack.size() >= 2) {
                    Value a = stack.pop();
                    Value b = stack.pop();
                    stack.push(a);
                    stack.push(b);
                }
            }

            case Opcodes.GETSTATIC -> {
                FieldInsnNode fi = (FieldInsnNode) insn;
                stack.push(this.staticField(fi.owner, fi.name));
            }

            case Opcodes.PUTSTATIC -> {
                FieldInsnNode fi = (FieldInsnNode) insn;
                Value v = safePop(stack);
                if (fieldsOut != null) {
                    fieldsOut.put(fi.name, v);
                }
            }

            case Opcodes.GETFIELD -> {
                safePop(stack);
                FieldInsnNode fi = (FieldInsnNode) insn;
                stack.push(new Value.Field(fi.owner, fi.name));
            }

            case Opcodes.PUTFIELD -> {
                safePop(stack);
                safePop(stack);
            }

            case Opcodes.NEW -> stack.push(new Value.Uninitialized(((TypeInsnNode) insn).desc));

            case Opcodes.CHECKCAST -> {
            }
            case Opcodes.INSTANCEOF -> {
                safePop(stack);
                stack.push(new Value.Unknown("instanceof"));
            }

            case Opcodes.INVOKESPECIAL -> {
                MethodInsnNode m = (MethodInsnNode) insn;
                Type[] mArgs = Type.getArgumentTypes(m.desc);
                List<Value> args = popN(stack, mArgs.length);
                Value thisRef = safePop(stack);
                if (m.name.equals("<init>")) {
                    if (!stack.isEmpty() && stack.peek() instanceof Value.Uninitialized u && u.className().equals(m.owner)) {
                        stack.pop();
                        stack.push(new Value.Constructed(m.owner, args));
                    }
                    if (this.predicate.matches(m.owner, m.name, m.desc)) {
                        this.sink.accept(new CallMatch(m.owner, m.name, m.desc, args));
                    }
                } else {
                    if (this.predicate.matches(m.owner, m.name, m.desc)) {
                        List<Value> all = new ArrayList<>();
                        all.add(thisRef);
                        all.addAll(args);
                        this.sink.accept(new CallMatch(m.owner, m.name, m.desc, all));
                    }
                    if (Type.getReturnType(m.desc).getSort() != Type.VOID) {
                        stack.push(new Value.InstanceCall(m.owner, m.name, thisRef, args));
                    }
                }
            }

            case Opcodes.INVOKESTATIC -> {
                MethodInsnNode m = (MethodInsnNode) insn;
                Type[] mArgs = Type.getArgumentTypes(m.desc);
                List<Value> args = popN(stack, mArgs.length);
                boolean matched = this.predicate.matches(m.owner, m.name, m.desc);
                if (matched) {
                    this.sink.accept(new CallMatch(m.owner, m.name, m.desc, args));
                }

                if (Type.getReturnType(m.desc).getSort() != Type.VOID) {
                    Value inlined = matched ? null : this.evaluate(m.owner, m.name, m.desc, null, args);
                    stack.push(inlined != null ? inlined : new Value.StaticCall(m.owner, m.name, args));
                } else if (!matched && this.reachesMatch(m.owner, m.name, m.desc, FOLLOW_DEPTH)) {
                    this.evaluate(m.owner, m.name, m.desc, null, args);
                }
            }

            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE -> {
                MethodInsnNode m = (MethodInsnNode) insn;
                Type[] mArgs = Type.getArgumentTypes(m.desc);
                List<Value> args = popN(stack, mArgs.length);
                Value receiver = safePop(stack);
                if (this.predicate.matches(m.owner, m.name, m.desc)) {
                    List<Value> all = new ArrayList<>();
                    all.add(receiver);
                    all.addAll(args);
                    this.sink.accept(new CallMatch(m.owner, m.name, m.desc, all));
                }

                Value.InstanceCall call = new Value.InstanceCall(m.owner, m.name, receiver, args);
                if (this.trace != null) {
                    this.trace.add(call);
                }
                if (Type.getReturnType(m.desc).getSort() != Type.VOID) {
                    stack.push(call);
                }
            }

            case Opcodes.INVOKEDYNAMIC -> {
                InvokeDynamicInsnNode id = (InvokeDynamicInsnNode) insn;
                Type[] dArgs = Type.getArgumentTypes(id.desc);
                List<Value> captures = popN(stack, dArgs.length);
                if ("java/lang/invoke/StringConcatFactory".equals(id.bsm.getOwner())) {
                    stack.push(concat(id, captures));
                    return;
                }
                if (Type.getReturnType(id.desc).getSort() != Type.VOID) {
                    Value.Lambda lambda;
                    if ("java/lang/invoke/LambdaMetafactory".equals(id.bsm.getOwner())
                            && id.bsmArgs != null && id.bsmArgs.length >= 2
                            && id.bsmArgs[1] instanceof Handle impl) {
                        boolean implStatic = impl.getTag() == Opcodes.H_INVOKESTATIC;
                        lambda = new Value.Lambda(
                                id.name,
                                id.desc,
                                impl.getOwner(),
                                impl.getName(),
                                impl.getDesc(),
                                implStatic,
                                captures
                        );
                    } else {
                        lambda = new Value.Lambda(id.name, id.desc, null, null, null, false, captures);
                    }
                    stack.push(lambda);
                }
            }

            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
                    Opcodes.IFNULL, Opcodes.IFNONNULL, Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> safePop(stack);
            case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
                    Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> {
                safePop(stack);
                safePop(stack);
            }
            case Opcodes.GOTO, Opcodes.RETURN -> {
            }

            case Opcodes.ATHROW -> safePop(stack);

            case Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD, Opcodes.IALOAD,
                    Opcodes.FALOAD, Opcodes.LALOAD, Opcodes.DALOAD -> {
                safePop(stack);
                safePop(stack);
                stack.push(new Value.Unknown("aload"));
            }

            case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM,
                    Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM,
                    Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM,
                    Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM,
                    Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR,
                    Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR, Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR,
                    Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG -> {
                Value right = safePop(stack);
                Value left = safePop(stack);
                Value folded = fold(op, left, right);
                stack.push(folded != null ? folded : new Value.Unknown("arith"));
            }
            case Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG,
                    Opcodes.I2L, Opcodes.I2F, Opcodes.I2D, Opcodes.L2I, Opcodes.L2F, Opcodes.L2D,
                    Opcodes.F2I, Opcodes.F2L, Opcodes.F2D, Opcodes.D2I, Opcodes.D2L, Opcodes.D2F,
                    Opcodes.I2B, Opcodes.I2C, Opcodes.I2S -> {
                Value converted = convert(op, safePop(stack));
                stack.push(converted != null ? converted : new Value.Unknown("conv"));
            }
            case Opcodes.ARRAYLENGTH -> {
                safePop(stack);
                stack.push(new Value.Unknown("conv"));
            }

            case Opcodes.ANEWARRAY, Opcodes.NEWARRAY -> {
                safePop(stack);
                stack.push(new Value.Unknown("newarray"));
            }
            case Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE,
                    Opcodes.IASTORE, Opcodes.FASTORE, Opcodes.LASTORE, Opcodes.DASTORE -> {
                safePop(stack);
                safePop(stack);
                safePop(stack);
            }

            default -> {
            }
        }
    }

    Value staticField(String owner, String name) {
        String key = owner + "." + name;
        Value cached = this.fieldCache.get(key);
        if (cached != null) {
            return cached;
        }

        Map<String, Value> initializing = this.initializing.get(owner);
        if (initializing != null) {
            Value assigned = initializing.get(name);
            return assigned != null ? assigned : new Value.Field(owner, name);
        }

        ClassNode cn = this.resolveClass(owner);
        MethodNode clinit = cn == null ? null : findMethod(cn, "<clinit>", null);
        if (clinit != null) {
            Map<String, Value> fields = new HashMap<>();
            this.initializing.put(owner, fields);
            try {
                this.interpret(clinit, fields);
            } catch (Throwable ignored) {
            } finally {
                this.initializing.remove(owner);
            }

            fields.forEach((field, value) -> this.fieldCache.putIfAbsent(owner + "." + field, value));
        }

        return this.fieldCache.computeIfAbsent(key, ignored -> new Value.Field(owner, name));
    }

    Trace traceCall(String owner, String name, Value receiver, List<Value> args) {
        List<Value.InstanceCall> outer = this.trace;
        this.trace = new ArrayList<>();
        try {
            Value returned = this.evaluate(owner, name, null, receiver, args);
            return new Trace(returned, List.copyOf(this.trace), this.loader);
        } finally {
            this.trace = outer;
        }
    }

    private Value evaluate(String owner, String name, String desc, Value receiver, List<Value> args) {
        String key = "call:" + owner + "." + name + (desc != null ? desc : "/" + args.size());
        if (!this.resolving.add(key)) {
            return null;
        }
        try {
            ClassNode cn = this.resolveClass(owner);
            if (cn == null) {
                return null;
            }
            MethodNode target = desc != null ? findMethod(cn, name, desc) : findMethod(cn, name, args.size());
            // A lambda's implementation also takes the functional interface's arguments, which
            // the call site never passed.
            if (target == null && desc == null) {
                target = findMethod(cn, name, null);
            }
            if (target == null) {
                return null;
            }

            Deque<Value> stack = new ArrayDeque<>();
            Value[] locals = new Value[Math.max(target.maxLocals, args.size() * 2 + 4)];
            int slot = 0;
            if ((target.access & Opcodes.ACC_STATIC) == 0) {
                locals[slot++] = receiver != null ? receiver : new Value.Unknown("this");
            }
            Type[] argTypes = Type.getArgumentTypes(target.desc);
            for (int i = 0; i < argTypes.length && i < args.size(); i++) {
                if (slot < locals.length) {
                    locals[slot] = args.get(i);
                }
                slot += argTypes[i].getSize();
            }

            Value returned = this.walk(target.instructions.getFirst(), null, stack, locals, null, 0);
            return returned instanceof Value.Unknown unknown && "empty_return".equals(unknown.reason())
                    ? null
                    : returned;
        } finally {
            this.resolving.remove(key);
        }
    }

    private boolean reachesMatch(String owner, String name, String desc, int depth) {
        if (depth <= 0) {
            return false;
        }

        String key = depth + ":" + owner + "." + name + desc;
        Boolean cached = this.reachesMatch.get(key);
        if (cached != null) {
            return cached;
        }
        this.reachesMatch.put(key, false);

        ClassNode cn = this.resolveClass(owner);
        MethodNode target = cn == null ? null : findMethod(cn, name, desc);
        boolean found = false;
        for (AbstractInsnNode insn = target == null ? null : target.instructions.getFirst();
             insn != null && !found; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)) {
                continue;
            }
            found = this.predicate.matches(call.owner, call.name, call.desc)
                    || this.reachesMatch(call.owner, call.name, call.desc, depth - 1);
        }

        this.reachesMatch.put(key, found);
        return found;
    }

    private ClassNode resolveClass(String name) {
        // Accepts binary names too, which is what Conduit.Implementation hands out.
        String internalName = name.replace('.', '/');
        if (this.classCache.containsKey(internalName)) {
            return this.classCache.get(internalName);
        }
        URL url = this.loader.findResource(internalName + ".class");
        if (url == null) {
            this.classCache.put(internalName, null);
            return null;
        }
        try (InputStream in = url.openStream()) {
            ClassReader reader = new ClassReader(in);
            ClassNode node = new ClassNode();
            reader.accept(node, ClassReader.SKIP_FRAMES);
            this.classCache.put(internalName, node);
            return node;
        } catch (IOException e) {
            this.classCache.put(internalName, null);
            return null;
        }
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) {
                return m;
            }
        }
        return null;
    }

    private static MethodNode findMethod(ClassNode cn, String name, int arity) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && Type.getArgumentTypes(m.desc).length == arity) {
                return m;
            }
        }
        return null;
    }

    private static Value fold(int op, Value left, Value right) {
        if (!(left instanceof Value.Literal a) || !(right instanceof Value.Literal b)) {
            return null;
        }
        if (!(a.value() instanceof Number x) || !(b.value() instanceof Number y)) {
            return null;
        }

        return switch (op) {
            case Opcodes.IADD -> Value.literal(x.intValue() + y.intValue());
            case Opcodes.ISUB -> Value.literal(x.intValue() - y.intValue());
            case Opcodes.IMUL -> Value.literal(x.intValue() * y.intValue());
            case Opcodes.IDIV -> y.intValue() == 0 ? null : Value.literal(x.intValue() / y.intValue());
            case Opcodes.IREM -> y.intValue() == 0 ? null : Value.literal(x.intValue() % y.intValue());
            case Opcodes.FADD -> Value.literal(x.floatValue() + y.floatValue());
            case Opcodes.FSUB -> Value.literal(x.floatValue() - y.floatValue());
            case Opcodes.FMUL -> Value.literal(x.floatValue() * y.floatValue());
            case Opcodes.FDIV -> y.floatValue() == 0f ? null : Value.literal(x.floatValue() / y.floatValue());
            case Opcodes.DADD -> Value.literal(x.doubleValue() + y.doubleValue());
            case Opcodes.DSUB -> Value.literal(x.doubleValue() - y.doubleValue());
            case Opcodes.DMUL -> Value.literal(x.doubleValue() * y.doubleValue());
            case Opcodes.DDIV -> y.doubleValue() == 0 ? null : Value.literal(x.doubleValue() / y.doubleValue());
            case Opcodes.LADD -> Value.literal(x.longValue() + y.longValue());
            case Opcodes.LSUB -> Value.literal(x.longValue() - y.longValue());
            case Opcodes.LMUL -> Value.literal(x.longValue() * y.longValue());
            default -> null;
        };
    }

    private static Value convert(int op, Value value) {
        if (!(value instanceof Value.Literal literal) || !(literal.value() instanceof Number n)) {
            return null;
        }

        return switch (op) {
            case Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> Value.literal(n.floatValue());
            case Opcodes.I2D, Opcodes.L2D, Opcodes.F2D -> Value.literal(n.doubleValue());
            case Opcodes.I2L, Opcodes.F2L, Opcodes.D2L -> Value.literal(n.longValue());
            case Opcodes.L2I, Opcodes.F2I, Opcodes.D2I, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S ->
                    Value.literal(n.intValue());
            case Opcodes.INEG -> Value.literal(-n.intValue());
            case Opcodes.FNEG -> Value.literal(-n.floatValue());
            case Opcodes.DNEG -> Value.literal(-n.doubleValue());
            case Opcodes.LNEG -> Value.literal(-n.longValue());
            default -> null;
        };
    }

    // \u0001 marks an argument in the concat recipe, \u0002 a constant from the bootstrap.
    static Value concat(InvokeDynamicInsnNode insn, List<Value> arguments) {
        StringBuilder out = new StringBuilder();
        String recipe = insn.bsmArgs != null && insn.bsmArgs.length > 0 && insn.bsmArgs[0] instanceof String text
                ? text
                : null;

        if (recipe == null) {
            for (Value argument : arguments) {
                if (!(argument instanceof Value.Literal literal) || literal.value() == null) {
                    return new Value.Unknown("concat");
                }
                out.append(literal.value());
            }

            return Value.literal(out.toString());
        }

        int argument = 0;
        int constant = 1;
        for (int i = 0; i < recipe.length(); i++) {
            char c = recipe.charAt(i);
            if (c == '\u0001') {
                if (argument >= arguments.size()
                        || !(arguments.get(argument) instanceof Value.Literal literal)
                        || literal.value() == null) {
                    return new Value.Unknown("concat");
                }
                out.append(literal.value());
                argument++;
            } else if (c == '\u0002') {
                if (insn.bsmArgs == null || constant >= insn.bsmArgs.length) {
                    return new Value.Unknown("concat");
                }
                out.append(insn.bsmArgs[constant++]);
            } else {
                out.append(c);
            }
        }

        return Value.literal(out.toString());
    }

    static Value safePop(Deque<Value> stack) {
        return stack.isEmpty() ? new Value.Unknown("stack_underflow") : stack.pop();
    }

    static Value safeLocal(Value[] locals, int idx) {
        return idx >= 0 && idx < locals.length && locals[idx] != null ? locals[idx] : new Value.Unknown("local_" + idx);
    }

    static List<Value> popN(Deque<Value> stack, int n) {
        List<Value> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.addFirst(safePop(stack));
        }
        return out;
    }
}
