package org.geysermc.conduit.generate;

import org.geysermc.conduit.ConduitContext;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Generates {@link org.geysermc.conduit.Conduit} interfaces from real classes.
 *
 * <p>The generator reads the classes a conduit mirrors, and works out from their constructors,
 * factories and methods where each field's value comes from at a call site. That becomes the
 * {@link org.geysermc.conduit.Conduit.Bind} entries on each method. Only the generator needs the
 * real classes. Commit its output, and scanning afterwards needs nothing but the jar being scanned.
 *
 * <pre>{@code
 * ConduitContext context = ConduitContext.load(Path.of("library.jar"));
 * new ConduitGenerator(context).generateAll(
 *         Path.of("src/main/java/com/example/conduit"), "com.example.conduit",
 *         List.of("com.example.Color", "com.example.Palette"));
 * }</pre>
 *
 * <p>Each file is stamped with a checksum. Regenerating replaces files that are unchanged since
 * they were generated, and leaves edited ones alone, so a binding the analysis can't derive can be
 * added by hand and survives the next run.
 *
 * <p>Also runnable from the command line, or through the {@code dev.opencollab.conduit} Gradle plugin.
 */
public final class ConduitGenerator {
    private static final String STAMP = "// conduit:generated ";
    private static final String CHECKSUM_PLACEHOLDER = "{checksum}";

    private final ConduitContext context;
    private List<String> scope = List.of();

    /**
     * Main constructor.
     *
     * @param context the jars holding the classes to generate from, and anything they reference
     */
    public ConduitGenerator(@NotNull ConduitContext context) {
        this.context = context;
    }

    /**
     * Generates the source of a single conduit.
     *
     * @param className the binary name of the class to mirror
     * @param targetPackage the package to generate into
     * @return the source, or null if the class is not in the context
     */
    public String generate(@NotNull String className, @NotNull String targetPackage) {
        return this.generate(className, targetPackage, new LinkedHashSet<>());
    }

