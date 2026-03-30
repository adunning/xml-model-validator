# XML Model Validator

Validate XML from `xml-model` processing instructions with a GitHub Action and
CLI built for Relax NG and Schematron-heavy workflows such as TEI, DocBook,
JATS, and other complex XML repositories.

It is designed to feel native in GitHub:

- inline annotations on failing files
- a readable step summary for every run
- structured outputs for later workflow steps
- an optional JSON report for artifacts or downstream automation

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
- can emit machine-readable JSON reports for automation
- emits GitHub annotations for validation failures and warnings
- writes GitHub step summaries for Action runs, including skipped changed-file checks

It is a good fit for repositories that keep validation rules in `xml-model`
processing instructions and want pull-request feedback directly in GitHub,
including scholarly editing, technical publishing, journal/article XML, and
other custom XML workflows.

## GitHub Action

What the Action gives you in GitHub:

- inline workflow annotations for validation errors and warnings
- a Markdown step summary with counts, run context, and issue details
- structured outputs for later workflow steps
- an optional saved JSON report for artifacts or downstream automation

Typical workflow:

1. check out the repository
2. run the Action against the whole repository, one directory, explicit files, or changed files only
3. inspect annotations and the step summary in GitHub
4. optionally use the structured outputs or saved JSON report in later steps

### Minimal usage

```yaml
- uses: actions/checkout@v6
- uses: adunning/xml-model-validator@v2
```

That default run validates all matching files in the repository and reports the
result through annotations and the job summary.

### Recommended workflow for most repositories

Save this workflow as `.github/workflows/validate-xml.yml`:

```yaml
name: Validate XML

on:
  push:
    branches:
      - main
    paths:
      - "**.xml"
  pull_request:
    types: [opened, synchronize, reopened, ready_for_review]
    paths:
      - "**.xml"
  schedule:
    - cron: "0 3 * * 1"
  workflow_dispatch:

permissions:
  contents: read
  pull-requests: read

jobs:
  validate-xml:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v6

      - name: Validate changed XML
        if: github.event_name != 'schedule' && github.event_name != 'workflow_dispatch'
        uses: adunning/xml-model-validator@v2
        with:
          changed_files_only: true

      - name: Validate full XML set
        if: github.event_name == 'schedule' || github.event_name == 'workflow_dispatch'
        uses: adunning/xml-model-validator@v2
```

This is the best default for many XML repositories: pull requests and pushes
stay fast because only changed XML files are checked, while the scheduled run
catches drift elsewhere in the repository. If your default branch is not
`main`, replace that value with your repository's default branch name.

If validation also depends on schema or config files, expand the `paths` filter
or remove it so schema-only changes also trigger validation.

### Advanced usage

Version tag semantics:

- `@v2` is a floating major tag that tracks the latest `2.x.y` release.
- `@v2.0.0` is an immutable exact release tag.
- This repository publishes releases from `vX.Y.Z` tags and then updates the
  matching major tag (`vX`) automatically.

Validate a directory recursively:

```yaml
- uses: adunning/xml-model-validator@v2
  with:
    directory: collections
```

Validate XML files stored with a non-`.xml` extension:

```yaml
- uses: adunning/xml-model-validator@v2
  with:
    directory: styles
    file_extensions: csl
```

Use changed-file mode in pull requests and upload a JSON report:

```yaml
- uses: actions/checkout@v6
  with:
    fetch-depth: 0

- id: xml-validate
  uses: adunning/xml-model-validator@v2
  with:
    changed_files_only: true
    json_report_path: reports/xml-validation.json

- uses: actions/upload-artifact@v4
  if: always() && steps.xml-validate.outputs.json_report_path != ''
  with:
    name: xml-validation-report
    path: ${{ steps.xml-validate.outputs.json_report_path }}
```

If you want the Action to behave as predictably as possible in pull requests,
prefer `actions/checkout@v6` with `fetch-depth: 0`.

For repositories that mostly care about pull-request validation, `changed_files_only: true`
plus a saved JSON report is usually the best starting point. For repositories
that want a full repository check on every run, the default Action invocation is
usually enough.

If you want both fast review feedback and a periodic full-repository safety
check, use the recommended workflow above and switch between
`changed_files_only: true` and a default full run based on `github.event_name`.

## Common patterns

Most users will probably want to use only the GitHub Action inputs. Use a
repository config file when you need more than one rule, want stable
repository-wide configuration, or want different schema sets for different
directories.

Apply a fallback remote Relax NG schema to files in one directory with only
Action inputs:

```yaml
- uses: adunning/xml-model-validator@v2
  with:
    directory: styles
    xml_model_rule_mode: fallback
    xml_model_rule_directory: styles
    xml_model_rule_extension: csl
    xml_model_declarations: |
      href="https://example.org/schema/styles.rng" schematypens="http://relaxng.org/ns/structure/1.0"
      href="https://example.org/schema/styles.sch" schematypens="http://purl.oclc.org/dsdl/schematron"
```

Because `file_extensions` is omitted here, the Action discovers both `.xml`
files and `.csl` files in `styles`. Set `file_extensions: csl` as well if you
want to restrict discovery to `.csl` only.

