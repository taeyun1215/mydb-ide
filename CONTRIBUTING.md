# Contributing to MyDB IDE

English · [한국어](CONTRIBUTING.ko.md)

Thanks for your interest. Bug reports, ideas, documentation and code are all welcome.

- [Ways to contribute](#ways-to-contribute)
- [Development setup](#development-setup)
- [Project layout](#project-layout)
- [Pull requests](#pull-requests)
- [Commit messages](#commit-messages)
- [Releases](#releases)

## Ways to contribute

- **Report a bug**: use the [bug report form](https://github.com/taeyun1215/mydb-ide/issues/new?template=bug_report.yml). Please remove host names, user names and anything else private from logs and screenshots.
- **Suggest a feature**: use the [feature request form](https://github.com/taeyun1215/mydb-ide/issues/new?template=feature_request.yml), or start a [discussion](https://github.com/taeyun1215/mydb-ide/discussions) first if the idea is still rough.
- **Fix something**: issues labeled [`good first issue`](https://github.com/taeyun1215/mydb-ide/labels/good%20first%20issue) are a good start. Comment on the issue so nobody else picks the same one.
- **Report a vulnerability**: privately, as described in [SECURITY.md](SECURITY.md).

## Development setup

Requirements:

- JDK 21 or newer to run Gradle (the build provisions the JDK 25 toolchain it compiles with)
- Docker, for the test databases (PostgreSQL 17, MySQL 8.4, MariaDB 11.4 and an sshd, started by Testcontainers)
- For a full app build only: about 30 GB of disk space and the platform source tree (see the README)

Run the tests:

```sh
src/scripts-test.sh                               # database layer
src/plugin-test.sh                                # IDE features
src/plugin-test.sh 'dev.teo.mydb.plugin.sql.*'    # a subset
```

The Gradle projects in `src/core` and `src/plugin` compile the sources of `src/product` and are the fastest way to iterate. Build the full app only when you need to try it by hand, and run it with `tools/mydb-run.sh` so your normal settings stay untouched.

## Project layout

| Change | Module | Tests |
|---|---|---|
| Connections, introspection, execution, DDL, compare, import/export (no UI) | `src/product/mydb.core` | `src/core` |
| Explorer, SQL language, consoles, grid, dialogs, diagrams | `src/product/mydb.plugin` | `src/plugin` |
| Product branding and IDE customization | `src/product/mydb.resources` | – |
| Product build | `src/product/build` | full build |

Changes to the platform itself are a last resort. If one is needed, update `src/product/patches/` and explain why in the pull request.

## Pull requests

1. For anything larger than a small fix, open an issue first so we can agree on the approach.
2. Fork, create a branch from `main` (`fix/grid-null-sort`, `feat/mysql-compare`).
3. Add or update tests. Tests run against the seed schema in `testdb/seed`; tests that change data use a fresh database.
4. Match the surrounding code: Kotlin, four-space indentation, short KDoc on public classes, comments that explain why.
5. Add a line under **Unreleased** in [CHANGELOG.md](CHANGELOG.md) for user-visible changes.
6. Run both test suites, then open the pull request and fill in the template.

CI runs both test suites on every pull request. A maintainer reviews and merges with squash.

By submitting a pull request you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).

## Commit messages

Use a short area prefix and the imperative mood:

```
grid: keep pending edits when the page is reloaded
mysql: read generated columns
docs: explain SSH key passphrases
```

## Releases

Versions follow the platform year and release (`2026.2.3`). A release is cut from `main`, tagged `vX.Y.Z`, and its installers are attached to the [GitHub release](https://github.com/taeyun1215/mydb-ide/releases) with SHA-256 checksums. The changes are summarized in [CHANGELOG.md](CHANGELOG.md).

Please follow the [Code of Conduct](CODE_OF_CONDUCT.md) in all project spaces.
