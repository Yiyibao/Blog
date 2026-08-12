package com.yubai.blog.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Lightweight source-level contracts for the M6 recipe boundary. */
class RecipeArchitectureTest {
    private static final Path RECIPE_ROOT = Path.of("src/main/java/com/yubai/blog/admin/recipe");

    @Test
    void recipeControllersDoNotReachIntoRepositories() throws IOException {
        assertThat(filesMatching("Controller.java"))
                .filteredOn(path -> read(path).matches("(?s).*import .*Repository;.*"))
                .as("recipe controllers must call application services")
                .isEmpty();
    }

    @Test
    void recipeServicesAndAdaptersDoNotDependOnWebControllers() throws IOException {
        assertThat(filesMatching(".java"))
                .filteredOn(path -> !path.getFileName().toString().endsWith("Controller.java"))
                .filteredOn(
                        path ->
                                read(path)
                                                .contains(
                                                        "import org.springframework.web.bind.annotation")
                                        || read(path).matches("(?s).*import .*Controller;.*"))
                .as("recipe domain/services/adapters must not depend on web controllers")
                .isEmpty();
    }

    @Test
    void extractionCoordinatorDoesNotOwnExternalParsingOrArchiveIo() throws IOException {
        var source = read(RECIPE_ROOT.resolve("RecipeExtractionService.java"));
        assertThat(source)
                .doesNotContain(
                        "com.fasterxml.jackson.databind",
                        "org.jsoup",
                        "java.util.zip",
                        "java.net.http",
                        "java.nio.file");
    }

    private static List<Path> filesMatching(String suffix) throws IOException {
        try (Stream<Path> paths = Files.walk(RECIPE_ROOT)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .toList();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read architecture fixture " + path, exception);
        }
    }
}
