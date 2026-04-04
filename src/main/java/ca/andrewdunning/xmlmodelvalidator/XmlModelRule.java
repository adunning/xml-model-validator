package ca.andrewdunning.xmlmodelvalidator;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * One configured rule that can supply or replace {@code xml-model}
 * declarations for matching files.
 */
record XmlModelRule(Path directory, String extension, XmlModelRuleMode mode, List<XmlModelEntry> entries,
        int priority) {
    XmlModelRule(Path directory, String extension, XmlModelRuleMode mode, List<XmlModelEntry> entries) {
        this(directory, extension, mode, entries, 0);
    }

    XmlModelRule {
        extension = normalizeExtension(extension);
        entries = List.copyOf(entries);
    }

    boolean matches(Path file) {
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (directory != null && !normalizedFile.startsWith(directory)) {
            return false;
        }
        if (extension.isEmpty()) {
            return true;
        }
        String filename = normalizedFile.getFileName().toString().toLowerCase(Locale.ROOT);
        return filename.endsWith(extension);
    }

    int specificity() {
        int score = 0;
        if (directory != null) {
            score += directory.getNameCount() * 10;
        }
        if (!extension.isEmpty()) {
            score += 1;
        }
        return score;
    }

    String describe() {
        String directoryDescription = directory == null ? "*" : directory.toString();
        String extensionDescription = extension.isEmpty() ? "*" : extension;
        return "directory=" + directoryDescription + ", extension=" + extensionDescription + ", mode="
                + mode.name().toLowerCase(Locale.ROOT);
    }

    private static String normalizeExtension(String extension) {
        if (extension == null) {
            return "";
        }
        String normalized = extension.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        if (!normalized.startsWith(".")) {
            normalized = "." + normalized;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
