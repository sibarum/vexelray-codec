package dev.vexelray.codec.image.gui;

import dev.vexelray.framework.processor.VexelProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The starter as an application meets it: off its own class file, read by the real processor, generating a wiring that
 * has to compile against the real shell. The same arrangement as the framework's {@code AutomationStarterTest}.
 */
final class ImageStarterTest {

    private static final String APP = """
            package app;
            import dev.vexelray.framework.api.*;
            import dev.vexelray.codec.image.gui.ImageStarter;
            @VexelApp(name = "demo", title = "Demo", starters = ImageStarter.class)
            public final class DemoApp {}
            """;

    @Test
    void namingTheStarterBindsTexturesToTheWindowAndUnbindsThemAtShutdown(@TempDir Path out) throws IOException {
        Compiled compiled = compile(out, Map.of("app.DemoApp", APP));
        assertEquals(List.of(), compiled.errors());

        String wiring = compiled.wiring();
        assertTrue(wiring.contains("imageStarter.textures()"), wiring);
        assertTrue(wiring.contains("imageStarter.texturesBinding(shell.app(), textures)"), wiring);
        assertTrue(wiring.contains(".register(texturesBinding)"), wiring);
    }

    @Test
    void anApplicationsOwnTexturesBackTheStartersOffAndAreTheOnesBound(@TempDir Path out) throws IOException {
        Compiled compiled = compile(out, Map.of("app.DemoApp", APP, "app.Recipes", """
                package app;
                import dev.vexelray.framework.api.*;
                import dev.vexelray.codec.image.gui.Textures;
                @Configuration
                final class Recipes {
                    @Provides Textures textures() { return new Textures(64L << 20); }
                }
                """));
        assertEquals(List.of(), compiled.errors(), "a @Default is backed off, not in conflict");

        String wiring = compiled.wiring();
        assertTrue(wiring.contains("recipes.textures()"), wiring);
        assertFalse(wiring.contains("imageStarter.textures()"), wiring);
        assertTrue(wiring.contains("imageStarter.texturesBinding(shell.app(), textures)"), wiring);
    }

    @Test
    void aPartThatShowsPicturesIsBuiltBeforeTheWindow(@TempDir Path out) throws IOException {
        Compiled compiled = compile(out, Map.of("app.DemoApp", APP, "app.Recipes", """
                package app;
                import dev.vexelray.framework.api.*;
                import dev.vexelray.codec.image.gui.ImageView;
                import dev.vexelray.codec.image.gui.Textures;
                import dev.vexelray.gui.core.Gui;
                import dev.vexelray.gui.core.layout.Length;
                import dev.vexelray.gui.krono.KronoGui;
                @Configuration
                final class Recipes {
                    @Provides ImageView view(Gui gui, KronoGui krono, Textures textures) {
                        return new ImageView(gui, krono, textures, Length.ZERO, Length.ZERO);
                    }
                }
                """));
        assertEquals(List.of(), compiled.errors());

        String wiring = compiled.wiring();
        int view = wiring.indexOf("recipes.view(");
        int binding = wiring.indexOf("imageStarter.texturesBinding(");
        assertTrue(view >= 0 && binding > view, "the tree first, the window's binding after:\n" + wiring);
    }

    // --- harness --------------------------------------------------------------------------------------------

    private record Compiled(List<String> errors, Path sources) {

        String wiring() throws IOException {
            return Files.readString(sources.resolve("app/DemoAppWiring.java"));
        }
    }

    private static Compiled compile(Path out, Map<String, String> sources) throws IOException {
        Path classes = Files.createDirectories(out.resolve("classes"));
        Path generated = Files.createDirectories(out.resolve("generated"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
            files.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(generated.toFile()));
            List<JavaFileObject> units = new ArrayList<>();
            for (Map.Entry<String, String> e : sources.entrySet()) {
                units.add(source(e.getKey(), e.getValue()));
            }
            JavaCompiler.CompilationTask task = javac.getTask(null, files, diagnostics,
                    List.of("-classpath", System.getProperty("java.class.path")), null, units);
            task.setProcessors(List.of(new VexelProcessor()));
            task.call();
        }
        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                errors.add(d.getMessage(Locale.ROOT));
            }
        }
        return new Compiled(errors, generated);
    }

    private static JavaFileObject source(String fqcn, String code) {
        URI uri = URI.create("string:///" + fqcn.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension);
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
    }
}
