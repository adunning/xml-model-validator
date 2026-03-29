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
- supports repository configuration from a single TOML file for schema aliases
  and rule-based `xml-model` handling
- supports optional rule-based `xml-model` configuration by directory and/or
  file extension, including fallback and full inline replacement modes
- resolves XML paths, caches, and optional aliases against the consuming
  repository when run as a GitHub Action
- emits GitHub annotations for validation failures and warnings

## GitHub Action

Minimal usage:

```yaml
- uses: actions/checkout@v6
- uses: adunning/xml-model-validator@v1
```

Version tag semantics:

- `@v1` is a floating major tag that tracks the latest `1.x.y` release.
- `@v1.0.0` is an immutable exact release tag.
- This repository publishes releases from `vX.Y.Z` tags and then updates the
  matching major tag (`vX`) automatically.

Validate a directory recursively:

```yaml
- uses: adunning/xml-model-validator@v1
  with:
    directory: collections
```

Validate XML files stored with a non-`.xml` extension:

```yaml
- uses: adunning/xml-model-validator@v1
  with:
    directory: styles
    file_extensions: csl
```

Validate files that omit inline `xml-model` declarations by applying a fallback
rule from repository config:

```yaml
- uses: adunning/xml-model-validator@v1
  with:
    directory: styles
    file_extensions: csl
    config: .xml-validator/config.toml
```

Validate a directory with one inline Action rule instead of a repository config
file:

```yaml
- uses: adunning/xml-model-validator@v1
  with:
    directory: styles
    file_extensions: csl
    xml_model_rule_mode: replace
    xml_model_rule_directory: styles
    xml_model_rule_extension: csl
    xml_model_declarations: |
      href="styles/schema.rng" schematypens="http://relaxng.org/ns/structure/1.0"
      href="styles/rules.sch" schematypens="http://purl.oclc.org/dsdl/schematron"
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

- `files`: space-separated list of files to validate explicitly
- `file_list`: newline-delimited file list path
- `directory`: directory to scan recursively
- `file_extensions`: comma- or whitespace-separated file extensions to discover when scanning directories or changed files; a leading period is optional and the default is `.xml`
- `changed_only`: validate only files with matching extensions changed by the current push or pull request
- `changed_source`: source for `changed_only` file discovery (`auto`, `api`, `git`)
- `jobs`: number of workers, `0` means automatic
- `config`: optional TOML validator config file containing schema aliases and
  `xml-model` rules
- `xml_model_rule_mode`: optional inline `xml-model` rule mode (`fallback` or
  `replace`)
- `xml_model_rule_directory`: optional directory scope for the inline
  `xml-model` rule
- `xml_model_rule_extension`: optional file extension scope for the inline
  `xml-model` rule; a leading period is optional
- `xml_model_declarations`: optional newline-delimited declarations for one
  inline `xml-model` rule
- `fail_fast`: stop after the first failing file

If you do not provide `files`, `file_list`, `directory`, or `changed_only`,
the action validates all matching files in the repository by default.

Selection precedence is `directory`, then `file_list`, then `files`, then
`changed_only`, then the repository-wide default.

When `changed_only: true`:

- `changed_source: auto` (default) tries the GitHub API for pull request and
  push events, then falls back to git diff if API discovery is unavailable.
- `changed_source: api` requires GitHub event context and API access.
- `changed_source: git` uses local git diff logic.
- If no changed files with matching extensions are found, the action reports that validation was
  skipped and exits successfully.

## Configuration

For repositories that need local schema aliases or rule-based `xml-model`
behaviour, provide `.xml-validator/config.toml`. Use `--config` or the
`config` Action input to override that location.

Example:

```toml
[schema_aliases]
"https://example.com/schema.rng" = "schemas/local.rng"

[[xml_model_rules]]
directory = "styles"
extension = "csl"
mode = "fallback"

[[xml_model_rules.declarations]]
href = "styles/schema.rng"
schematypens = "http://relaxng.org/ns/structure/1.0"

[[xml_model_rules]]
directory = "tei"
extension = "xml"
mode = "replace"

[[xml_model_rules.declarations]]
href = "../schemas/tei.rng"
schematypens = "http://relaxng.org/ns/structure/1.0"

[[xml_model_rules.declarations]]
href = "../schemas/tei.sch"
schematypens = "http://purl.oclc.org/dsdl/schematron"
```

`schema_aliases` maps remote schema URLs to local files relative to the config
file.

`xml_model_rules` lets you define different schema sets for different
directories or extensions. Rule `directory` values are resolved relative to the
repository root. Rule `extension` values may be written with or without a
leading period. Declaration `href` values in config and Action-supplied rules
are also resolved from the repository root.

Each rule can match by directory and/or extension, and can either:

- `fallback`: apply only when the file has no inline `xml-model` declarations
- `replace`: ignore inline `xml-model` declarations and use the configured
  declarations instead

When multiple rules match a file, the most specific directory rule wins; an
extension-qualified rule beats the same directory without an extension. If two
rules match with the same specificity and precedence, validation fails with an
ambiguity error instead of choosing one implicitly. Inline `xml-model`
processing instructions inside the XML file still follow standard XML relative
resolution against the XML file itself.

The GitHub Action’s inline override inputs define only one rule per run. Use
the TOML config file when you need multiple directory-specific or
extension-specific rules in the same workflow.

Each declaration supports the same fields the validator reads from inline
`xml-model` instructions:

- `href` (required)
- `schematypens` (optional)
- `type` (optional)
- `phase` (optional)

The config schema is strict: unsupported keys are rejected so configuration
mistakes fail clearly instead of being ignored silently.

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
java -jar target/xml-model-validator.jar --directory path/to/styles --file-extensions csl -j 0
java -jar target/xml-model-validator.jar --directory path/to/styles --file-extensions csl --config .xml-validator/config.toml -j 0
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

4. Tag and push a strict SemVer tag (`vX.Y.Z`):

```bash
git tag -a vX.Y.Z -m "Release vX.Y.Z"
git push origin vX.Y.Z
```

5. The release workflow creates the GitHub Release automatically from that tag,
  and then force-updates the major tag (`vX`) to the same commit.

  Example: pushing `v1.0.0` publishes the release and updates `v1`.

6. Do not manually create or push `v1`, `v2`, etc.; those tags are managed by
  the release workflow to stay synchronized with the latest patch/minor in each
  major line.

The release workflow validates version metadata, verifies the jar runtime
version, builds the project, and publishes the runnable jar plus SHA-256
checksum as GitHub Release assets.
