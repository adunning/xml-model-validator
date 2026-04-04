# Contributing

## Local checks

Run the full verification suite before opening a pull request or cutting a
release:

```bash
./gradlew check
```

## Pull requests

- Keep changes narrowly scoped and explain the user-visible effect in the pull
  request description.
- Add or update tests for any behavioural change.
- Update the README, Action metadata, or CLI help when inputs, outputs, or
  user-facing behaviour change.
- Prefer straightforward changes over broad refactors unless the refactor is
  directly needed for correctness or maintainability.

## Project conventions

- The GitHub Action and the CLI are both public interfaces; changes should keep
  them clear, predictable, and well documented.
- Favour standard-library solutions and small, explicit designs over extra
  dependencies unless a dependency clearly reduces complexity or improves
  reliability.
- Keep GitHub-specific feedback high quality: annotations, step summaries,
  outputs, and skip behaviour should remain coherent when the Action interface
  changes.

## Release process

1. Ensure `gradle.properties` and `CITATION.cff` use the target version.
2. Ensure `CITATION.cff` `date-released` matches the planned release date.
3. Run the full verification suite locally:

```bash
./gradlew check
```

4. Tag and push a strict SemVer tag in the form `vX.Y.Z`:

```bash
git tag -a vX.Y.Z -m "Release vX.Y.Z"
git push origin vX.Y.Z
```

5. The release workflow creates the GitHub Release automatically from that tag
   and then force-updates the corresponding major tag (`vX`) to the same
   commit.

   Example: pushing `v1.0.0` publishes the release and updates `v1`.

6. Do not manually create or push `v1`, `v2`, and similar floating major tags.
   The release workflow manages them so they stay synchronized with the latest
   patch or minor release in each major line.

The release workflow validates version metadata, verifies the jar runtime
version, builds the project, and publishes the runnable jar plus SHA-256
checksum as GitHub Release assets.
