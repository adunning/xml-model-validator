# xml-model-validator

Validate XML from `xml-model` processing instructions with a GitHub Action and
CLI designed for Relax NG and Schematron-heavy repositories.

This repository provides:

- a Java CLI
- a self-contained GitHub Action ready for GitHub Marketplace

Release artifacts are published on GitHub Releases as:

- `xml-model-validator.jar`
- `xml-model-validator.jar.sha256`

The validator:

- parses `xml-model` processing instructions with a proper XML parser
- validates Relax NG schemas with Jing
- validates Schematron with Saxon and SchXslt2
- supports Relax NG and Schematron `xml-model` declarations in the same file
- supports Relax NG XML syntax (`.rng`) and RELAX NG Compact Syntax (`.rnc`)
- supports standalone Schematron files and embedded Schematron inside Relax NG XML syntax schemas
- supports Schematron `phase` selection from the `xml-model` processing instruction
- recognizes common schema hints from `schematypens`, `type`, and schema file extensions
- follows remote schema URLs and caches downloaded schemas in the workspace
- supports optional schema alias overrides for repositories that need to pin a
  remote schema URL to a local file
- resolves XML paths, caches, and optional aliases against the consuming
  repository when run as a GitHub Action
- emits GitHub annotations for validation failures and warnings

## GitHub Action

Minimal usage:

```yaml
- uses: actions/checkout@v6
- uses: adunning/xml-model-validator@v1
```

Validate a directory recursively:

```yaml
- uses: adunning/xml-model-validator@v1
  with:
    directory: collections
```

Validate only the XML files changed by the current push or pull request:

```yaml
- uses: adunning/xml-model-validator@v1
  with:
    changed_only: true
```

Choose how changed files are discovered:

```yaml
- uses: adunning/xml-model-validator@v1
  with:
    changed_only: true
    changed_source: auto # auto | api | git
```

Validate explicit files and stop on the first failure:

```yaml
- uses: adunning/xml-model-validator@v1
  with:
    files: docs/a.xml docs/b.xml
    fail_fast: true
```

## Inputs

- `files`: space-separated list of XML files
- `file_list`: newline-delimited file list path
- `directory`: directory to scan recursively
- `changed_only`: validate only XML files changed by the current push or pull request
- `changed_source`: source for `changed_only` file discovery (`auto`, `api`, `git`)
- `jobs`: number of workers, `0` means automatic
- `schema_aliases`: optional TSV file mapping remote schema URLs to local files
- `fail_fast`: stop after the first failing file

If you do not provide `files`, `file_list`, `directory`, or `changed_only`,
the action validates all XML files in the repository by default.

Selection precedence is `directory`, then `file_list`, then `files`, then
`changed_only`, then the repository-wide default.

When `changed_only: true`:

- `changed_source: auto` (default) tries the GitHub API for pull request and
  push events, then falls back to git diff if API discovery is unavailable.
- `changed_source: api` requires GitHub event context and API access.
- `changed_source: git` uses local git diff logic.
- If no changed XML files are found, the action reports that validation was
  skipped and exits successfully.

## Schema aliases

The validator follows `xml-model` directly by default. If a repository needs an
override, provide a TSV file with:

```text
remote-url<TAB>local-path
```

Relative alias paths are resolved from the alias file location.

## Supported schema forms

The action is intentionally focused on the schema types most commonly used with
`xml-model` in editorial workflows:

- Relax NG XML syntax
- RELAX NG Compact Syntax
- standalone Schematron
- embedded Schematron in Relax NG XML syntax schemas

It does not currently attempt to validate every schema language that `xml-model`
can theoretically reference.

## Runtime model

The GitHub Action sets up Java internally, builds the shaded jar from the
action source when needed, then runs it with `java -jar`.

The built jar is cached under `~/.cache/xml-model-validator/jar`, and its cache
key is derived from the action's build inputs so a cached binary is only reused
when the jar-producing contents match.

The action also caches Maven's local repository and wrapper directories under
`~/.m2`, keyed from Maven dependency inputs so dependency downloads are reused
until those inputs change.

Remote schema downloads and prepared Schematron artifacts are cached under
`~/.cache/xml-model-validator/schema-downloads` and
`~/.cache/xml-model-validator/schematron`. The action restores the latest
runtime cache and saves a fresh one at the end of each run so those artifacts
can accumulate safely over time.

The `changed_only` mode expects the repository to be available in the runner,
which normally means using `actions/checkout@v6` earlier in the job.

If you use `changed_source: git`, `fetch-depth: 0` is recommended for reliable
diffs on push and pull request events.

## Local development

Build the runnable jar:

```bash
./mvnw -q -DskipTests package
```

Run:

```bash
java -jar target/xml-model-validator.jar --directory path/to/xml -j 0
```

Show CLI usage:

```bash
java -jar target/xml-model-validator.jar --help
```

Show CLI version:

```bash
java -jar target/xml-model-validator.jar --version
```

Verify a published release artifact:

```bash
VERSION=v1.0.0 # replace with the release tag you want to verify
curl -LO "https://github.com/adunning/xml-model-validator/releases/download/${VERSION}/xml-model-validator.jar"
curl -LO "https://github.com/adunning/xml-model-validator/releases/download/${VERSION}/xml-model-validator.jar.sha256"
shasum -a 256 -c xml-model-validator.jar.sha256
```

## Preparing a release

1. Ensure `pom.xml` and `CITATION.cff` use the target version.
2. Ensure `CITATION.cff` `date-released` matches the planned release date.
3. Run checks locally:

```bash
./mvnw -B verify
```

4. Tag and push (`vX.Y.Z` or `X.Y.Z` are both accepted by release validation).
5. Publish a GitHub Release from that tag.

The release workflow validates version metadata, builds the project, and uploads
the runnable jar and SHA-256 checksum as release assets.