    /**
     * Generates the source of a single conduit, collecting the classes its methods refer to.
     *
     * @param className the binary name of the class to mirror
     * @param targetPackage the package to generate into
     * @param referenced receives the binary names of non-JDK classes the conduit refers to
     * @return the source, or null if the class is not in the context
     */
    public String generate(@NotNull String className, @NotNull String targetPackage, @NotNull Set<String> referenced) {
        ClassNode node = this.read(className);
        if (node == null) {
            return null;
        }

        Map<String, ClassNode> world = this.world(className);
        ClassNode shape = shapeOf(node, world);

        Map<String, List<Bindings.Bind>> binds = new LinkedHashMap<>();
        List<Bindings.Bind> derived = ScopedValue.where(Effects.CLASSES, this::read).call(() -> Bindings.of(shape, world));
        for (Bindings.Bind bind : derived) {
            binds.computeIfAbsent(bind.member(), member -> new ArrayList<>()).add(bind);
        }

        List<FieldNode> fields = new ArrayList<>();
        for (FieldNode field : shape.fields) {
            if ((field.access & Opcodes.ACC_STATIC) != 0) {
                continue;
            }

            if (shape != node && !declaresReader(node, field.name)) {
                continue;
            }
            fields.add(field);
        }

        Set<String> imports = new LinkedHashSet<>();
        Set<String> emitted = new LinkedHashSet<>();
        StringBuilder members = new StringBuilder();
        List<String> unmapped = new ArrayList<>();
        for (FieldNode field : fields) {
            if (!binds.containsKey(field.name)) {
                unmapped.add(field.name + " (" + this.readableType(field, new LinkedHashSet<>(), new LinkedHashSet<>()) + ")");
                continue;
            }

            Set<String> names = new LinkedHashSet<>();
            String type = this.readableType(field, new LinkedHashSet<>(), names);

            if (!this.scope.isEmpty() && !this.scope.containsAll(names)) {
                unmapped.add(field.name + " (" + type + ", needs " + String.join(", ", missing(names)) + ")");
                referenced.addAll(names);
                continue;
            }

            this.readableType(field, imports, referenced);
            members.append('\n');
            for (Bindings.Bind bind : binds.get(field.name)) {
                members.append("    ").append(bind).append('\n');
            }
            emitted.add(field.name);
            members.append("    ").append(type).append(' ').append(field.name).append("();\n");
        }

        // Abstract methods are filled in by subclasses, usually with a constant or a field read,
        // which the proxy resolves the same way as a field.
        for (MethodNode method : shape.methods) {
            if (!derivableAccessor(method)) {
                continue;
            }
            if (emitted.contains(method.name)) {
                continue;
            }

            Type returned = Type.getReturnType(method.desc);
            Set<String> names = new LinkedHashSet<>();
            String type = this.readableType(returned, new LinkedHashSet<>(), names);

            if (!this.scope.isEmpty() && !this.scope.containsAll(names)) {
                unmapped.add(method.name + "() (" + type + ", needs " + String.join(", ", missing(names)) + ")");
                referenced.addAll(names);
                continue;
            }

            this.readableType(returned, imports, referenced);
            imports.add("java.util.List");
            emitted.add(method.name);

            members.append('\n')
                    .append("    // Every value this method can return, one per branch.\n")
                    .append("    List<").append(type).append("> ").append(method.name).append("();\n");
        }

        // A functional interface has no state, only the lambda or method reference it was given.
        if (members.isEmpty() && functionalMethod(shape) != null) {
            members.append('\n')
                    .append("    @Conduit.Implementation\n")
                    .append("    String implementation();\n");
        }

        StringBuilder out = new StringBuilder("package ").append(targetPackage).append(";\n\n");
        out.append("import org.geysermc.conduit.Conduit;\n");
        if (!imports.isEmpty()) {
            out.append('\n');
        }
        for (String type : imports) {
            out.append("import ").append(type).append(";\n");
        }

        out.append('\n').append(STAMP).append(CHECKSUM_PLACEHOLDER).append(" from ").append(className)
                .append(". Edit freely, edits are kept when regenerating\n");
        out.append("@Conduit(\"").append(className).append("\")\n");
        out.append("public interface ").append(simpleName(className)).append(" {\n");
        out.append(members);

        if (!unmapped.isEmpty()) {
            out.append('\n');
            for (String field : unmapped) {
                out.append("    // not derivable: ").append(field).append('\n');
            }
        }

        return stamp(out.append("}\n").toString());
    }

    // The checksum covers the file with the placeholder still in it, so it can be rechecked.
    private static String stamp(String source) {
        CRC32 checksum = new CRC32();
        checksum.update(source.getBytes(StandardCharsets.UTF_8));
        return source.replace(CHECKSUM_PLACEHOLDER, Long.toHexString(checksum.getValue()));
    }

    /**
     * Checks a generated file's checksum against its contents, to tell whether it was edited
     * since it was generated.
     *
     * @param source the contents of a generated file
     * @return whether it is unchanged, false if it has no checksum
     */
    public static boolean isUnmodified(@NotNull String source) {
        int marker = source.indexOf(STAMP);
        if (marker < 0) {
            return false;
        }

        int start = marker + STAMP.length();
        int end = start;
        while (end < source.length() && Character.digit(source.charAt(end), 16) >= 0) {
            end++;
        }

        String recorded = source.substring(start, end);
        return !recorded.isEmpty()
                && stamp(source.substring(0, start) + CHECKSUM_PLACEHOLDER + source.substring(end)).equals(source);
    }

