package org.geysermc.conduit.asm;

import org.geysermc.conduit.Argument;
import org.geysermc.conduit.Conduit;
import org.geysermc.conduit.Trace;
import org.geysermc.conduit.Value;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class ConduitWrapper {
    private static final Map<Class<?>, Map<String, Integer>> METHOD_ORDER = new ConcurrentHashMap<>();
    private static final String THIS_MARKER = "__conduit_this__";
    private static final Object NOT_BOUND = new Object();
    private static final Set<String> STRING_TYPES = Set.of("java/lang/String", "java/lang/Object", "java/lang/CharSequence");
    private static final Set<String> NUMBER_TYPES = Set.of("java/lang/Object", "java/lang/Number", "java/lang/Integer", "java/lang/Long",
            "java/lang/Float", "java/lang/Double", "java/lang/Short", "java/lang/Byte", "java/lang/Boolean", "java/lang/Character");
    private static final Set<String> MAP_FACTORIES = Set.of("java/util/Map", "com/google/common/collect/ImmutableMap");

    private ConduitWrapper() {
    }

    public static Object coerce(Value value, Class<?> target, ClassLoader sourceLoader) {
        return coerce(value, target, target, sourceLoader);
    }

    public static Object coerce(Value value, Class<?> target, java.lang.reflect.Type genericTarget, ClassLoader sourceLoader) {
        return coerce(value, target, genericTarget, sourceLoader, List.of());
    }

    public static Object coerce(Value value, Class<?> target, java.lang.reflect.Type genericTarget,
                                ClassLoader sourceLoader, List<Value.InstanceCall> calls) {
        if (value == null) {
            return null;
        }

        if (target.isInterface() && target.isAnnotationPresent(Conduit.class)) {
            if (value instanceof Value.Literal || value instanceof Value.Unknown) {
                return null;
            }

            Trace entry = registryEntry(value, sourceLoader);
            return entry == null
                    ? proxy(target, new Handler(value, target, sourceLoader, calls, null))
                    : proxy(target, new Handler(entry.returned(), target, sourceLoader, entry.calls(), null));
        }

        if (Supplier.class.isAssignableFrom(target)) {
            Class<?> suppliedType = inferSupplierType(genericTarget);
            return (Supplier<?>) () -> {
                Value supplied = resolveSuppliedValue(value);
                if (supplied == null) {
                    return null;
                }

                Trace trace = traceCall(supplied, sourceLoader);
                if (trace != null && trace.returned() == null) {
                    // A call that can't be followed still says what was called.
                    trace = new Trace(supplied, trace.calls(), sourceLoader);
                }
                if (suppliedType == Trace.class) {
                    return trace != null ? trace : new Trace(supplied, List.of(), sourceLoader);
                }

                if (trace != null && trace.returned() != null) {
                    supplied = trace.returned();
                }
                return coerce(supplied, suppliedType, suppliedType, sourceLoader,
                        trace == null ? calls : trace.calls());
            };
        }

        if (List.class.isAssignableFrom(target)) {
            Class<?> element = inferSupplierType(genericTarget);
            List<Value> values = new ArrayList<>();
            for (Value each : value instanceof Value.Multiple multiple ? multiple.values() : List.of(value)) {
                values.addAll(lookups(each));
            }

            List<Object> coerced = new ArrayList<>(values.size());
            for (Value each : values) {
                Object one = coerce(each, element, element, sourceLoader, calls);
                if (one != null && !coerced.contains(one)) {
                    coerced.add(one);
                }
            }

            return List.copyOf(coerced);
        }

        if (value instanceof Value.Literal l) {
            Object raw = l.value();
            if (raw == null) {
                return null;
            }

            if (raw instanceof Number number) {
                return widen(number, target);
            }
            return raw;
        }

        if (target.isAssignableFrom(Value.class)) {
            return value;
        }

        return null;
    }

    // A lookup into a map built from constants can return any of its values.
    private static List<Value> lookups(Value value) {
        if (!(value instanceof Value.InstanceCall(String _, String name, Value map, List<Value> args))
                || !(name.equals("get") || name.equals("getOrDefault"))
                || !(map instanceof Value.StaticCall(String owner, String factory, List<Value> entries))
                || !factory.equals("of") || !MAP_FACTORIES.contains(owner)) {
            return List.of(value);
        }

        List<Value> values = new ArrayList<>();
        for (int i = 1; i < entries.size(); i += 2) {
            values.add(entries.get(i));
        }
        if (name.equals("getOrDefault") && args.size() == 2) {
            values.add(args.get(1));
        }
        return values;
    }

    private static Object widen(Number number, Class<?> target) {
        String name = target.isPrimitive() ? target.getName() : target.getSimpleName().toLowerCase();
        return switch (name) {
            case "int", "integer" -> number.intValue();
            case "long" -> number.longValue();
            case "float" -> number.floatValue();
            case "double" -> number.doubleValue();
            case "short" -> number.shortValue();
            case "byte" -> number.byteValue();
            case "boolean" -> number.intValue() != 0;
            case "character", "char" -> (char) number.intValue();
            default -> number;
        };
    }

    public static Object withTypeArgument(Object conduit, String className) {
        if (conduit == null || !Proxy.isProxyClass(conduit.getClass())
                || !(Proxy.getInvocationHandler(conduit) instanceof Handler handler)) {
            return conduit;
        }

        Handler specialized = new Handler(handler.value, handler.conduitType, handler.sourceLoader, handler.calls,
                className.replace('.', '/'));
        return proxy(handler.conduitType, specialized);
    }

    private static Object proxy(Class<?> conduitType, Handler handler) {
        return Proxy.newProxyInstance(conduitType.getClassLoader(), new Class<?>[]{conduitType}, handler);
    }

    private static Trace registryEntry(Value value, ClassLoader loader) {
        if (!(value instanceof Value.InstanceCall get) || !"get".equals(get.methodName()) || !get.args().isEmpty()) {
            return null;
        }

        List<Value> registration = Handler.argsOf(get.receiver());
        if (registration == null) {
            return null;
        }

        Value.Lambda supplier = null;
        for (Value argument : registration) {
            if (argument instanceof Value.Lambda lambda && "get".equals(lambda.name())) {
                if (supplier != null) {
                    return null;
                }

                supplier = lambda;
            }
        }

        Value supplied = supplier == null ? null : resolveSuppliedValue(supplier);
        if (supplied == null) {
            return null;
        }

        Trace trace = traceCall(supplied, loader);
        return trace != null && trace.returned() != null ? trace : new Trace(supplied, List.of(), loader);
    }

    private static Class<?> inferSupplierType(java.lang.reflect.Type genericTarget) {
        if (!(genericTarget instanceof ParameterizedType parameterized)) {
            return Object.class;
        }
        java.lang.reflect.Type[] args = parameterized.getActualTypeArguments();
        if (args.length != 1) {
            return Object.class;
        }

        java.lang.reflect.Type arg = args[0];
        if (arg instanceof Class<?> cls) {
            return cls;
        }
        if (arg instanceof ParameterizedType nested && nested.getRawType() instanceof Class<?> cls) {
            return cls;
        }
        return Object.class;
    }

    private static Value resolveSuppliedValue(Value value) {
        if (value instanceof Value.Lambda lambda) {
            if (lambda.implOwner() == null || lambda.implMethod() == null) {
                return null;
            }

            if ("<init>".equals(lambda.implMethod())) {
                return new Value.Constructed(lambda.implOwner(), lambda.captures());
            }

            if (lambda.implStatic()) {
                return new Value.StaticCall(lambda.implOwner(), lambda.implMethod(), lambda.captures());
            }

            if (lambda.captures().isEmpty()) {
                return null;
            }
            Value receiver = lambda.captures().get(0);
            List<Value> args = new ArrayList<>();
            for (int i = 1; i < lambda.captures().size(); i++) {
                args.add(lambda.captures().get(i));
            }

            if (args.isEmpty() && receiver instanceof Value.Lambda captured) {
                return resolveSuppliedValue(captured);
            }

            return new Value.InstanceCall(lambda.implOwner(), lambda.implMethod(), receiver, args);
        }

        return value;
    }

    private static Trace traceCall(Value value, ClassLoader loader) {
        if (!(loader instanceof ConduitClassLoader conduitLoader)) {
            return null;
        }

        CallScanner evaluator = new CallScanner(conduitLoader, (owner, name, desc) -> false, match -> {});
        if (value instanceof Value.StaticCall call) {
            return evaluator.traceCall(call.className(), call.methodName(), null, call.args());
        }
        if (value instanceof Value.InstanceCall call) {
            return evaluator.traceCall(call.className(), call.methodName(), call.receiver(), call.args());
        }

        return null;
    }

    private static Map<String, Integer> methodOrder(Class<?> iface) {
        return METHOD_ORDER.computeIfAbsent(iface, ConduitWrapper::computeMethodOrder);
    }

    private static Map<String, Integer> computeMethodOrder(Class<?> iface) {
        Map<String, Integer> order = new HashMap<>();
        String resource = iface.getName().replace('.', '/') + ".class";
        try (InputStream in = iface.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return order;
            }

            List<String> methods = new ArrayList<>();
            ClassReader reader = new ClassReader(in);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                    if (!name.equals("<init>") && !name.equals("<clinit>")) {
                        methods.add(name);
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);

            for (int i = 0; i < methods.size(); i++) {
                order.putIfAbsent(methods.get(i), i);
            }
        } catch (Throwable t) {
        }

        return order;
    }

    private static final class Handler implements InvocationHandler {
        private final Value value;
        private final Class<?> conduitType;
        private final ClassLoader sourceLoader;
        private final List<Value.InstanceCall> calls;
        private final String typeArgument;

        Handler(Value value, Class<?> conduitType, ClassLoader sourceLoader, List<Value.InstanceCall> calls,
                String typeArgument) {
            this.value = value;
            this.conduitType = conduitType;
            this.sourceLoader = sourceLoader;
            this.calls = calls;
            this.typeArgument = typeArgument;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            Object resolved = this.resolve(proxy, method, args);
            if (resolved != null) {
                return resolved;
            }

            // Unresolved collections are empty rather than null, so callers never have to guard.
            if (List.class.isAssignableFrom(method.getReturnType())) {
                return List.of();
            }
            if (Set.class.isAssignableFrom(method.getReturnType())) {
                return Set.of();
            }
            if (Map.class.isAssignableFrom(method.getReturnType())) {
                return Map.of();
            }

            return null;
        }

        private Object resolve(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> this.value.toString();
                    case "hashCode" -> this.value.hashCode();
                    case "equals" -> args != null
                            && args.length == 1
                            && args[0] != null
                            && Proxy.isProxyClass(args[0].getClass())
                            && Proxy.getInvocationHandler(args[0]) instanceof Handler h
                            && h.value.equals(this.value);
                    default -> null;
                };
            }

            if (method.isAnnotationPresent(Conduit.Implementation.class)) {
                return implementationOf(this.value);
            }

            Object bound = this.resolveBinds(method);
            if (bound != NOT_BOUND) {
                return bound;
            }

            Argument.Index indexArg = method.getAnnotation(Argument.Index.class);

            org.geysermc.conduit.Method.Calls callsOn = method.getAnnotation(org.geysermc.conduit.Method.Calls.class);
            org.geysermc.conduit.Method.Chain chain = method.getAnnotation(org.geysermc.conduit.Method.Chain.class);
            if (callsOn != null || chain != null) {
                List<Value.InstanceCall> matches = callsOn != null
                        ? this.callsOn(callsOn.value())
                        : this.chainedCalls(chain.value());
                return this.resolveCalls(method, matches, indexArg);
            }

            Argument.Named namedArg = method.getAnnotation(Argument.Named.class);
            org.geysermc.conduit.Method.Index methodIndex = method.getAnnotation(org.geysermc.conduit.Method.Index.class);
            boolean named = namedArg != null && !namedArg.value().isEmpty();
            String name = named ? namedArg.value() : method.getName();

            if (methodIndex != null) {
                Value methodValue = resolveConduitMethod(this.value, this.conduitType, method.getName(), this.sourceLoader, new HashSet<>());
                if (methodValue != null) {
                    return this.view(methodValue, method);
                }

                Value indexedMethod = resolveConduitMethodByIndex(this.value, this.conduitType, methodIndex.value(), this.sourceLoader, new HashSet<>());
                if (indexedMethod != null) {
                    return this.view(indexedMethod, method);
                }

                List<Value> args0 = argsOf(this.value);
                int fallbackIndex = methodIndex.value();
                if (fallbackIndex >= 0 && args0 != null && fallbackIndex < args0.size()) {
                    return this.view(args0.get(fallbackIndex), method);
                }

                if (indexArg == null && !named) {
                    return null;
                }
            }

            if (indexArg != null) {
                List<Value> args0 = argsOf(this.value);
                int index = indexArg.value();
                if (index < 0) {
                    return null;
                }
                if (args0 == null || index >= args0.size()) {
                    return null;
                }

                Object direct = this.view(args0.get(index), method);
                if (direct != null) {
                    return direct;
                }

                Value regrouped = regroup(args0, index, method.getReturnType());
                return regrouped == null ? null : this.view(regrouped, method);
            }

            Value member = resolveConduitMember(this.value, this.conduitType, name, this.sourceLoader, new HashSet<>(), this.typeArgument);
            if (member != null) {
                return this.view(member, method);
            }

            if (named) {
                return null;
            }

            Integer index = methodOrder(method.getDeclaringClass()).get(name);
            if (index != null) {
                List<Value> args0 = argsOf(this.value);
                if (args0 != null && index < args0.size()) {
                    return this.view(args0.get(index), method);
                }
            }

            return null;
        }

        private static Value regroup(List<Value> args, int index, Class<?> conduitType) {
            Conduit conduit = conduitType.getAnnotation(Conduit.class);
            if (conduit == null || !conduitType.isInterface()) {
                return null;
            }

            Map<Integer, Class<?>> slots = declaredSlots(conduitType);
            int arity = 0;
            for (int slot : slots.keySet()) {
                arity = Math.max(arity, slot + 1);
            }
            if (arity == 0 || slots.size() != arity || index + arity > args.size()) {
                return null;
            }

            List<Value> sliced = new ArrayList<>(args.subList(index, index + arity));
            for (Map.Entry<Integer, Class<?>> slot : slots.entrySet()) {
                if (!matchesSlot(sliced.get(slot.getKey()), slot.getValue())) {
                    return null;
                }
            }

            return new Value.Constructed(conduit.value().replace('.', '/'), sliced);
        }

        private static Map<Integer, Class<?>> declaredSlots(Class<?> conduitType) {
            Map<Integer, Class<?>> slots = new HashMap<>();
            for (Method method : conduitType.getMethods()) {
                Argument.Index index = method.getAnnotation(Argument.Index.class);
                if (index != null && index.value() >= 0) {
                    slots.putIfAbsent(index.value(), method.getReturnType());
                }
            }

            return slots;
        }

        private static boolean matchesSlot(Value value, Class<?> declared) {
            if (declared.isInterface() && declared.isAnnotationPresent(Conduit.class)) {
                String internal = declared.getAnnotation(Conduit.class).value().replace('.', '/');
                return value instanceof Value.Constructed constructed && constructed.className().equals(internal);
            }

            if (!(value instanceof Value.Literal literal) || literal.value() == null) {
                return false;
            }

            Object raw = literal.value();

            return switch (declared.getName()) {
                case "int", "short", "byte", "char", "boolean" -> raw instanceof Integer;
                case "long" -> raw instanceof Long;
                case "float" -> raw instanceof Float;
                case "double" -> raw instanceof Double;
                default -> declared.isInstance(raw);
            };
        }

        private Object resolveBinds(Method method) {
            Conduit.Bind[] binds = method.getAnnotationsByType(Conduit.Bind.class);
            boolean described = false;
            Object empty = null;
            for (Conduit.Bind bind : binds) {
                described = true;
                Object resolved = this.resolveBind(bind, method);
                if (resolved == null) {
                    continue;
                }

                boolean blank = resolved instanceof List<?> list && list.isEmpty()
                        || resolved instanceof Map<?, ?> map && map.isEmpty();
                if (!blank) {
                    return resolved;
                }
                if (empty == null) {
                    empty = resolved;
                }
            }

            return described ? empty : NOT_BOUND;
        }

        private Object resolveBind(Conduit.Bind bind, Method method) {
            Value base = this.value;
            if (bind.from() >= 0) {
                List<Value> own = argsOf(this.value);
                if (own == null || bind.from() >= own.size()) {
                    return null;
                }
                base = own.get(bind.from());
            }

            List<Value.InstanceCall> matches;
            if (!bind.calls().isEmpty()) {
                matches = callsOn(this.calls, base, bind.calls());
            } else if (!bind.chain().isEmpty()) {
                matches = chainedCalls(base, bind.chain());
            } else {
                List<Value> own = argsOf(base);
                if (own == null) {
                    return null;
                }
                if (!bind.descriptor().isEmpty() && !fits(own, bind.descriptor())) {
                    return null;
                }

                if (!bind.producer().isEmpty() && !producedBy(base, bind.producer())) {
                    return null;
                }

                return this.shape(method, List.of(own), bind, List.of(base));
            }

            if (!bind.descriptor().isEmpty()) {
                matches = matches.stream().filter(call -> fits(call.args(), bind.descriptor())).toList();
            }

            // A builder setter applies to what comes after it, so a single value takes the call nearest it in the chain.
            Class<?> returned = method.getReturnType();
            if (!bind.chain().isEmpty() && matches.size() > 1
                    && !List.class.isAssignableFrom(returned) && !Map.class.isAssignableFrom(returned)) {
                matches = List.of(matches.getLast());
            }

            List<List<Value>> arguments = new ArrayList<>(matches.size());
            for (Value.InstanceCall call : matches) {
                arguments.add(call.args());
            }

            return this.shape(method, arguments, bind, List.copyOf(matches));
        }

        // Overloads often share an argument count, so constants are checked against the parameter types too.
        private static boolean fits(List<Value> arguments, String descriptor) {
            Type[] parameters = Type.getArgumentTypes(descriptor);
            if (parameters.length != arguments.size()) {
                return false;
            }

            for (int i = 0; i < parameters.length; i++) {
                if (!(arguments.get(i) instanceof Value.Literal(Object literal)) || literal == null) {
                    continue;
                }

                boolean primitive = parameters[i].getSort() != Type.OBJECT && parameters[i].getSort() != Type.ARRAY;
                String type = parameters[i].getInternalName();
                if (literal instanceof String ? primitive || !STRING_TYPES.contains(type)
                        : literal instanceof Number && !primitive && !NUMBER_TYPES.contains(type)) {
                    return false;
                }
            }
            return true;
        }

        private Object shape(Method method, List<List<Value>> candidates, Conduit.Bind bind, List<Value> selves) {
            Class<?> returnType = method.getReturnType();
            if (bind.arg() < 0 && (returnType == boolean.class || returnType == Boolean.class)) {
                return !candidates.isEmpty();
            }

            boolean list = List.class.isAssignableFrom(returnType);
            boolean map = Map.class.isAssignableFrom(returnType);
            java.lang.reflect.Type[] parameters = typeArguments(method.getGenericReturnType());
            Class<?> target = list ? inferSupplierType(method.getGenericReturnType())
                    : map ? rawType(parameters.length > 1 ? parameters[1] : null)
                    : returnType;

            List<Object> out = new ArrayList<>();
            Map<Object, Object> entries = new LinkedHashMap<>();
            for (int i = 0; i < candidates.size(); i++) {
                List<Value> arguments = candidates.get(i);

                Value selected = bind.arg() < 0 ? selves.get(i) : select(arguments, bind, target);
                Object coerced = selected == null ? null
                        : coerce(selected, target, list || map ? target : method.getGenericReturnType(),
                        this.sourceLoader, this.calls);
                if (coerced == null) {
                    continue;
                }

                if (map) {
                    Object key = bind.key() >= 0 && bind.key() < arguments.size()
                            ? coerce(arguments.get(bind.key()), rawType(parameters.length > 0 ? parameters[0] : null),
                            this.sourceLoader)
                            : null;
                    if (key != null) {
                        entries.put(key, coerced);
                    }
                    continue;
                }

                out.add(coerced);
                if (!list) {
                    return coerced;
                }
            }

            if (map) {
                return Collections.unmodifiableMap(entries);
            }
            return list ? List.copyOf(out) : null;
        }

        private static java.lang.reflect.Type[] typeArguments(java.lang.reflect.Type type) {
            return type instanceof ParameterizedType parameterized
                    ? parameterized.getActualTypeArguments()
                    : new java.lang.reflect.Type[0];
        }

        private static Class<?> rawType(java.lang.reflect.Type type) {
            if (type instanceof Class<?> cls) {
                return cls;
            }
            if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
                return raw;
            }

            return Object.class;
        }

        private static boolean producedBy(Value value, String producer) {
            if (value instanceof Value.StaticCall call) {
                return call.methodName().equals(producer);
            }
            if (value instanceof Value.InstanceCall call) {
                return call.methodName().equals(producer);
            }
            return value instanceof Value.Constructed && "<init>".equals(producer);
        }

        private static Value select(List<Value> arguments, Conduit.Bind bind, Class<?> target) {
            int from = bind.arg();
            if (from < 0 || from >= arguments.size()) {
                return null;
            }
            if (bind.span() <= 1) {
                return arguments.get(from);
            }

            int to = Math.min(from + bind.span(), arguments.size());
            Conduit conduit = target.getAnnotation(Conduit.class);
            if (conduit == null || to - from < bind.span()) {
                return null;
            }
            return new Value.Constructed(conduit.value().replace('.', '/'), List.copyOf(arguments.subList(from, to)));
        }

        private static String implementationOf(Value value) {
            String internal = switch (value) {
                case Value.Lambda lambda -> lambda.implOwner();
                case Value.Constructed constructed -> constructed.className();
                default -> null;
            };

            return internal == null ? null : internal.replace('/', '.');
        }

        private Object view(Value resolved, Method method) {
            return coerce(resolved, method.getReturnType(), method.getGenericReturnType(),
                    this.sourceLoader, this.calls);
        }

        private List<Value.InstanceCall> callsOn(String name) {
            return callsOn(this.calls, this.value, name);
        }

        private static List<Value.InstanceCall> callsOn(List<Value.InstanceCall> recorded, Value base, String name) {
            List<Value.InstanceCall> matches = new ArrayList<>();
            for (Value.InstanceCall call : recorded) {
                if (call.methodName().equals(name) && (call.receiver().equals(base) || refetches(call.receiver(), base))) {
                    matches.add(call);
                }
            }

            return matches;
        }

        private static boolean refetches(Value lookup, Value base) {
            return lookup instanceof Value.InstanceCall(String _, String getter, Value parent, List<Value> keys)
                    && keys.size() == 1 && keys.getFirst() instanceof Value.Literal
                    && base instanceof Value.InstanceCall(String _, String putter, Value owner, List<Value> args)
                    && !getter.equals(putter) && !args.isEmpty()
                    && keys.getFirst().equals(args.getFirst()) && parent.equals(owner);
        }

        private List<Value.InstanceCall> chainedCalls(String name) {
            return chainedCalls(this.value, name);
        }

        private static List<Value.InstanceCall> chainedCalls(Value base, String name) {
            List<Value.InstanceCall> matches = new ArrayList<>();

            Value current = base;
            for (; current instanceof Value.InstanceCall call; current = call.receiver()) {
                if (call.methodName().equals(name)) {
                    matches.add(0, call);
                }
            }

            // A chain starts at a static factory or a constructor. Treat either as a call so it can be matched too.
            if (current instanceof Value.StaticCall root && root.methodName().equals(name)) {
                matches.add(0, new Value.InstanceCall(root.className(), root.methodName(), current, root.args()));
            } else if (current instanceof Value.Constructed root && "<init>".equals(name)) {
                matches.add(0, new Value.InstanceCall(root.className(), name, current, root.args()));
            }

            return matches;
        }

        private Object resolveCalls(Method method, List<Value.InstanceCall> matches, Argument.Index index) {
            Class<?> returnType = method.getReturnType();
            if (index == null && (returnType == boolean.class || returnType == Boolean.class)) {
                return !matches.isEmpty();
            }

            if (List.class.isAssignableFrom(returnType)) {
                Class<?> element = inferSupplierType(method.getGenericReturnType());
                List<Object> out = new ArrayList<>(matches.size());
                for (Value.InstanceCall call : matches) {
                    Object coerced = coerce(select(call, index), element, element, this.sourceLoader, this.calls);
                    if (coerced != null) {
                        out.add(coerced);
                    }
                }

                return List.copyOf(out);
            }

            if (matches.isEmpty()) {
                return null;
            }
            Value nearest = select(matches.get(matches.size() - 1), index);
            return coerce(nearest, returnType, method.getGenericReturnType(), this.sourceLoader, this.calls);
        }

        private static Value select(Value.InstanceCall call, Argument.Index index) {
            if (index == null) {
                return call;
            }
            if (index.value() < 0 || index.value() >= call.args().size()) {
                return null;
            }
            return call.args().get(index.value());
        }

        private static List<Value> argsOf(Value v) {
            if (v instanceof Value.Constructed c) {
                return c.args();
            }
            if (v instanceof Value.StaticCall s) {
                return s.args();
            }
            if (v instanceof Value.InstanceCall i) {
                return i.args();
            }
            return null;
        }

        private static Value resolveConduitMember(Value value, Class<?> conduitType, String member, ClassLoader loader,
                                                  Set<String> seen, String typeArgument) {
            Conduit conduit = conduitType.getAnnotation(Conduit.class);
            String internal = conduit == null ? null : conduit.value().replace('.', '/');
            List<String> members = memberCandidates(member);
            return resolveMemberValue(value, internal, members, loader, seen, typeArgument);
        }

        private static Value resolveConduitMethod(Value value, Class<?> conduitType, String member, ClassLoader loader, Set<String> seen) {
            Conduit conduit = conduitType.getAnnotation(Conduit.class);
            String internal = conduit == null ? null : conduit.value().replace('.', '/');
            List<String> members = memberCandidates(member);
            return resolveMethodValue(value, internal, members, loader, seen);
        }

        private static Value resolveConduitMethodByIndex(Value value, Class<?> conduitType, int index, ClassLoader loader, Set<String> seen) {
            if (index < 0 || !(value instanceof Value.Constructed constructed) || loader == null) {
                return null;
            }

            Conduit conduit = conduitType.getAnnotation(Conduit.class);
            String ownerInternal = conduit == null ? null : conduit.value().replace('.', '/');
            List<String> classCandidates = new ArrayList<>(2);
            if (ownerInternal != null) {
                classCandidates.add(ownerInternal);
            }
            if (classCandidates.stream().noneMatch(c -> c.equals(constructed.className()))) {
                classCandidates.add(constructed.className());
            }

            for (String className : classCandidates) {
                ClassNode node = readClass(loader, className);
                if (node == null) {
                    continue;
                }

                List<MethodNode> methods = noArgInstanceMethods(node);
                if (index >= methods.size()) {
                    continue;
                }

                Map<String, Value> fields = resolveConstructedFields(node, constructed.args());
                Value resolved = evaluateMethodReturn(node, methods.get(index), fields, loader, seen);
                if (resolved != null) {
                    return resolved;
                }
            }

            return null;
        }

        private static Value resolveMethodValue(Value value, String ownerInternal, List<String> members, ClassLoader loader, Set<String> seen) {
            if (!(value instanceof Value.Constructed constructed) || loader == null) {
                return null;
            }

            List<String> classCandidates = new ArrayList<>(hierarchy(loader, constructed.className()));
            if (ownerInternal != null && !classCandidates.contains(ownerInternal)) {
                classCandidates.add(ownerInternal);
            }

            for (String className : classCandidates) {
                ClassNode node = readClass(loader, className);
                if (node == null) {
                    continue;
                }

                Map<String, Value> fields = resolveConstructedFields(node, constructed.args());
                for (String member : members) {
                    MethodNode method = findMethod(node, member, 0);
                    if (method == null) {
                        method = findMethodAnyArity(node, member);
                    }
                    if (method == null) {
                        continue;
                    }

                    Value resolved = evaluateMethodReturn(node, method, fields, loader, seen);
                    if (resolved != null) {
                        return resolved;
                    }
                }
            }

            return null;
        }

        private static Value resolveMemberValue(Value value, String ownerInternal, List<String> members, ClassLoader loader,
                                                Set<String> seen, String typeArgument) {
            if (loader == null) {
                return null;
            }

            Value.Constructed constructed;

            if (value instanceof Value.Constructed c) {
                constructed = c;
            } else if (value instanceof Value.Field field) {
                return value;
            } else if (value instanceof Value.InstanceCall instanceCall) {
                Value resolved = evaluateInstanceMethodForMemberResolution(instanceCall, loader, seen);
                if (resolved == null) {
                    return null;
                }
                if (resolved instanceof Value.Constructed c) {
                    constructed = c;
                } else if (resolved instanceof Value.InstanceCall || resolved instanceof Value.StaticCall) {
                    return resolveMemberValue(resolved, ownerInternal, members, loader, seen, typeArgument);
                } else if (resolved instanceof Value.Field) {
                    return resolved;
                } else {
                    return null;
                }
            } else if (value instanceof Value.StaticCall staticCall) {
                Value resolved = evaluateStaticMethodForMemberResolution(staticCall, loader, seen);
                if (resolved == null) {
                    return null;
                }
                if (resolved instanceof Value.Constructed c) {
                    constructed = c;
                } else if (resolved instanceof Value.InstanceCall || resolved instanceof Value.StaticCall) {
                    return resolveMemberValue(resolved, ownerInternal, members, loader, seen, typeArgument);
                } else if (resolved instanceof Value.Field) {
                    return resolved;
                } else {
                    return null;
                }
            } else {
                return null;
            }

            List<String> classCandidates = new ArrayList<>(hierarchy(loader, constructed.className()));
            if (ownerInternal != null && !classCandidates.contains(ownerInternal)) {
                classCandidates.add(ownerInternal);
            }

            for (String className : classCandidates) {
                ClassNode node = readClass(loader, className);
                if (node == null) {
                    continue;
                }

                Map<String, Value> fields = resolveConstructedFields(node, constructed.args());
                for (String member : members) {
                    Value directField = fields.get(member);
                    if (directField != null) {
                        return directField;
                    }

                    MethodNode method = findMethod(node, member, 0);
                    if (method == null) {
                        method = findMethodAnyArity(node, member);
                    }
                    if (method == null) {
                        continue;
                    }

                    Value resolved = evaluateMethodReturn(node, method, fields, loader, seen);
                    if (resolved != null) {
                        return dereference(loader, classCandidates, constructed.className(), resolved, seen, 0, typeArgument);
                    }

                    return new Value.InstanceCall(className, member, constructed, new ArrayList<>());
                }
            }

            return null;
        }

        private static Value evaluateInstanceMethodForMemberResolution(Value.InstanceCall call, ClassLoader loader, Set<String> seen) {
            if (!(call.receiver() instanceof Value.Constructed receiver)) {
                return null;
            }

            ClassNode ownerNode = readClass(loader, call.className());
            if (ownerNode == null) {
                return null;
            }

            MethodNode method = findMethod(ownerNode, call.methodName(), 0);
            // A lambda's implementation also takes the functional interface's arguments.
            if (method == null) {
                method = findConcreteByName(ownerNode, call.methodName());
            }
            if (method == null) {
                return null;
            }

            Map<String, Value> fields = resolveConstructedFields(ownerNode, receiver.args());
            return evaluateMethodReturn(ownerNode, method, fields, loader, seen);
        }

        private static Value evaluateStaticMethodForMemberResolution(Value.StaticCall call, ClassLoader loader, Set<String> seen) {
            ClassNode ownerNode = readClass(loader, call.className());
            if (ownerNode == null) {
                return null;
            }

            MethodNode method = findMethod(ownerNode, call.methodName(), call.args().size());
            if (method == null) {
                return null;
            }

            Deque<Value> stack = new ArrayDeque<>();
            Value[] locals = new Value[Math.max(method.maxLocals, call.args().size() + 4)];
            for (int i = 0; i < call.args().size(); i++) {
                if (i < locals.length) {
                    locals[i] = call.args().get(i);
                }
            }

            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                int op = insn.getOpcode();
                if (op < 0) {
                    continue;
                }

                Value constant = CallScanner.constant(insn);

                if (constant != null) {

                    stack.push(constant);

                    continue;

                }


                switch (op) {
                    case Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD ->
                            stack.push(CallScanner.safeLocal(locals, ((VarInsnNode) insn).var));

                    case Opcodes.GETFIELD, Opcodes.CHECKCAST, Opcodes.DUP, Opcodes.POP, Opcodes.INVOKEVIRTUAL,
                            Opcodes.INVOKEINTERFACE, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC -> {
                        if (op == Opcodes.GETFIELD) {
                            CallScanner.safePop(stack);
                            stack.push(new Value.Unknown("field"));
                        } else if (op == Opcodes.DUP && !stack.isEmpty()) {
                            stack.push(stack.peek());
                        } else if (op == Opcodes.POP) {
                            CallScanner.safePop(stack);
                        } else if (op == Opcodes.CHECKCAST) {
                        } else {
                            MethodInsnNode callNode = (MethodInsnNode) insn;
                            Type[] callArgs = Type.getArgumentTypes(callNode.desc);
                            CallScanner.popN(stack, callArgs.length);
                            if (op != Opcodes.INVOKESTATIC) {
                                CallScanner.safePop(stack);
                            }
                            if (Type.getReturnType(callNode.desc).getSort() != Type.VOID) {
                                stack.push(new Value.Unknown("call"));
                            }
                        }
                    }

                    case Opcodes.ARETURN, Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN -> {
                        return stack.isEmpty() ? null : stack.pop();
                    }

                    default -> {
                    }
                }
            }

            return null;
        }

        private static List<String> memberCandidates(String member) {
            List<String> names = new ArrayList<>(2);
            names.add(member);

            if (member.startsWith("get") && member.length() > 3) {
                names.add(decapitalize(member.substring(3)));
            } else if (member.startsWith("is") && member.length() > 2) {
                names.add(decapitalize(member.substring(2)));
            }

            return names;
        }

        private static String decapitalize(String value) {
            if (value.isEmpty()) {
                return value;
            }
            if (value.length() == 1) {
                return value.toLowerCase();
            }
            return Character.toLowerCase(value.charAt(0)) + value.substring(1);
        }

        private static ClassNode readClass(ClassLoader loader, String internalName) {
            try (InputStream in = loader.getResourceAsStream(internalName + ".class")) {
                if (in == null) {
                    return null;
                }
                ClassReader reader = new ClassReader(in);
                ClassNode node = new ClassNode();
                reader.accept(node, ClassReader.SKIP_FRAMES);
                return node;
            } catch (Throwable t) {
                return null;
            }
        }

        private static Map<String, Value> resolveConstructedFields(ClassNode node, List<Value> ctorArgs) {
            Map<String, Value> best = new HashMap<>();
            for (MethodNode method : node.methods) {
                if (!"<init>".equals(method.name)) {
                    continue;
                }
                if (Type.getArgumentTypes(method.desc).length != ctorArgs.size()) {
                    continue;
                }

                Map<String, Value> mapped = mapCtorFields(node.name, method, ctorArgs);
                if (mapped.size() > best.size()) {
                    best = mapped;
                }
            }
            return best;
        }

        private static Map<String, Value> mapCtorFields(String ownerInternal, MethodNode ctor, List<Value> args) {
            Map<String, Value> fields = new HashMap<>();
            Value[] locals = new Value[Math.max(ctor.maxLocals, args.size() + 4)];
            locals[0] = new Value.Unknown(THIS_MARKER);

            Type[] argTypes = Type.getArgumentTypes(ctor.desc);
            int slot = 1;
            for (int i = 0; i < argTypes.length && i < args.size(); i++) {
                if (slot < locals.length) {
                    locals[slot] = args.get(i);
                }
                slot += argTypes[i].getSize();
            }

            Deque<Value> stack = new ArrayDeque<>();
            for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                int op = insn.getOpcode();
                if (op < 0) {
                    continue;
                }

                Value constant = CallScanner.constant(insn);

                if (constant != null) {

                    stack.push(constant);

                    continue;

                }


                switch (op) {
                    case Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD ->
                            stack.push(CallScanner.safeLocal(locals, ((VarInsnNode) insn).var));

                    case Opcodes.DUP -> {
                        if (!stack.isEmpty()) {
                            stack.push(stack.peek());
                        }
                    }
                    case Opcodes.POP -> CallScanner.safePop(stack);

                    case Opcodes.PUTFIELD -> {
                        FieldInsnNode field = (FieldInsnNode) insn;
                        Value assigned = CallScanner.safePop(stack);
                        Value receiver = CallScanner.safePop(stack);
                        if (isThis(receiver) && ownerInternal.equals(field.owner)) {
                            fields.put(field.name, assigned);
                        }
                    }

                    case Opcodes.INVOKESPECIAL, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESTATIC -> {
                        MethodInsnNode call = (MethodInsnNode) insn;
                        Type[] callArgs = Type.getArgumentTypes(call.desc);
                        CallScanner.popN(stack, callArgs.length);
                        if (op != Opcodes.INVOKESTATIC) {
                            CallScanner.safePop(stack);
                        }
                        if (Type.getReturnType(call.desc).getSort() != Type.VOID) {
                            stack.push(new Value.Unknown("call"));
                        }
                    }

                    default -> {
                    }
                }
            }

            return fields;
        }

        private static Value staticField(ClassLoader loader, String owner, String name) {
            if (loader instanceof ConduitClassLoader conduitLoader) {
                return new CallScanner(conduitLoader, (o, n, d) -> false, match -> {}).staticField(owner, name);
            }

            return new Value.Field(owner, name);
        }

        private static boolean jumpsForward(MethodNode method, AbstractInsnNode insn) {
            if (!(insn instanceof org.objectweb.asm.tree.JumpInsnNode jump)) {
                return false;
            }
            return method.instructions.indexOf(jump.label) > method.instructions.indexOf(jump);
        }

        private static List<String> hierarchy(ClassLoader loader, String className) {
            List<String> chain = new ArrayList<>();
            for (String current = className; current != null && chain.size() < 16; ) {
                chain.add(current);

                ClassNode node = readClass(loader, current);
                if (node == null || node.superName == null || "java/lang/Object".equals(node.superName)) {
                    break;
                }
                current = node.superName;
            }

            return chain;
        }

        private static MethodNode findConcreteByName(ClassNode node, String name) {
            for (MethodNode method : node.methods) {
                if (method.name.equals(name) && (method.access & Opcodes.ACC_ABSTRACT) == 0) {
                    return method;
                }
            }
            return null;
        }

        // A conduit method takes no arguments, but the method backing it may. One that ignores its
        // arguments still resolves, since unset locals read as unknown.
        private static MethodNode findMethodAnyArity(ClassNode node, String name) {
            for (MethodNode method : node.methods) {
                if (!method.name.equals(name)) {
                    continue;
                }
                if ("<init>".equals(name) || "<clinit>".equals(name)) {
                    continue;
                }
                if ((method.access & Opcodes.ACC_STATIC) != 0) {
                    continue;
                }
                // A bridge only forwards to the real method, and evaluating it loses the value.
                if ((method.access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0) {
                    continue;
                }
                if ((method.access & Opcodes.ACC_ABSTRACT) != 0) {
                    continue;
                }
                return method;
            }

            return null;
        }

        private static MethodNode findMethod(ClassNode node, String name, int argCount) {
            for (MethodNode method : node.methods) {
                if (!method.name.equals(name)) {
                    continue;
                }
                if ("<init>".equals(name) || "<clinit>".equals(name)) {
                    continue;
                }
                if (Type.getArgumentTypes(method.desc).length != argCount) {
                    continue;
                }
                if ((method.access & Opcodes.ACC_STATIC) != 0) {
                    continue;
                }
                return method;
            }
            return null;
        }

        private static List<MethodNode> noArgInstanceMethods(ClassNode node) {
            List<MethodNode> methods = new ArrayList<>();
            for (MethodNode method : node.methods) {
                if ("<init>".equals(method.name) || "<clinit>".equals(method.name)) {
                    continue;
                }
                if ((method.access & Opcodes.ACC_STATIC) != 0) {
                    continue;
                }
                if (Type.getArgumentTypes(method.desc).length != 0) {
                    continue;
                }
                methods.add(method);
            }
            return methods;
        }

        /**
         * Resolves a value that only points at where the real one comes from. A field of another
         * class is followed to where the hierarchy assigns it, and a call on an unknown receiver
         * is evaluated on the class named by the superclass's first type argument, or on
         * {@code typeArgument} when that argument is only a type variable.
         */
        private static Value dereference(ClassLoader loader, List<String> candidates, String concrete,
                                         Value value, Set<String> seen, int depth, String typeArgument) {
            if (value == null || depth > 4) {
                return value;
            }

            if (value instanceof Value.Multiple multiple) {
                List<Value> mapped = new ArrayList<>();
                for (Value each : multiple.values()) {
                    Value one = dereference(loader, candidates, concrete, each, seen, depth + 1, typeArgument);
                    if (one != null && !mapped.contains(one)) {
                        mapped.add(one);
                    }
                }

                if (mapped.isEmpty()) {
                    return null;
                }
                return mapped.size() == 1 ? mapped.get(0) : new Value.Multiple(List.copyOf(mapped));
            }

            if (value instanceof Value.Field field && !candidates.contains(field.className())) {
                Value stored = fieldAssignment(loader, candidates, field, seen);
                if (stored != null && !stored.equals(value)) {
                    return dereference(loader, candidates, concrete, stored, seen, depth + 1, typeArgument);
                }
            }

            if (value instanceof Value.InstanceCall call && call.receiver() instanceof Value.Unknown) {
                String declared = typeArgument(loader, concrete);
                if (declared == null) {
                    declared = typeArgument;
                }

                if (declared != null) {
                    Value evaluated = evaluateOn(loader, declared, call.methodName(), seen);
                    if (evaluated != null) {
                        return dereference(loader, candidates, concrete, evaluated, seen, depth + 1, typeArgument);
                    }
                }
            }

            return value;
        }

        private static Value fieldAssignment(ClassLoader loader, List<String> candidates, Value.Field field, Set<String> seen) {
            for (String className : candidates) {
                ClassNode node = readClass(loader, className);
                if (node == null) {
                    continue;
                }

                for (MethodNode method : node.methods) {
                    for (AbstractInsnNode insn : method.instructions) {
                        if (insn.getOpcode() != Opcodes.PUTFIELD) {
                            continue;
                        }

                        FieldInsnNode put = (FieldInsnNode) insn;
                        if (!put.owner.equals(field.className()) || !put.name.equals(field.fieldName())) {
                            continue;
                        }

                        Value stored = evaluateMethodReturn(node, method, Map.of(), loader, seen, List.of(), insn);
                        if (stored != null) {
                            return stored;
                        }
                    }
                }
            }

            return null;
        }

        // For class Foo extends Base<Bar>, returns Bar.
        private static String typeArgument(ClassLoader loader, String className) {
            ClassNode node = readClass(loader, className);
            if (node == null || node.signature == null) {
                return null;
            }

            String signature = node.signature;

            // Skip the class's own type parameters to reach the superclass.
            int cursor = 0;
            if (signature.startsWith("<")) {
                int depth = 0;
                for (; cursor < signature.length(); cursor++) {
                    char c = signature.charAt(cursor);
                    if (c == '<') {
                        depth++;
                    } else if (c == '>' && --depth == 0) {
                        cursor++;
                        break;
                    }
                }
            }

            int open = signature.indexOf('<', cursor);
            if (open < 0) {
                return null;
            }

            if (open + 1 >= signature.length() || signature.charAt(open + 1) != 'L') {
                return null;
            }

            int end = open + 1;
            while (end < signature.length() && signature.charAt(end) != ';' && signature.charAt(end) != '<') {
                end++;
            }
            return end > open + 2 ? signature.substring(open + 2, end) : null;
        }

        private static Value evaluateOn(ClassLoader loader, String className, String methodName, Set<String> seen) {
            for (String candidate : hierarchy(loader, className)) {
                ClassNode node = readClass(loader, candidate);
                if (node == null) {
                    continue;
                }

                MethodNode method = findMethod(node, methodName, 0);
                if (method == null) {
                    method = findMethodAnyArity(node, methodName);
                }
                if (method == null) {
                    continue;
                }

                Value resolved = evaluateMethodReturn(node, method, Map.of(), loader, seen);
                if (resolved != null) {
                    return resolved;
                }
            }

            return null;
        }

        private static Value evaluateStaticCall(ClassLoader loader, MethodInsnNode call, List<Value> args, Set<String> seen) {
            for (Value arg : args) {
                if (!(arg instanceof Value.Literal)) {
                    return null;
                }
            }

            // The call's owner may inherit the method rather than declare it.
            for (String className : hierarchy(loader, call.owner)) {
                ClassNode owner = readClass(loader, className);
                if (owner == null) {
                    continue;
                }

                for (MethodNode candidate : owner.methods) {
                    if (!candidate.name.equals(call.name) || !candidate.desc.equals(call.desc)) {
                        continue;
                    }
                    if ((candidate.access & Opcodes.ACC_STATIC) == 0) {
                        continue;
                    }

                    return evaluateMethodReturn(owner, candidate, Map.of(), loader, seen, args);
                }
            }

            return null;
        }

        private static Value evaluateMethodReturn(ClassNode owner, MethodNode method, Map<String, Value> fields,
                                                  ClassLoader loader, Set<String> seen) {
            return evaluateMethodReturn(owner, method, fields, loader, seen, List.of());
        }

        private static Value evaluateMethodReturn(ClassNode owner, MethodNode method, Map<String, Value> fields,
                                                  ClassLoader loader, Set<String> seen, List<Value> arguments) {
            return evaluateMethodReturn(owner, method, fields, loader, seen, arguments, null);
        }

        private static Value evaluateMethodReturn(ClassNode owner, MethodNode method, Map<String, Value> fields,
                                                  ClassLoader loader, Set<String> seen, List<Value> arguments,
                                                  AbstractInsnNode stopAt) {
            String key = owner.name + "." + method.name + method.desc;
            if (!seen.add(key)) {
                return new Value.Unknown("recursive_method");
            }
            try {
                Value[] locals = new Value[Math.max(method.maxLocals, arguments.size() + 1)];
                int slot = 0;
                if ((method.access & Opcodes.ACC_STATIC) == 0) {
                    locals[slot++] = new Value.Unknown(THIS_MARKER);
                }
                for (Value argument : arguments) {
                    if (slot < locals.length) {
                        locals[slot++] = argument;
                    }
                }
                Deque<Value> stack = new ArrayDeque<>();
                List<Value> returns = new ArrayList<>();

                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn == stopAt) {
                        return stack.isEmpty() ? null : stack.peek();
                    }

                    int op = insn.getOpcode();
                    if (op < 0) {
                        continue;
                    }

                    Value constant = CallScanner.constant(insn);

                    if (constant != null) {

                        stack.push(constant);

                        continue;

                    }


                    switch (op) {
                        case Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD ->
                                stack.push(CallScanner.safeLocal(locals, ((VarInsnNode) insn).var));

                        case Opcodes.NEW -> {
                            TypeInsnNode typeInsn = (TypeInsnNode) insn;
                            stack.push(new Value.Uninitialized(typeInsn.desc));
                        }

                        case Opcodes.GETFIELD -> {
                            FieldInsnNode field = (FieldInsnNode) insn;
                            Value receiver = CallScanner.safePop(stack);
                            if (isThis(receiver) && owner.name.equals(field.owner)) {
                                stack.push(fields.getOrDefault(field.name, new Value.Field(field.owner, field.name)));
                            } else {
                                stack.push(new Value.Field(field.owner, field.name));
                            }
                        }

                        case Opcodes.GETSTATIC -> {
                            FieldInsnNode field = (FieldInsnNode) insn;
                            stack.push(staticField(loader, field.owner, field.name));
                        }

                        case Opcodes.CHECKCAST -> {
                        }

                        case Opcodes.DUP -> {
                            if (!stack.isEmpty()) {
                                stack.push(stack.peek());
                            }
                        }
                        case Opcodes.POP -> CallScanner.safePop(stack);

                        case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESPECIAL -> {
                            MethodInsnNode call = (MethodInsnNode) insn;
                            Type[] callArgs = Type.getArgumentTypes(call.desc);
                            List<Value> args = CallScanner.popN(stack, callArgs.length);
                            Value receiver = CallScanner.safePop(stack);

                            if ("<init>".equals(call.name)) {
                                if (receiver instanceof Value.Uninitialized u && u.className().equals(call.owner)) {
                                    stack.push(new Value.Constructed(call.owner, args));
                                }
                            } else if (Type.getReturnType(call.desc).getSort() != Type.VOID) {
                                Value resolved = null;
                                if (args.isEmpty()) {
                                    resolved = resolveMethodCall(receiver, call.owner, call.name, loader, seen);
                                }
                                stack.push(resolved != null ? resolved : new Value.InstanceCall(call.owner, call.name, receiver, args));
                            }
                        }

                        case Opcodes.INVOKESTATIC -> {
                            MethodInsnNode call = (MethodInsnNode) insn;
                            Type[] callArgs = Type.getArgumentTypes(call.desc);
                            List<Value> args = CallScanner.popN(stack, callArgs.length);
                            if (Type.getReturnType(call.desc).getSort() != Type.VOID) {
                                Value resolved = evaluateStaticCall(loader, call, args, seen);
                                stack.push(resolved != null ? resolved : new Value.StaticCall(call.owner, call.name, args));
                            }
                        }

                        case Opcodes.INVOKEDYNAMIC -> {
                            InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                            Type[] indyArgs = Type.getArgumentTypes(indy.desc);
                            List<Value> args = CallScanner.popN(stack, indyArgs.length);
                            if ("java/lang/invoke/StringConcatFactory".equals(indy.bsm.getOwner())) {
                                stack.push(CallScanner.concat(indy, args));
                            } else if (Type.getReturnType(indy.desc).getSort() != Type.VOID) {
                                stack.push(new Value.Unknown("invokedynamic:" + indy.name));
                            }
                        }

                        case Opcodes.ARETURN, Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN -> {
                            // Keep walking, since a branching method returns from several places.
                            if (!stack.isEmpty()) {
                                Value returned = stack.pop();
                                if (returned != null && !returns.contains(returned)) {
                                    returns.add(returned);
                                }
                            }
                        }

                        case Opcodes.GOTO -> {
                            // A switch or ternary jumps forward into a single return, so take each branch's value here.
                            if (!stack.isEmpty() && jumpsForward(method, insn)) {
                                Value branch = stack.peek();
                                if (branch != null && !returns.contains(branch)) {
                                    returns.add(branch);
                                }
                            }
                        }

                        default -> {
                        }
                    }
                }

                if (returns.isEmpty()) {
                    return null;
                }
                return returns.size() == 1 ? returns.get(0) : new Value.Multiple(List.copyOf(returns));
            } finally {
                seen.remove(key);
            }
        }

        private static Value resolveMethodCall(Value receiver, String ownerInternal, String methodName,
                                               ClassLoader loader, Set<String> seen) {
            if (!(receiver instanceof Value.Constructed c)) {
                return null;
            }

            List<String> classCandidates = new ArrayList<>(2);
            classCandidates.add(c.className());
            if (!c.className().equals(ownerInternal)) {
                classCandidates.add(ownerInternal);
            }

            for (String className : classCandidates) {
                ClassNode owner = readClass(loader, className);
                if (owner == null) {
                    continue;
                }

                MethodNode method = findMethod(owner, methodName, 0);
                if (method == null) {
                    continue;
                }

                Map<String, Value> fields = resolveConstructedFields(owner, c.args());
                Value resolved = evaluateMethodReturn(owner, method, fields, loader, seen);
                if (resolved != null) {
                    return resolved;
                }
            }

            return null;
        }

        private static boolean isThis(Value value) {
            return value instanceof Value.Unknown u && THIS_MARKER.equals(u.reason());
        }

    }
}
