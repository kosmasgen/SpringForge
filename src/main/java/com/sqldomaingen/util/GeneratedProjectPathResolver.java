package com.sqldomaingen.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class GeneratedProjectPathResolver {

    private static final Path OUTPUT_ROOT = Path.of("output");

    private GeneratedProjectPathResolver() {
    }

    public static Path resolveGeneratedProjectRoot() {
        if (!Files.exists(OUTPUT_ROOT)) {
            throw new IllegalStateException(
                    "Output directory does not exist: " + OUTPUT_ROOT.toAbsolutePath()
            );
        }

        try (var paths = Files.list(OUTPUT_ROOT)) {
            List<Path> projects = paths
                    .filter(Files::isDirectory)
                    .toList();

            if (projects.isEmpty()) {
                throw new IllegalStateException(
                        "No generated project found under: " + OUTPUT_ROOT.toAbsolutePath()
                );
            }

            if (projects.size() > 1) {
                throw new IllegalStateException(
                        "Multiple generated projects found under output: " + projects
                );
            }

            return projects.getFirst();

        } catch (IOException exception) {
            throw new RuntimeException(
                    "Failed to resolve generated project root.",
                    exception
            );
        }
    }

    public static Path resolveGeneratedJavaRoot() {
        return resolveGeneratedProjectRoot()
                .resolve("src")
                .resolve("main")
                .resolve("java");
    }
}