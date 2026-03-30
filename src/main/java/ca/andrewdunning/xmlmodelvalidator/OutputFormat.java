package ca.andrewdunning.xmlmodelvalidator;

/**
 * Supported output formats for validation results.
 */
enum OutputFormat {
    TEXT,
    GITHUB,
    JSON;

    static OutputFormat defaultForEnvironment(boolean githubActions) {
        return githubActions ? GITHUB : TEXT;
    }
}
