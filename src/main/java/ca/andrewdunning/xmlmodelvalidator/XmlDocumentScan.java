package ca.andrewdunning.xmlmodelvalidator;

import java.nio.file.Path;
import java.util.List;

/** The metadata collected from a single initial XML scan. */
record XmlDocumentScan(
        Path file,
        List<XmlModelEntry> xmlModelEntries,
        List<String> schemaLocations,
        ValidationIssue wellFormednessIssue) {
    XmlDocumentScan {
        xmlModelEntries = List.copyOf(xmlModelEntries);
        schemaLocations = List.copyOf(schemaLocations);
    }
}
