package org.geysermc.conduit.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.toolchain.JavaToolchainService;

/**
 * Generates conduits from the classes they mirror as part of the build, and adds Conduit to the
 * project's dependencies.
 *
 * <pre>{@code
 * plugins {
 *     id("dev.opencollab.conduit")
 * }
 *
 * conduit {
 *     targetPackage = "com.example.conduit"
 *     context("com.example:library:1.0")
 *     classes("com.example.Color", "com.example.Palette")
 * }
 * }</pre>
 *
 * <p>Generated sources go to {@code build/generated/conduit} and are added to the main source set.
 * With no classes configured, nothing is generated and the plugin only adds the dependency.
 */
public class ConduitPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);

        Configuration generator = project.getConfigurations().create("conduitGenerator", configuration -> {
            configuration.setCanBeConsumed(false);
            configuration.setDescription("The Conduit generator, kept off the project's own classpaths.");
        });
        Configuration context = project.getConfigurations().create("conduitContext", configuration -> {
            configuration.setCanBeConsumed(false);
            configuration.setDescription("Dependencies the generated conduits are read from.");
        });

        ConduitExtension conduit = project.getExtensions().create("conduit", ConduitExtension.class, context);
        conduit.getVersion().convention(ConduitPlugin.class.getPackage().getImplementationVersion());
        conduit.getOutputDirectory().convention(project.getLayout().getBuildDirectory().dir("generated/conduit"));
        conduit.getContextJars().from(context);

        Provider<String> dependency = conduit.getVersion().map(version -> "dev.opencollab:conduit:" + version);
        project.getDependencies().addProvider(generator.getName(), dependency);
        project.getDependencies().addProvider(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, dependency);

        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);

        TaskProvider<GenerateConduitClasses> generate = project.getTasks().register("generateConduitClasses", GenerateConduitClasses.class, task -> {
            task.setGroup("conduit");
            task.onlyIf("conduit classes are configured", t -> !conduit.getClasses().get().isEmpty());
            task.classpath(generator);
            task.getTargetPackage().set(conduit.getTargetPackage().orElse(""));
            task.getClasses().set(conduit.getClasses());
            task.getContextJars().from(conduit.getContextJars());
            task.getOutputDirectory().set(conduit.getOutputDirectory());
            task.getJavaLauncher().set(toolchains.launcherFor(java.getToolchain()));
        });

        project.getExtensions().getByType(SourceSetContainer.class).named("main", main ->
                main.getJava().srcDir(project.files(conduit.getOutputDirectory()).builtBy(generate)));
    }
}
