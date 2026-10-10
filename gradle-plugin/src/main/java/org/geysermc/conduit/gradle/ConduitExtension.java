package org.geysermc.conduit.gradle;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;

import javax.inject.Inject;

/**
 * The {@code conduit} block.
 */
public abstract class ConduitExtension {
    private final Configuration context;

    /**
     * Creates the extension. Gradle calls this when the plugin is applied.
     *
     * @param context the configuration {@link #context} adds dependencies to
     */
    @Inject
    public ConduitExtension(Configuration context) {
        this.context = context;
    }

    /**
     * The project's dependency handler, injected by Gradle.
     *
     * @return the dependency handler
     */
    @Inject
    protected abstract DependencyHandler getDependencies();

    /**
     * The package generated conduits are written into.
     *
     * @return the package to generate conduits into
     */
    public abstract Property<String> getTargetPackage();

    /**
     * The Conduit version added to the project's dependencies and used to run the generator.
     *
     * @return the Conduit version to depend on, by default the plugin's own
     */
    public abstract Property<String> getVersion();

    /**
     * The directory generated sources are written to and compiled from.
     *
     * @return where generated sources are written, by default {@code build/generated/conduit}
     */
    public abstract DirectoryProperty getOutputDirectory();

    /**
     * The jars the generator reads the mirrored classes from.
     *
     * @return the jars the generated conduits are read from
     */
    public abstract ConfigurableFileCollection getContextJars();

    /**
     * The classes to generate a conduit for.
     *
     * @return the binary names of the classes to generate conduits for
     */
    public abstract ListProperty<String> getClasses();

    /**
     * Adds files to read classes from, as accepted by {@code project.files(...)}.
     *
     * @param jars the files
     */
    public void contextJars(Object... jars) {
        this.getContextJars().from(jars);
    }

    /**
     * Adds dependencies to read classes from, in any notation {@code dependencies} accepts.
     *
     * @param notations the dependencies
     */
    public void context(Object... notations) {
        for (Object notation : notations) {
            if (notation instanceof Provider<?> provider) {
                this.getDependencies().addProvider(this.context.getName(), provider);
            } else {
                this.getDependencies().add(this.context.getName(), notation);
            }
        }
    }

    /**
     * Adds classes to generate conduits for.
     *
     * @param names the binary names of the classes
     */
    public void classes(String... names) {
        this.getClasses().addAll(names);
    }
}
