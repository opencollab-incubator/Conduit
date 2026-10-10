package org.geysermc.conduit.generate;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class Bindings {
    record Bind(String member, int arg, int span, int from, int key, String calls, String chain,
                String descriptor, String producer) {
        Bind descriptor(String value) {
            return new Bind(this.member, this.arg, this.span, this.from, this.key, this.calls, this.chain,
                    value, this.producer);
        }

        Bind producer(String value) {
            return new Bind(this.member, this.arg, this.span, this.from, this.key, this.calls, this.chain,
                    this.descriptor, value);
        }

        static Bind of(String member) {
            return new Bind(member, -1, 1, -1, -1, "", "", "", "");
        }

        Bind arg(int value) {
            return new Bind(this.member, value, this.span, this.from, this.key, this.calls, this.chain, this.descriptor, this.producer);
        }

        Bind span(int value) {
            return new Bind(this.member, this.arg, value, this.from, this.key, this.calls, this.chain, this.descriptor, this.producer);
        }

        Bind from(int value) {
            return new Bind(this.member, this.arg, this.span, value, this.key, this.calls, this.chain, this.descriptor, this.producer);
        }

        Bind key(int value) {
            return new Bind(this.member, this.arg, this.span, this.from, value, this.calls, this.chain, this.descriptor, this.producer);
        }

        Bind calls(String value) {
            return new Bind(this.member, this.arg, this.span, this.from, this.key, value, this.chain, this.descriptor, this.producer);
        }

        Bind chain(String value) {
            return new Bind(this.member, this.arg, this.span, this.from, this.key, this.calls, value, this.descriptor, this.producer);
        }

        @Override
        public String toString() {
            List<String> parts = new ArrayList<>();
            if (this.arg >= 0) {
                parts.add("arg = " + this.arg);
            }
            if (this.span > 1) {
                parts.add("span = " + this.span);
            }
            if (this.from >= 0) {
                parts.add("from = " + this.from);
            }
            if (this.key >= 0) {
                parts.add("key = " + this.key);
            }
            if (!this.calls.isEmpty()) {
                parts.add("calls = \"" + this.calls + '"');
            }
            if (!this.chain.isEmpty()) {
                parts.add("chain = \"" + this.chain + '"');
            }
            if (!this.descriptor.isEmpty()) {
                parts.add("descriptor = \"" + this.descriptor + '"');
            }
            if (!this.producer.isEmpty()) {
                parts.add("producer = \"" + this.producer + '"');
            }
            return parts.isEmpty() ? "@Conduit.Bind" : "@Conduit.Bind(" + String.join(", ", parts) + ")";
        }
    }

    private Bindings() {
    }

    static List<Bind> of(ClassNode owner, Map<String, ClassNode> world) {
        List<Bind> binds = new ArrayList<>();
        for (FieldNode field : owner.fields) {
            if ((field.access & Opcodes.ACC_STATIC) != 0) {
                continue;
            }
            for (Bind bind : forField(owner, field, world)) {
                if (!binds.contains(bind)) {
                    binds.add(bind);
                }
            }
        }

        return binds;
    }

    private static List<Bind> forField(ClassNode owner, FieldNode field, Map<String, ClassNode> world) {
        List<Bind> binds = new ArrayList<>();

        for (MethodNode constructor : owner.methods) {
            if (!"<init>".equals(constructor.name)) {
                continue;
            }

            Effects.Expr value = Effects.constructorFields(owner, constructor).get(field.name);
            if (value == null) {
                value = throughChainedConstructor(owner, constructor, field.name);
                if (value == null) {
                    continue;
                }
            }

            String descriptor = constructor.desc;
            span(value).ifPresent(span -> {
                Bind bind = Bind.of(field.name).arg(span.from()).span(span.width())
                        .descriptor(descriptor).producer("<init>");
                if (!binds.contains(bind)) {
                    binds.add(bind);
                }
            });
        }

        MethodNode constructor = widestConstructor(owner);
        Effects.Expr assigned = constructor == null
                ? null
                : Effects.constructorFields(owner, constructor).get(field.name);

        if (assigned != null) {
            binds.addAll(throughProducers(owner, field.name, assigned, world));
        }

        for (Bind bind : throughStaticFactories(owner, Effects.of(owner), field.name, field.name, 1)) {
            if (!binds.contains(bind)) {
                binds.add(bind);
            }
        }

        binds.addAll(throughCalls(owner, field, world));
        return binds;
    }

    private static Effects.Expr throughChainedConstructor(ClassNode owner, MethodNode constructor, String field) {
        Effects.Construction chained = Effects.selfDelegate(owner, constructor);
        if (chained == null) {
            return null;
        }

        for (MethodNode target : owner.methods) {
            if (!"<init>".equals(target.name) || target == constructor) {
                continue;
            }
            if (Type.getArgumentTypes(target.desc).length != chained.arguments().size()) {
                continue;
            }

            Effects.Expr value = Effects.constructorFields(owner, target).get(field);
            if (value == null || value.kind() != Effects.Expr.Kind.PARAMETER) {
                continue;
            }

            Effects.Expr passed = chained.arguments().get(value.parameter());
            if (passed.kind() == Effects.Expr.Kind.PARAMETER) {
                return passed;
            }
        }

        return null;
    }

    private static MethodNode widestConstructor(ClassNode owner) {
        MethodNode best = null;
        int explained = -1;
        for (MethodNode method : owner.methods) {
            if (!"<init>".equals(method.name)) {
                continue;
            }

            int fields = Effects.constructorFields(owner, method).size();
            int parameters = Type.getArgumentTypes(method.desc).length;
            if (fields > explained
                    || fields == explained && best != null
                    && parameters > Type.getArgumentTypes(best.desc).length) {
                explained = fields;
                best = method;
            }
        }

        return best;
    }

    private static Optional<Span> span(Effects.Expr value) {
        if (value.kind() == Effects.Expr.Kind.PARAMETER) {
            return Optional.of(new Span(value.parameter(), 1));
        }

        if (value.kind() != Effects.Expr.Kind.CONSTRUCTED || value.arguments().isEmpty()) {
            return Optional.empty();
        }

        int first = -1;
        for (int i = 0; i < value.arguments().size(); i++) {
            Effects.Expr argument = value.arguments().get(i);
            if (argument.kind() != Effects.Expr.Kind.PARAMETER) {
                return Optional.empty();
            }
            if (i == 0) {
                first = argument.parameter();
            } else if (argument.parameter() != first + i) {
                return Optional.empty();
            }
        }

        return Optional.of(new Span(first, value.arguments().size()));
    }

    private record Span(int from, int width) {
    }

    private static List<Bind> throughProducers(ClassNode owner, String member, Effects.Expr assigned,
                                               Map<String, ClassNode> world) {
        List<Bind> binds = new ArrayList<>();
        Span span = span(assigned).orElse(null);
        if (span == null) {
            return binds;
        }

        for (ClassNode candidate : world.values()) {
            for (Effects.Summary summary : Effects.of(candidate)) {
                for (Effects.Construction construction : summary.constructions()) {
                    if (!construction.type().equals(owner.name)) {
                        continue;
                    }
                    if (span.from() + span.width() > construction.arguments().size()) {
                        continue;
                    }

                    Effects.Expr source = construction.arguments().get(span.from());
                    if (source.kind() == Effects.Expr.Kind.PARAMETER) {
                        Span run = run(construction.arguments(), span);
                        if (run != null) {
                            binds.add(Bind.of(member).arg(run.from()).span(run.width())
                                    .descriptor(summary.descriptor()).producer(summary.name()));
                        }
                    } else if (source.kind() == Effects.Expr.Kind.FIELD) {
                        binds.addAll(stateBinds(candidate, member, source.field(), span.width()));
                    } else if (source.kind() == Effects.Expr.Kind.CONSTRUCTED) {
                        span(source).ifPresent(inner -> binds.add(Bind.of(member).arg(inner.from())
                                .span(inner.width()).descriptor(summary.descriptor()).producer(summary.name())));
                    } else if (source.kind() == Effects.Expr.Kind.CALL
                            && source.receiver().kind() == Effects.Expr.Kind.PARAMETER) {
                        throughArgument(member, source, world).ifPresent(binds::add);
                    }
                }
            }
        }

        for (ClassNode candidate : world.values()) {
            for (Effects.Summary summary : Effects.of(candidate)) {
                if (summary.forwards() == null) {
                    continue;
                }

                for (Bind bind : List.copyOf(binds)) {
                    if (!bind.producer().equals(summary.forwards().type())) {
                        continue;
                    }
                    if (bind.arg() < 0 || bind.arg() + bind.span() > summary.forwards().arguments().size()) {
                        continue;
                    }

                    Span through = run(summary.forwards().arguments(), new Span(bind.arg(), bind.span()));
                    if (through == null) {
                        continue;
                    }

                    Bind forwarded = Bind.of(member).arg(through.from()).span(through.width())
                            .descriptor(summary.descriptor()).producer(summary.name());
                    if (!binds.contains(forwarded)) {
                        binds.add(forwarded);
                    }
                }
            }
        }

        return binds;
    }

    private static Span run(List<Effects.Expr> arguments, Span span) {
        int first = -1;
        for (int i = 0; i < span.width(); i++) {
            Effects.Expr argument = arguments.get(span.from() + i);
            if (argument.kind() != Effects.Expr.Kind.PARAMETER) {
                return null;
            }
            if (i == 0) {
                first = argument.parameter();
            } else if (argument.parameter() != first + i) {
                return null;
            }
        }

        return new Span(first, span.width());
    }

    private static List<Bind> stateBinds(ClassNode producer, String member, String field, int width) {
        List<Bind> binds = new ArrayList<>();
        List<Effects.Summary> summaries = Effects.of(producer);

        for (Effects.Summary summary : summaries) {
            if (Type.getArgumentTypes(summary.descriptor()).length != 0) {
                continue;
            }

            for (Effects.Construction delegate : summary.delegates()) {
                boolean assigns = summaries.stream().anyMatch(other ->
                        other.name().equals(delegate.type()) && other.assigns().containsKey(field));
                if (!assigns) {
                    continue;
                }

                Bind bind = Bind.of(member).descriptor(summary.descriptor());
                binds.add(summary.returnsThis() ? bind.chain(summary.name()) : bind.calls(summary.name()));
            }
        }

        for (Effects.Summary summary : summaries) {
            Integer parameter = summary.assigns().get(field);
            boolean flag = summary.flags().contains(field);
            if (parameter == null && !flag) {
                continue;
            }

            Bind bind = flag ? Bind.of(member) : Bind.of(member).arg(parameter).span(width);
            bind = bind.descriptor(summary.descriptor());
            binds.add(summary.returnsThis() ? bind.chain(summary.name()) : bind.calls(summary.name()));
        }

        binds.addAll(throughStaticFactories(producer, summaries, member, field, width));
        return binds;
    }

    private static boolean setter(Effects.Summary summary) {
        return summary.returnsThis() && !summary.isStatic()
                && (!summary.assigns().isEmpty() || !summary.flags().isEmpty());
    }

    private static List<Bind> throughStaticFactories(ClassNode producer, List<Effects.Summary> summaries,
                                                     String member, String field, int width) {
        List<Bind> binds = new ArrayList<>();
        if (summaries.stream().noneMatch(Bindings::setter)) {
            return binds;
        }

        for (MethodNode constructor : producer.methods) {
            if (!"<init>".equals(constructor.name)) {
                continue;
            }

            Effects.Expr assigned = Effects.constructorFields(producer, constructor).get(field);
            if (assigned == null || assigned.kind() != Effects.Expr.Kind.PARAMETER) {
                continue;
            }

            Bind constructed = Bind.of(member).arg(assigned.parameter()).span(width)
                    .descriptor(constructor.desc).chain("<init>");
            if (!binds.contains(constructed)) {
                binds.add(constructed);
            }

            int arity = Type.getArgumentTypes(constructor.desc).length;
            for (Effects.Summary summary : summaries) {
                if (!summary.isStatic()) {
                    continue;
                }

                for (Effects.Construction construction : summary.constructions()) {
                    if (!construction.type().equals(producer.name) || construction.arguments().size() != arity) {
                        continue;
                    }

                    Span run = run(construction.arguments(), new Span(assigned.parameter(), width));
                    if (run == null) {
                        continue;
                    }

                    Bind bind = Bind.of(member).arg(run.from()).span(run.width())
                            .descriptor(summary.descriptor()).chain(summary.name());
                    if (!binds.contains(bind)) {
                        binds.add(bind);
                    }
                }
            }
        }

        return binds;
    }

    private static Optional<Bind> throughArgument(String member, Effects.Expr source,
                                                            Map<String, ClassNode> world) {
        ClassNode holder = world.get(source.type());
        if (holder == null) {
            return Optional.empty();
        }

        String read = null;
        for (Effects.Summary summary : Effects.of(holder)) {
            if (summary.name().equals(source.field())) {
                read = summary.returnsField();
            }
        }

        if (read == null) {
            return Optional.empty();
        }
        for (Effects.Summary summary : Effects.of(holder)) {
            if (!summary.appends().containsKey(read)) {
                continue;
            }

            Bind bind = Bind.of(member).from(source.receiver().parameter());
            return Optional.of(summary.returnsThis()
                    ? bind.chain(summary.name())
                    : bind.calls(summary.name()));
        }

        return Optional.empty();
    }

    private static List<Bind> throughCalls(ClassNode owner, FieldNode field, Map<String, ClassNode> world) {
        List<Bind> binds = new ArrayList<>();
        List<Effects.Summary> summaries = Effects.of(owner);

        for (Effects.Summary summary : summaries) {
            if (field.name.equals(summary.returnsField())
                    && Type.getArgumentTypes(summary.descriptor()).length == 0) {
                binds.add(Bind.of(field.name).calls(summary.name()));
            }
        }

        for (Effects.Summary summary : summaries) {
            List<Effects.Expr> appended = summary.appends().get(field.name);
            if (appended == null) {
                continue;
            }

            Bind bind = Bind.of(field.name);
            bind = summary.returnsThis() ? bind.chain(summary.name()) : bind.calls(summary.name());
            if (appended.size() == 2) {
                Effects.Expr key = appended.get(0);
                if (key.kind() == Effects.Expr.Kind.PARAMETER) {
                    bind = bind.key(key.parameter());
                }
            }

            binds.add(bind);
        }

        for (Effects.Summary summary : summaries) {
            if (summary.appends().containsKey(field.name)) {
                continue;
            }
            for (Effects.Construction delegate : summary.delegates()) {
                boolean fills = summaries.stream().anyMatch(other ->
                        other.name().equals(delegate.type()) && other.appends().containsKey(field.name));
                if (!fills) {
                    continue;
                }

                Bind bind = Bind.of(field.name);
                binds.add(summary.returnsThis() ? bind.chain(summary.name()) : bind.calls(summary.name()));
            }
        }

        return binds;
    }
}