Replace inline declarations for one directory with remote Relax NG and
Schematron rules with only Action inputs:

```yaml
- uses: adunning/xml-model-validator@v2
  with:
    directory: tei
    xml_model_rule_mode: replace
    xml_model_rule_directory: tei
    xml_model_rule_extension: xml
    xml_model_declarations: |
      href="https://example.org/schema/tei.rng" schematypens="http://relaxng.org/ns/structure/1.0"
      href="https://example.org/schema/tei.sch" schematypens="http://purl.oclc.org/dsdl/schematron"
```

Use a repository config file when you need multiple rules or want to pin a
remote schema URL to a local file:

```toml
[schema_aliases]
"https://example.org/schema/styles.rng" = "schemas/styles.rng"

[[xml_model_rules]]
directory = "styles"
extension = "csl"
mode = "fallback"

[[xml_model_rules.declarations]]
href = "https://example.org/schema/styles.rng"
schematypens = "http://relaxng.org/ns/structure/1.0"

[[xml_model_rules]]
directory = "tei"
extension = "xml"
mode = "replace"

[[xml_model_rules.declarations]]
href = "https://example.org/schema/tei.rng"
schematypens = "http://relaxng.org/ns/structure/1.0"

[[xml_model_rules.declarations]]
href = "https://example.org/schema/tei.sch"
schematypens = "http://purl.oclc.org/dsdl/schematron"
```

Validate only the XML files changed by the current push or pull request:

```yaml
- uses: adunning/xml-model-validator@v2
  with:
    changed_files_only: true
```

Choose how changed files are discovered:

```yaml
- uses: adunning/xml-model-validator@v2
  with:
    changed_files_only: true
    changed_source: auto # auto | api | git
```

Validate explicit files and stop on the first failure:

```yaml
- uses: adunning/xml-model-validator@v2
  with:
    files: |
      docs/a.xml
      docs/b.xml
    fail_fast: true
```

## Inputs

- `files`: newline-delimited list of files to validate explicitly
- `files_from`: newline-delimited file list path
- `directory`: directory to scan recursively
- `file_extensions`: comma- or whitespace-separated file extensions to discover when scanning directories or changed files; a leading period is optional and the default is `.xml`
- `changed_files_only`: validate only files with matching extensions changed by the current push or pull request
- `changed_source`: source for `changed_files_only` file discovery (`auto`, `api`, `git`)
- `jobs`: number of workers, `0` means automatic
- `config`: optional TOML validator config file containing schema aliases and
  `xml-model` rules
- `xml_model_rule_mode`: optional inline `xml-model` rule mode (`fallback` or
  `replace`)
- `xml_model_rule_directory`: optional directory scope for the inline
  `xml-model` rule
- `xml_model_rule_extension`: optional file extension scope for the inline
  `xml-model` rule; a leading period is optional, and when `file_extensions` is
  omitted the Action still discovers `.xml` files by default and adds this
  value to the discovery set
- `xml_model_declarations`: optional newline-delimited declarations for one
  inline `xml-model` rule; remote schema URLs are supported and are expected to
  be the most common case
- `fail_fast`: stop after the first failing file
- `json_report_path`: optional path, relative to the repository root or absolute, where the Action should save a JSON validation report

If you do not provide `files`, `files_from`, `directory`, or `changed_files_only`,
the action validates all matching files in the repository by default.

The Action accepts at most one selection input at a time. If you provide more
than one of `files`, `files_from`, `directory`, or `changed_files_only`, the run
fails with an input error.

When `changed_files_only: true`:

- `changed_source: auto` (default) tries the GitHub API for pull request and
  push events, then falls back to git diff if API discovery is unavailable.
- `changed_source: api` requires GitHub event context and API access.
- `changed_source: git` uses local git diff logic.
- If no changed files with matching extensions are found, the action reports that validation was
  skipped, emits a GitHub notice and step summary, and exits successfully.

During GitHub Action runs, the default output format emits both workflow
annotations and a Markdown step summary. The summary includes counts, duration,
run context, and issue details when validation runs, and an explicit skip
message when `changed_files_only` finds nothing to validate.

The Action also exposes structured outputs you can use in later workflow steps:

- `skipped`
- `files_checked`
- `failed_files`
- `warning_count`
- `json_report_path`

## Outputs

- `skipped`: `true` when `changed_files_only` found no matching files and the Action exited successfully without validating
- `files_checked`: number of files actually validated
- `failed_files`: number of files that failed validation
- `warning_count`: number of warning-level issues reported
- `json_report_path`: absolute path to the saved JSON report when `json_report_path` is set; otherwise empty

Example:

```yaml
- id: xml-validate
  uses: adunning/xml-model-validator@v2
  with:
    changed_files_only: true
    json_report_path: reports/xml-validation.json

- name: Show validation result
  if: always()
  run: |
    echo "Skipped: ${{ steps.xml-validate.outputs.skipped }}"
    echo "Checked: ${{ steps.xml-validate.outputs.files_checked }}"
    echo "Failed: ${{ steps.xml-validate.outputs.failed_files }}"
    echo "Warnings: ${{ steps.xml-validate.outputs.warning_count }}"
    echo "JSON report: ${{ steps.xml-validate.outputs.json_report_path }}"
```

