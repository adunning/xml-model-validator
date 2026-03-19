package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class JingRunnerTest {
    @Test
    void normalizesSeverityPrefixAndQuotedTokens() {
        String normalized = JingRunner.normalizeMessage(
                "error: element \"respStmt\" not allowed yet; missing required element \"title\"");

        assertEquals("element `respStmt` not allowed yet; missing required element `title`", normalized);
    }

    @Test
    void preservesMessagesWithoutSeverityPrefix() {
        String normalized = JingRunner.normalizeMessage("element \"a\" conflicts with \"b\"");

        assertEquals("element `a` conflicts with `b`", normalized);
    }
}
