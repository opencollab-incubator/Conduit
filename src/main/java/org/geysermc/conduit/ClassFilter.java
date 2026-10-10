package org.geysermc.conduit;

import org.geysermc.conduit.asm.ClassInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Selects classes for {@link ConduitContext#scanClasses}. Conditions are combined with and.
 *
 * <pre>{@code
 * ClassFilter.filter()
 *         .inPackage("com.example")
 *         .implementsInterface("com.example.api.Plugin")
 *         .extendsClass("com.example.Base").not();
 * }</pre>
 */
public class ClassFilter {
    @FunctionalInterface
    private interface InfoPredicate {
        boolean test(ClassInfo info, Function<String, ClassInfo> lookup);
    }

    private final List<InfoPredicate> filters = new ArrayList<>();

    private ClassFilter() {
    }

    /**
     * Matches classes implementing an interface, directly or through a superclass or
     * superinterface.
     *
     * @param interfaceClass the interface
     * @return this filter
     */
    public ClassFilter implementsInterface(Class<?> interfaceClass) {
        return this.implementsInterface(interfaceClass.getName());
    }

    /**
     * Matches classes implementing an interface, directly or through a superclass or superinterface.
     *
     * @param interfaceName the binary name of the interface
     * @return this filter
     * @see #implementsInterface(Class)
     */
    public ClassFilter implementsInterface(String interfaceName) {
        String internal = interfaceName.replace('.', '/');
        this.filters.add((info, lookup) -> implementsInterfaceRecursive(info, internal, lookup));
        return this;
    }

    /**
     * Matches classes extending a class, directly or indirectly.
     *
     * @param superClass the superclass
     * @return this filter
     */
    public ClassFilter extendsClass(Class<?> superClass) {
        return this.extendsClass(superClass.getName());
    }

    /**
     * Matches classes extending a class, directly or indirectly.
     *
     * @param superClassName the binary name of the superclass
     * @return this filter
     * @see #extendsClass(Class)
     */
    public ClassFilter extendsClass(String superClassName) {
        String internal = superClassName.replace('.', '/');
        this.filters.add((info, lookup) -> {
            ClassInfo current = info;
            while (current != null && current.superName() != null) {
                if (current.superName().equals(internal)) {
                    return true;
                }
                current = lookup.apply(current.superName());
            }

            return false;
        });

        return this;
    }

    /**
     * Matches classes in a package or any of its subpackages.
     *
     * @param packageName the package name
     * @return this filter
     */
    public ClassFilter inPackage(String packageName) {
        String internal = packageName.replace('.', '/');
        this.filters.add((info, lookup) -> {
            int last = info.name().lastIndexOf('/');
            if (last < 0) {
                return internal.isEmpty();
            }

            return info.name().substring(0, last).startsWith(internal);
        });

        return this;
    }

    /**
     * Inverts the condition added last.
     *
     * @return this filter
     * @throws IllegalStateException if no condition has been added
     */
    public ClassFilter not() {
        if (this.filters.isEmpty()) {
            throw new IllegalStateException("not() must follow a filter");
        }

        int last = this.filters.size() - 1;
        InfoPredicate original = this.filters.get(last);
        this.filters.set(last, (info, lookup) -> !original.test(info, lookup));
        return this;
    }

    boolean test(ClassInfo info, Function<String, ClassInfo> lookup) {
        for (InfoPredicate filter : this.filters) {
            if (!filter.test(info, lookup)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Creates a filter with no conditions.
     *
     * @return a filter matching every class
     */
    public static ClassFilter filter() {
        return new ClassFilter();
    }

    private static boolean implementsInterfaceRecursive(ClassInfo info, String internalIfc, Function<String, ClassInfo> lookup) {
        if (info == null) {
            return false;
        }

        for (String iface : info.interfaces()) {
            if (iface.equals(internalIfc)) {
                return true;
            }

            ClassInfo ifcInfo = lookup.apply(iface);
            if (implementsInterfaceRecursive(ifcInfo, internalIfc, lookup)) {
                return true;
            }
        }

        if (info.superName() != null) {
            return implementsInterfaceRecursive(lookup.apply(info.superName()), internalIfc, lookup);
        }

        return false;
    }
}
