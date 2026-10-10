# Conduit

A library for interfacing with Java bytecode without a real application context.

Conduit is capable of reading and handling compiled Java bytecode without loading it into the classpath. It can be used to analyze and extract information from Java classes, methods, and fields, even when the code is not running in a real application context.

## What Does This Library Solve?

Code often describes its data by calling an API when it starts up, so the normal way to read that data is to load the jar and let it run. That stops working when the classes the code depends on isn't available, requires a huge number of libraries or bloat to even run, or when running it would have side effects you don't want.

### Real-world example 
[Hydraulic](https://github.com/GeyserMC/Hydraulic) is what Conduit was initially built for. It runs on a modded Minecraft server and generates Bedrock resource packs for the mods installed there. When it comes to entities, it needs each mod's entity models and textures, but mods only register those from their client code. 

The Minecraft client isn't present on a server, so loading that code fails on the first client class it references. Even with those classes present, running a mod's client setup on a server is heavily problematic.

Conduit reads the registration calls from the bytecode instead. Hydraulic gets the same models a client would see, and the mod's client code is never loaded.

## Usage

Conduit has two halves. An **intercept** is a handler for calls the scanned code makes to an API. It decides what to do with each call, so it is written by hand. A **conduit** is an interface over a class in the scanned jar, used to read the arguments those calls were given. Conduits mirror real classes, so they are usually generated rather than written.

Say a jar registers colors through an API like this:

```java
ColorRegistry.register("sky", new Color(135, 206, 235));
```

Mirror the API with an intercept handler, and the class with a conduit:

```java
@Intercept("com.example.api.ColorRegistry")
public class ColorRegistry {
    @Intercept.Capture
    public static CaptureChannel<Color> CAPTURE;

    // The method overlay for the real ColorRegistry#register
    public static void register(String name, Color color) {
        CAPTURE.emit(color);
    }
}

@Conduit("com.example.api.Color")
public interface Color {
    Integer red();
    Integer green();
    Integer blue();
}
```

Then scan the jar:

```java
ConduitContext context = ConduitContext.load(Path.of("example.jar"));

VirtualMachine vm = VirtualMachineFactory.create(context).await();
vm.intercept(ColorRegistry.class);
vm.capture(ColorRegistry.CAPTURE, color -> System.out.println(color.red() + ", " + color.green() + ", " + color.blue()));
vm.execute();
```

Simple conduits like the one above can be written by hand, but most are generated from the real class, which works out how each value is built and emits the bindings for it. Only the generator needs the real classes. Generate once, commit the output, and scanning afterwards only needs the jar being scanned.

```java
new ConduitGenerator(ConduitContext.load(Path.of("api.jar")))
        .generateAll(Path.of("src/main/java/com/example/conduit"), "com.example.conduit", List.of("com.example.api.Color"));
```

## Gradle Plugin

The Gradle plugin runs the generator as part of the build and adds Conduit to your dependencies.

```kotlin
plugins {
    id("dev.opencollab.conduit") version "0.1.0-SNAPSHOT"
}

conduit {
    targetPackage = "com.example.conduit"

    context("com.example:api:1.0.0")
    contextJars(file("libs/other-api.jar"))

    classes(
        "com.example.api.Color",
        "com.example.api.Palette"
    )
}
```

Generated sources are written to `build/generated/conduit` and added to the main source set.

## Adding as a Dependency

```kotlin
repositories {
    maven("https://repo.opencollab.dev/maven-snapshots/")
}

dependencies {
    implementation("dev.opencollab:conduit:0.1.0-SNAPSHOT")
}
```

The published jar shades and relocates ASM, so it has no dependencies of its own.

## Building

Conduit requires Java 25.

```
./gradlew build
```

## Support and Development

Please join the [GeyserMC Discord server](https://discord.gg/geysermc) for discussion and support for this project.