Upload the saved JSON report as a workflow artifact:

```yaml
- id: xml-validate
  uses: adunning/xml-model-validator@v2
  with:
    directory: collections
    json_report_path: reports/xml-validation.json

- uses: actions/upload-artifact@v4
  if: always() && steps.xml-validate.outputs.json_report_path != ''
  with:
    name: xml-validation-report
    path: ${{ steps.xml-validate.outputs.json_report_path }}
```

## Configuration Reference

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
href = "https://example.org/schema/styles.rng"
schematypens = "http://relaxng.org/ns/structure/1.0"

[[xml_model_rules]]
directory = "tei"
extension = "xml"
mode = "replace"

[[xml_model_rules.declarations]]
href = "schemas/tei.rng"
schematypens = "http://relaxng.org/ns/structure/1.0"

[[xml_model_rules.declarations]]
href = "schemas/tei.sch"
schematypens = "http://purl.oclc.org/dsdl/schematron"
```

`schema_aliases` maps remote schema URLs to local files relative to the config
file.

`xml_model_rules` lets you define different schema sets for different
directories or extensions. Rule `directory` values are resolved relative to the
repository root. Rule `extension` values may be written with or without a
leading period. Declaration `href` values may be remote `http://` or `https://`
URLs, repository-root-relative local paths, or absolute local paths. Local
declaration `href` values in config and Action-supplied rules are resolved from
the repository root.

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

When an inline rule provides `xml_model_rule_extension` and `file_extensions`
is omitted, the effective discovery set is `.xml` plus the inline rule
extension. Set `file_extensions` explicitly when you want to narrow discovery
to a smaller set such as only `.csl`.

Configured remote schema URLs use the same download and cache behaviour as
remote schema URLs referenced from inline `xml-model` processing instructions.

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

The `changed_files_only` mode expects the repository to be available in the runner,
which normally means using `actions/checkout@v6` earlier in the job.

If you use `changed_source: git`, `fetch-depth: 0` is recommended for reliable
diffs on push and pull request events.

## Local development

Build the runnable jar:

```bash
./mvnw -q -DskipTests package
```

## CLI

The CLI requires exactly one input source per invocation:

- `--directory PATH` to scan a directory recursively
- `--files-from PATH` to read a newline-delimited file list from a file
- `--files-from -` to read a newline-delimited file list from standard input
- `FILES...` to validate explicit file paths

The CLI rejects invocations that omit an input source or combine more than one
input source.

`--files-from` expects one path per line. Blank lines are ignored. When you use
`--files-from -`, paths are read from standard input, which makes the CLI work
well in pipelines such as `find ... | xml-model-validator --files-from -`.

File discovery rules:

- `--directory` and `--files-from` apply `--file-extensions`
- if `--file-extensions` is omitted, discovery defaults to `.xml`
- if an inline rule sets `--rule-extension` and `--file-extensions` is omitted,
  discovery uses both `.xml` and that rule extension
- explicit `FILES...` arguments are validated as given and are not filtered by
  `--file-extensions`

Output formats:

- `--format text` writes human-readable diagnostics
- `--format github` writes GitHub workflow annotations and summaries
- `--format json` writes a machine-readable report to standard output
- if `--format` is omitted, the CLI defaults to `text` locally and `github`
  inside GitHub Actions

Inspection mode:

- `--plan` prints the resolved input source, config path, extensions, rules,
  and file set without running validation
- `--plan` succeeds even when the resolved file set is empty, so it can be used
  to debug discovery

Exit status:

- `0` means validation succeeded
- `1` means one or more files failed validation
- `2` means command-line usage was invalid

Output behaviour:

- by default, successful runs are quiet
- validation warnings and errors are written to standard error
- use `--verbose` to print progress information and successful summaries

Run:

```bash
java -jar target/xml-model-validator.jar --directory path/to/xml -j 0
java -jar target/xml-model-validator.jar --plan --directory path/to/xml
java -jar target/xml-model-validator.jar --verbose --directory path/to/xml -j 0
find path/to/xml -name '*.xml' -print | java -jar target/xml-model-validator.jar --files-from - -j 0
java -jar target/xml-model-validator.jar --files-from path/to/files.txt -j 0
java -jar target/xml-model-validator.jar path/to/a.xml path/to/b.xml -j 0
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

Write a JSON report:

```bash
java -jar target/xml-model-validator.jar --format json path/to/a.xml path/to/b.xml
```

Preview the full validation plan:

```bash
java -jar target/xml-model-validator.jar --plan --format json --directory path/to/xml
```

Verify a published release artifact:

```bash
VERSION=v2.0.0 # replace with the release tag you want to verify
curl -LO "https://github.com/adunning/xml-model-validator/releases/download/${VERSION}/xml-model-validator.jar"
curl -LO "https://github.com/adunning/xml-model-validator/releases/download/${VERSION}/xml-model-validator.jar.sha256"
shasum -a 256 -c xml-model-validator.jar.sha256
```
