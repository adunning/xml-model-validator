package ca.andrewdunning.xmlmodelvalidator;

import java.io.IOException;
import java.nio.file.Path;

/** Fetches remote schema resources into a local file that validators can read. */
interface RemoteSchemaFetcher {
    Path fetch(String url) throws IOException, InterruptedException;
}