    /**
     * Generates a set of conduits that refer to each other. A method whose type is outside the
     * set is left out and reported, rather than generated against a type that won't exist.
     *
     * @param directory the directory to write into
     * @param targetPackage the package to generate into
     * @param classNames the binary names of the classes to mirror
     * @return the files written
     * @throws IOException if a file can't be read or written
     */
    public List<Path> generateAll(@NotNull Path directory, @NotNull String targetPackage,
                                  @NotNull List<String> classNames) throws IOException {
        Files.createDirectories(directory);
        this.scope = List.copyOf(classNames);

        Set<String> produced = new LinkedHashSet<>();
        for (String className : classNames) {
            produced.add(simpleName(className));
        }

        List<Path> written = new ArrayList<>();
        List<Path> kept = new ArrayList<>();
        Set<String> referenced = new LinkedHashSet<>();
        for (String className : classNames) {
            String source = this.generate(className, targetPackage, referenced);
            if (source == null) {
                System.err.println("Not on the context, skipped: " + className);
                continue;
            }

            Path file = directory.resolve(simpleName(className) + ".java");
            if (Files.exists(file) && !isUnmodified(Files.readString(file))) {
                kept.add(file);
                continue;
            }

            Files.writeString(file, source);
            written.add(file);
        }

        if (!kept.isEmpty()) {
            System.err.println("Left alone, edited since it was generated:");
            kept.forEach(file -> System.err.println("  " + file));
        }

        referenced.removeIf(name -> produced.contains(simpleName(name)));
        if (!referenced.isEmpty()) {
            System.err.println("Referenced but not generated. Add these, or delete the accessors that use them:");
            referenced.forEach(name -> System.err.println("  " + name));
        }

        return written;
    }

    private static boolean derivableAccessor(MethodNode method) {
        if ((method.access & Opcodes.ACC_ABSTRACT) == 0) {
            return false;
        }
        if ((method.access & Opcodes.ACC_STATIC) != 0) {
            return false;
        }
        if ((method.access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0) {
            return false;
        }
        if ("<init>".equals(method.name) || "<clinit>".equals(method.name)) {
            return false;
        }

        return Type.getReturnType(method.desc).getSort() != Type.VOID;
    }

    private static MethodNode functionalMethod(ClassNode node) {
        if ((node.access & Opcodes.ACC_INTERFACE) == 0) {
            return null;
        }

        MethodNode single = null;
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_ABSTRACT) == 0) {
                continue;
            }
            if ((method.access & Opcodes.ACC_STATIC) != 0) {
                continue;
            }
            if (single != null) {
                return null;
            }

