package ca.andrewdunning.xmlmodelvalidator;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs Jing in-process and converts its textual diagnostics into structured validation issues.
 */
final class JingRunner {
    private static final Pattern JING_ISSUE_PATTERN = Pattern.compile("^(.*?)(?::(\\d+)(?::(\\d+))?)?:\\s*(.+)$");
    private static final Pattern LEADING_SEVERITY_PATTERN = Pattern.compile("^(?i)(error|warning):\\s*");
    private static final Pattern QUOTED_TOKEN_PATTERN = Pattern.compile("\"([^\"\\r\\n]+)\"");

    /**
     * Serializes access because Jing writes through global stdout/stderr streams.
     */
    synchronized List<ValidationIssue> validate(Path schema, Path xmlFile) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setOut(capture);
            System.setErr(capture);
            Class<?> driverClass = Class.forName("com.thaiopensource.relaxng.util.Driver");
            var constructor = driverClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object driver = constructor.newInstance();
            var method = driverClass.getMethod("doMain", String[].class);
            method.setAccessible(true);
            exitCode = (Integer) method.invoke(driver, (Object) new String[] {
                    schema.toString(),
                    xmlFile.toString()
            });
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            capture.close();
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        if (exitCode == 0) {
            return List.of();
        }

        List<ValidationIssue> issues = new ArrayList<>();
        for (String line : output.lines().map(String::trim).filter(text -> !text.isEmpty()).toList()) {
            Matcher matcher = JING_ISSUE_PATTERN.matcher(line);
            if (matcher.matches()) {
                Integer lineNumber = matcher.group(2) != null ? Integer.valueOf(matcher.group(2)) : null;
                Integer column = matcher.group(3) != null ? Integer.valueOf(matcher.group(3)) : null;
                issues.add(new ValidationIssue(xmlFile, normalizeMessage(matcher.group(4)), lineNumber, column, false));
            } else {
                issues.add(new ValidationIssue(xmlFile, normalizeMessage(line), null, null, false));
            }
        }
        return issues;
    }

    static String normalizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String withoutSeverity = LEADING_SEVERITY_PATTERN.matcher(message).replaceFirst("");
        return QUOTED_TOKEN_PATTERN.matcher(withoutSeverity).replaceAll("`$1`");
    }
}
