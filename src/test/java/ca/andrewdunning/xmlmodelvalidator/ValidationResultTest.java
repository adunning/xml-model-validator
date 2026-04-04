package ca.andrewdunning.xmlmodelvalidator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ValidationResultTest {
    @Test
    void deduplicatesExactIssuesWhilePreservingOrder() {
        ValidationIssue duplicate =
                new ValidationIssue(Path.of("document.xml"), "Repeated\n   message", 12, 4, 12, 4, false);
        ValidationIssue warning = new ValidationIssue(Path.of("document.xml"), "Warning", 20, null, true);

        ValidationResult result =
                new ValidationResult(Path.of("document.xml"), false, List.of(duplicate, duplicate, warning));

        assertEquals(2, result.issues().size());
        assertEquals("Repeated message", result.issues().get(0).message());
        assertEquals(warning, result.issues().get(1));
    }
}
