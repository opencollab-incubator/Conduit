package org.geysermc.conduit.asm;

import org.objectweb.asm.ClassReader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class ClassScanner {
    private ClassScanner() {
    }

    public static List<ClassInfo> scanAll(ConduitClassLoader loader) {
        List<ClassInfo> out = new ArrayList<>();
        for (URL url : loader.getURLs()) {
            try {
                File file = new File(url.toURI());
                if (file.isDirectory()) {
                    scanDirectory(file, out);
                } else if (file.getName().endsWith(".jar")) {
                    scanJar(file, out);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static Function<String, ClassInfo> index(List<ClassInfo> infos) {
        Map<String, ClassInfo> map = new HashMap<>(infos.size());
        for (ClassInfo info : infos) {
            map.putIfAbsent(info.name(), info);
        }
        return map::get;
    }

    private static void scanDirectory(File dir, List<ClassInfo> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, out);
            } else if (file.getName().endsWith(".class")) {
                readTo(file, out);
            }
        }
    }

    private static void scanJar(File jarFile, List<ClassInfo> out) {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    out.add(readInfo(in));
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void readTo(File classFile, List<ClassInfo> out) {
        try (InputStream in = new FileInputStream(classFile)) {
            out.add(readInfo(in));
        } catch (IOException ignored) {
        }
    }

    private static ClassInfo readInfo(InputStream in) throws IOException {
        ClassReader reader = new ClassReader(in);
        return new ClassInfo(reader.getClassName(), reader.getSuperName(), List.of(reader.getInterfaces()));
    }
}