            single = method;
        }

        return single;
    }

    private static ClassNode shapeOf(ClassNode node, Map<String, ClassNode> world) {
        if ((node.access & Opcodes.ACC_INTERFACE) == 0 || !node.fields.isEmpty()) {
            return node;
        }

        for (ClassNode candidate : world.values()) {
            if (candidate.interfaces != null && candidate.interfaces.contains(node.name)) {
                return candidate;
            }
        }

        return node;
    }

    private List<String> missing(Set<String> names) {
        List<String> out = new ArrayList<>();
        for (String name : names) {
            if (!this.scope.contains(name)) {
                out.add(name);
            }
        }

        return out;
    }

    private static boolean declaresReader(ClassNode node, String member) {
        return node.methods.stream().anyMatch(method ->
                method.name.equals(member) && Type.getArgumentTypes(method.desc).length == 0);
    }

    private Map<String, ClassNode> world(String className) {
        Map<String, ClassNode> world = new LinkedHashMap<>();
        for (String name : this.scope.isEmpty() ? List.of(className) : this.scope) {
            ClassNode node = this.read(name);
            if (node != null) {
                world.put(node.name, node);
            }
        }

        return world;
    }

    private ClassNode read(String className) {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream in = this.context.classLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }

            ClassNode node = new ClassNode();

            new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            return node;
        } catch (IOException e) {
            return null;
        }
    }

    private String readableType(FieldNode field, Set<String> imports, Set<String> referenced) {
        if (field.signature != null) {
            String generic = this.fromSignature(field.signature, new int[]{0}, imports, referenced);
            if (generic != null) {
                return generic;
            }
        }

        return this.readableType(Type.getType(field.desc), imports, referenced);
    }

    private String readableType(Type type, Set<String> imports, Set<String> referenced) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "Boolean";
            case Type.CHAR -> "Character";
            case Type.BYTE -> "Byte";
            case Type.SHORT -> "Short";
            case Type.INT -> "Integer";
            case Type.FLOAT -> "Float";
            case Type.LONG -> "Long";
            case Type.DOUBLE -> "Double";
            case Type.ARRAY -> this.readableType(type.getElementType(), imports, referenced) + "[]".repeat(type.getDimensions());
            case Type.OBJECT -> this.declare(type.getClassName(), imports, referenced);
            default -> type.getClassName();
        };
    }

    private String declare(String className, Set<String> imports, Set<String> referenced) {
        if (className.startsWith("java.")) {
            if (!className.startsWith("java.lang.")) {
                imports.add(className);
            }
        } else {
            referenced.add(className);
        }

        return simpleName(className);
    }

    private String fromSignature(String signature, int[] cursor, Set<String> imports, Set<String> referenced) {
        if (cursor[0] >= signature.length()) {
            return null;
        }

        char c = signature.charAt(cursor[0]);
        switch (c) {
            case 'Z', 'C', 'B', 'S', 'I', 'F', 'J', 'D' -> {
                cursor[0]++;
                return this.readableType(Type.getType(String.valueOf(c)), imports, referenced);
            }
            case '[' -> {
                cursor[0]++;
                String element = this.fromSignature(signature, cursor, imports, referenced);
                return element == null ? null : element + "[]";
            }
            case '*' -> {
                cursor[0]++;
                return "?";
            }
            case '+', '-' -> {
                cursor[0]++;
                return this.fromSignature(signature, cursor, imports, referenced);
            }
            case 'T' -> {
                int end = signature.indexOf(';', cursor[0]);
                if (end < 0) {
                    return null;
                }
                cursor[0] = end + 1;
                return "Object";
            }
            case 'L' -> {
                int end = cursor[0] + 1;
                while (end < signature.length() && signature.charAt(end) != '<' && signature.charAt(end) != ';') {
                    end++;
                }
                if (end >= signature.length()) {
                    return null;
                }

                String className = signature.substring(cursor[0] + 1, end).replace('/', '.');
                String name = this.declare(className, imports, referenced);
                cursor[0] = end;
                if (signature.charAt(cursor[0]) != '<') {
                    cursor[0]++;
                    return name;
                }

                // Generated conduits are never generic, so only JDK types keep their type arguments.
                boolean generic = className.startsWith("java.");
                cursor[0]++;
                List<String> arguments = new ArrayList<>();
                while (cursor[0] < signature.length() && signature.charAt(cursor[0]) != '>') {
                    String argument = generic
                            ? this.fromSignature(signature, cursor, imports, referenced)
                            : this.fromSignature(signature, cursor, new LinkedHashSet<>(), new LinkedHashSet<>());
                    if (argument == null) {
                        return null;
                    }
                    arguments.add(argument);
                }

                cursor[0] += 2;
                return generic ? name + "<" + String.join(", ", arguments) + ">" : name;
            }
            default -> {
                return null;
            }
        }
    }

    private static String simpleName(String className) {
        String name = className.substring(className.lastIndexOf('.') + 1);
        return name.substring(name.lastIndexOf('$') + 1);
    }

    /**
     * Generates conduits from the command line, the same way as {@link #generateAll}.
     *
     * @param args the output directory, the package, a comma-separated list of jars, then each class
     * @throws IOException if a file can't be read or written
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            System.err.println("Usage: ConduitGenerator <output-directory> <package> <jar>[,<jar>..] <class>..");
            return;
        }

        List<Path> jars = new ArrayList<>();
        for (String jar : args[2].split(",")) {
            jars.add(Path.of(jar));
        }

        ConduitContext context = ConduitContext.load(jars.toArray(Path[]::new));
        List<Path> written = new ConduitGenerator(context)
                .generateAll(Path.of(args[0]), args[1], List.of(args).subList(3, args.length));
        written.forEach(file -> System.out.println("Wrote " + file));
    }
}
