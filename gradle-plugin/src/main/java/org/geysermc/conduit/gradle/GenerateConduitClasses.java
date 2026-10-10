package org.geysermc.conduit.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.OutputDirectory;

import javax.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Runs the Conduit generator over the configured context jars.
 */
public abstract class GenerateConduitClasses extends JavaExec {
    /**
     * The package to generate conduits into.
     *
     * @return the package name
     */
    @Input
    public abstract Property<String> getTargetPackage();

    /**
     * The classes to generate a conduit for.
     *
     * @return the binary names of the classes
     */
    @Input
    public abstract ListProperty<String> getClasses();

    /**
     * The jars the generator reads the mirrored classes from.
     *
     * @return the jars
     */
    @Classpath
    public abstract ConfigurableFileCollection getContextJars();

    /**
     * The directory generated sources are written to. It is cleared on every run.
     *
     * @return the output directory
     */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /**
     * File operations, injected by Gradle.
     *
     * @return the file operations
     */
    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    /**
     * Creates the task. Gradle calls this when the task is registered.
     */
    public GenerateConduitClasses() {
        this.getMainClass().set("org.geysermc.conduit.generate.ConduitGenerator");
        this.setDescription("Generates Conduit interfaces from the configured context jars.");
    }

    @Override
    public void exec() {
        if (this.getContextJars().isEmpty()) {
            throw new IllegalStateException("conduit { context(...) / contextJars(...) } is empty, there is nothing to generate against.");
        }

        if (this.getTargetPackage().get().isEmpty()) {
            throw new IllegalStateException("conduit { targetPackage } is not set.");
        }

        // The output is never edited by hand, so clear it rather than let the generator keep stale files.
        File root = this.getOutputDirectory().get().getAsFile();
        this.getFileSystemOperations().delete(spec -> spec.delete(root));

        List<String> args = new ArrayList<>();
        args.add(new File(root, this.getTargetPackage().get().replace('.', '/')).getAbsolutePath());
        args.add(this.getTargetPackage().get());
        args.add(this.getContextJars().getFiles().stream().map(File::getAbsolutePath).collect(Collectors.joining(",")));
        args.addAll(this.getClasses().get());
        this.setArgs(args);

        super.exec();
    }
}
