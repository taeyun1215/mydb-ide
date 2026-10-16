<div align="center">

**English** · [한국어](README.ko.md)

<img src="src/product/mydb.resources/src/mydb.svg" width="112" alt="MyDB IDE">

# MyDB IDE

### A free, open-source desktop IDE for PostgreSQL, MySQL, MariaDB and SQLite.

**Explore schemas, write SQL that knows your database, edit data safely, and compare schemas, all on your own machine.**

[![Release](https://img.shields.io/github/v/release/taeyun1215/mydb-ide?label=release&color=6c5ce7)](https://github.com/taeyun1215/mydb-ide/releases/latest)
[![CI](https://github.com/taeyun1215/mydb-ide/actions/workflows/ci.yml/badge.svg)](https://github.com/taeyun1215/mydb-ide/actions/workflows/ci.yml)
[![Downloads](https://img.shields.io/github/downloads/taeyun1215/mydb-ide/total?color=00b894)](https://github.com/taeyun1215/mydb-ide/releases)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
![Platforms](https://img.shields.io/badge/platform-macOS%20%7C%20Windows%20%7C%20Linux-lightgrey)
![Databases](https://img.shields.io/badge/PostgreSQL%20%7C%20MySQL%20%7C%20MariaDB%20%7C%20SQLite-336791)

[**Download**](#download) · [**Quick start**](#quick-start) · [**Features**](#features) · [**User guide**](docs/guide.md) · [**FAQ**](#faq) · [**Contributing**](CONTRIBUTING.md)

</div>

> [!IMPORTANT]
> The only official sources of MyDB IDE are this repository and its [Releases](https://github.com/taeyun1215/mydb-ide/releases) page. Builds are not yet signed by Apple or Microsoft, so your OS asks for confirmation the first time; see [Download](#download).

---

## Contents

- [Why MyDB IDE](#why-mydb-ide)
- [Download](#download)
- [Quick start](#quick-start)
- [Features](#features)
- [Supported databases](#supported-databases)
- [Keyboard shortcuts](#keyboard-shortcuts)
- [Privacy](#privacy)
- [FAQ](#faq)
- [Building from source](#building-from-source)
- [Testing](#testing)
- [Architecture](#architecture)
- [Roadmap](#roadmap)
- [Contributing](#contributing) · [Security](#security) · [License](#license)

## Why MyDB IDE

| | |
|---|---|
| 🗂️ **One tool for your databases** | PostgreSQL, MySQL, MariaDB and SQLite share one explorer, one console and one result grid. |
| 🧠 **SQL that knows your schema** | Completion, resolution, inspections and quick fixes come from the live database model, including join conditions from foreign keys. |
| 🛡️ **Safe by default** | Grid edits stay pending until you submit them in one transaction. `DELETE` and `UPDATE` without `WHERE` ask first. Data sources can be read-only. |
| 🔒 **Private** | No account, telemetry, update server or plugin marketplace. Passwords live in your OS keychain. |
| 🆓 **Free and open source** | Apache License 2.0, no paid tier. |

## Download

Get the latest version from the [Releases page](https://github.com/taeyun1215/mydb-ide/releases/latest).

| OS | Apple Silicon / ARM64 | Intel / x64 |
|---|---|---|
| **macOS** 12+ | [`macos-arm64.dmg`](https://github.com/taeyun1215/mydb-ide/releases/latest/download/MyDB-IDE-2026.2.3-macos-arm64.dmg) | [`macos-x64.dmg`](https://github.com/taeyun1215/mydb-ide/releases/latest/download/MyDB-IDE-2026.2.3-macos-x64.dmg) |
| **Windows** 10+ | [`windows-arm64.exe`](https://github.com/taeyun1215/mydb-ide/releases/latest/download/MyDB-IDE-2026.2.3-windows-arm64.exe) | [`windows-x64.exe`](https://github.com/taeyun1215/mydb-ide/releases/latest/download/MyDB-IDE-2026.2.3-windows-x64.exe) |
| **Linux** | [`linux-arm64.tar.gz`](https://github.com/taeyun1215/mydb-ide/releases/latest/download/MyDB-IDE-2026.2.3-linux-arm64.tar.gz) | [`linux-x64.tar.gz`](https://github.com/taeyun1215/mydb-ide/releases/latest/download/MyDB-IDE-2026.2.3-linux-x64.tar.gz) |

Each release lists the SHA-256 checksum of every file in `SHA256SUMS.txt`.

<details>
<summary><b>macOS: "MyDB IDE cannot be opened"</b></summary>

The app is not notarized yet. Open the DMG, drag **MyDB IDE** to **Applications**, try to open it once, then go to **System Settings → Privacy & Security** and click **Open Anyway**. Or, in Terminal:

```sh
xattr -dr com.apple.quarantine "/Applications/MyDB IDE.app"
```
</details>

<details>
<summary><b>Windows: "Windows protected your PC"</b></summary>

The installer is not code-signed yet. Click **More info → Run anyway**.
</details>

<details>
<summary><b>Linux</b></summary>

```sh
tar -xzf MyDB-IDE-*-linux-*.tar.gz
./mydb-ide/bin/mydb.sh
```
</details>

## Quick start

1. **Add a data source**: press <kbd>⌘</kbd> <kbd>;</kbd>, click **+**, choose PostgreSQL, MySQL, MariaDB or SQLite, fill in host, database, user and password, then **Test Connection**.
2. **Pick schemas**: on the **Schemas** tab, check the schemas to load.
3. **Query**: a console opens for the new data source. Type a query, press <kbd>⌘</kbd> <kbd>↵</kbd>, and read the results below.
4. **Edit data**: select a table and press <kbd>⌘</kbd> <kbd>↓</kbd>. Change cells, add or delete rows, then submit with <kbd>⌘</kbd> <kbd>↵</kbd>.
5. **Go further**: <kbd>⌘</kbd> <kbd>N</kbd> creates a table, <kbd>⌘</kbd> <kbd>D</kbd> on two schemas compares them, <kbd>⌥</kbd> <kbd>⇧</kbd> <kbd>⌘</kbd> <kbd>U</kbd> opens an ER diagram.

For SSH tunnels, transactions, schema compare, import/export and more, read the **[User guide](docs/guide.md)**.

Already have data sources in another IDE project? **Import Data Sources from IDE Project…** in the explorer toolbar brings them over with their SSH settings.

## Features

<details open>
<summary><b>🗂️ Database explorer</b></summary>

- Data sources → databases → schemas → tables, views, materialized views, routines, sequences, columns, keys and indexes
- Choose which schemas to load; badges show `shown/total`
- Incremental refresh that re-reads only changed relations; the model is saved per project and survives restarts
- Quick documentation (DDL and comments), Go to DDL, Copy Reference, filter, data source colors (also on editor tabs)
- Go to Symbol for tables, views and columns across all data sources
- **Virtual foreign keys** for relations the database does not declare, used by completion, joins and diagrams
</details>

<details open>
<summary><b>🧠 SQL consoles</b></summary>

- Consoles per data source, plus `.sql` files attached to a data source
- Completion for schemas, tables, columns, aliases, CTEs and keywords, with **join conditions from foreign keys** first
- Highlighting of unknown tables and columns, quick fixes, and inspections such as `DELETE` without `WHERE` or non-aggregated columns in `GROUP BY`
- Intentions: qualify identifiers, flip join sides, wrap in a subquery
- Formatter, rename refactoring (tables, columns, aliases) with preview, find usages
- Run the statement at the caret, a selection or the whole script; one result tab per result set; query history
- Manual and automatic transactions, isolation levels, commit and rollback from the toolbar or the Services view
- SQL Script run configurations
</details>

<details open>
<summary><b>📋 Result grid and table editor</b></summary>

- Paging, sorting, local value filters, `WHERE` / `ORDER BY` filters with column completion
- In-place editing, adding and deleting rows; **changes stay pending**, can be previewed as DML, and are submitted in one transaction
- Failed rows are highlighted with the server error; nothing reaches the server before you submit
- Value panel, transpose, exact row count, charts from result columns
- Compare the data of two result sets
</details>

<details>
<summary><b>🏗️ Schema objects</b></summary>

- Create and modify **tables** (columns, keys, foreign keys, indexes, checks, generated columns), **views**, **materialized views**, **sequences** and **schemas** in dialogs with a live DDL preview
- SQL generator, DDL to console, truncate and drop with confirmation
</details>

<details>
<summary><b>🔍 Compare, diagrams and plans</b></summary>

- **Schema compare** of two schemas or data sources: object tree with statuses, side-by-side DDL diff, property diff, and a migration script you can open in a console
- **ER diagrams** with foreign key and view edges; export to PNG, SVG, Mermaid, PlantUML and GraphML
- **Execution plans** (`EXPLAIN`, `EXPLAIN ANALYZE`) as a tree with every node property and as a diagram
</details>

<details>
<summary><b>📦 Import and export</b></summary>

- Export results or tables to CSV, TSV, JSON, SQL `INSERT`s, Markdown, HTML and Excel
- Import CSV/TSV into new or existing tables with type conversion and an error file
- `pg_dump`, `pg_restore` and `psql` for PostgreSQL; `mysqldump` and `mysql` for MySQL
- Dump a schema into a **DDL data source** (a model read from `.sql` files)
- CSV and TSV files open as an editable **Data** tab
</details>

<details>
<summary><b>🔌 Connectivity</b></summary>

- SSH tunnels with a password or a key pair, shared SSH configurations
- SSL modes up to `verify-full`, custom CA, client certificates with key passwords
- Driver properties, read-only data sources, and separate sessions per purpose so the console, the table editor and introspection never block each other
- Import data sources from another IDE project's `.idea/dataSources.xml`
</details>

## Supported databases

| | PostgreSQL | MySQL / MariaDB | SQLite |
|---|:---:|:---:|:---:|
| Explorer and introspection | ✅ | ✅ | ✅ |
| Console, completion, resolution | ✅ | ✅ | ✅ |
| Grid editing | ✅ | ✅ | ✅ |
| Create / modify table | ✅ | ✅ | ✅ |
| Views | ✅ | ✅ | ✅ |
| Materialized views, sequences | ✅ | – | – |
| Execution plan | tree + diagram | table | `EXPLAIN QUERY PLAN` |
| Schema compare | ✅ | planned | planned |
| Dump / restore tools | `pg_dump`, `pg_restore`, `psql` | `mysqldump`, `mysql` | – |
| SSH / SSL | ✅ | ✅ | – |

Tested against PostgreSQL 17, MySQL 8.4, MariaDB 11.4 and SQLite 3.50. MySQL connections use MariaDB Connector/J, which accepts `jdbc:mysql:` URLs.

## Keyboard shortcuts

| Action | macOS | Windows / Linux |
|---|---|---|
| Data sources | <kbd>⌘</kbd> <kbd>;</kbd> | <kbd>Ctrl</kbd> <kbd>Alt</kbd> <kbd>Shift</kbd> <kbd>S</kbd> |
| New query console | <kbd>⇧</kbd> <kbd>⌘</kbd> <kbd>L</kbd> | explorer toolbar |
| Execute / submit grid changes | <kbd>⌘</kbd> <kbd>↵</kbd> | <kbd>Ctrl</kbd> <kbd>Enter</kbd> |
| Edit table data | <kbd>⌘</kbd> <kbd>↓</kbd> | <kbd>F4</kbd> |
| Go to DDL | <kbd>⌘</kbd> <kbd>B</kbd> | <kbd>Ctrl</kbd> <kbd>B</kbd> |
| New object | <kbd>⌘</kbd> <kbd>N</kbd> | <kbd>Ctrl</kbd> <kbd>N</kbd> |
| Modify table | <kbd>⌘</kbd> <kbd>F6</kbd> | <kbd>Ctrl</kbd> <kbd>F6</kbd> |
| Compare structure | <kbd>⌘</kbd> <kbd>D</kbd> | <kbd>Ctrl</kbd> <kbd>D</kbd> |
| SQL generator | <kbd>⌥</kbd> <kbd>⌘</kbd> <kbd>G</kbd> | <kbd>Ctrl</kbd> <kbd>Alt</kbd> <kbd>G</kbd> |
| Diagram | <kbd>⌥</kbd> <kbd>⇧</kbd> <kbd>⌘</kbd> <kbd>U</kbd> | <kbd>Ctrl</kbd> <kbd>Alt</kbd> <kbd>Shift</kbd> <kbd>U</kbd> |
| Quick documentation | <kbd>F1</kbd> | <kbd>Ctrl</kbd> <kbd>Q</kbd> |
| Find usages | <kbd>⌥</kbd> <kbd>F7</kbd> | <kbd>Alt</kbd> <kbd>F7</kbd> |

## Privacy

MyDB IDE does not phone home.

- No usage statistics, crash reports or error uploads; logs stay on your machine
- No update checks, plugin marketplace, online help or account sync
- Passwords and key passphrases are kept in the OS credential store (Keychain on macOS), never in project files
- `.idea/mydbDataSources.xml` holds connection settings without secrets, so it can be shared with a team

## FAQ

<details>
<summary><b>Is it really free?</b></summary>

Yes. MyDB IDE is licensed under Apache 2.0, with no paid edition, account or trial.
</details>

<details>
<summary><b>What is it built on?</b></summary>

On the open-source IntelliJ Platform Community Edition (Apache 2.0), which provides the editor, the result grid and the window system. The database features (connections, introspection, the SQL language, editing, compare, import and export) are this project's own code. See [NOTICE](NOTICE).
</details>

<details>
<summary><b>Where are my passwords stored?</b></summary>

In the operating system's credential store: Keychain on macOS, Credential Manager on Windows, Secret Service or KeePass on Linux. Project files never contain secrets.
</details>

<details>
<summary><b>Can I use it at work?</b></summary>

Yes. The Apache 2.0 license allows commercial use. Read-only data sources and the confirmation for `DELETE`/`UPDATE` without `WHERE` help when you connect to production.
</details>

<details>
<summary><b>Will you support SQL Server, Oracle or ClickHouse?</b></summary>

They are on the [roadmap](#roadmap). Open or upvote a [feature request](https://github.com/taeyun1215/mydb-ide/issues/new?template=feature_request.yml) to help decide the order.
</details>

<details>
<summary><b>Why does macOS or Windows warn me when I open it?</b></summary>

The builds are not code-signed yet. See [Download](#download) for how to open them. Signing is on the roadmap.
</details>

## Building from source

Requirements: macOS, Linux or Windows; JDK 21+; Git; Docker for the tests; about 30 GB of free disk space for a full build.

```sh
git clone https://github.com/taeyun1215/mydb-ide.git
cd mydb-ide

# the IDE platform source tree, at the version this release is built on
git clone --branch idea/262.10968.63 --depth 1 https://github.com/JetBrains/intellij-community.git

# link src/product into it, apply src/product/patches, regenerate the build files
src/product/setup-community.sh

# build installers for every OS and architecture
cd intellij-community
./build/run_build_target.sh "$(pwd)" //mydb/build:i_build_target
```

The first build downloads its toolchain and takes 20–40 minutes; later builds take about 10. Installers land in `intellij-community/out/mydb/artifacts/`.

Try a build without touching your normal settings:

```sh
tools/mydb-run.sh extract   # copy the app out of the latest DMG
tools/mydb-run.sh start     # run it with its own config, caches and logs
tools/mydb-run.sh stop
```

## Testing

Tests run against real servers in Docker, started by Testcontainers: PostgreSQL 17, MySQL 8.4, MariaDB 11.4 and an sshd.

```sh
src/scripts-test.sh                               # database layer: 116 tests
src/plugin-test.sh                                # IDE features: 154 tests
src/plugin-test.sh 'dev.teo.mydb.plugin.sql.*'    # a subset
```

`testdb/seed` holds the seed schemas (`shop`, `shop2`) and `testdb/expected` the expected outputs. [CI](.github/workflows/ci.yml) runs both suites on every push and pull request.

## Architecture

```
src/product/
├── mydb.core/            Database layer, no UI
│   ├── connection/       JDBC connections, SSH tunnels, SSL, sessions
│   ├── introspect/       PostgreSQL, MySQL, SQLite and DDL-file introspectors → DbModel
│   ├── exec/             statement splitting, execution, paging, transactions, dialects
│   ├── edit/             pending grid changes → DML, transactional submit
│   ├── ddl/              DDL generation per dialect
│   ├── diff/             schema compare and migration scripts
│   ├── plan/             execution plans
│   └── transfer/         import, export, dump tools, charts, data compare
├── mydb.plugin/          IDE features
│   ├── explorer/         database tree, actions, virtual objects
│   ├── sql/              SQL language: lexer, parser, PSI, resolve, completion, inspections, formatter
│   ├── console/          consoles, run targets, results, history, run configurations
│   ├── grid/  table/     result grid and table editor
│   ├── objects/          create / modify dialogs
│   ├── compare/ diagram/ schema compare UI, ER and plan diagrams
│   └── datasource/       data source dialogs, SSH configurations, import
├── mydb.resources/       product branding and customization
├── build/                product build definition and bundled license files
└── patches/              changes to the platform source tree
```

The database layer is plain Kotlin on JDBC and is tested on its own (`src/core`). The IDE layer uses the platform's editor, PSI, grid and diagram infrastructure (`src/plugin`).

## Roadmap

- [ ] Signed and notarized macOS builds, signed Windows installers
- [ ] Schema compare for MySQL and SQLite
- [ ] SQL Server, Oracle and ClickHouse
- [ ] Korean UI
- [ ] Release builds from CI

Have an idea? Open a [discussion](https://github.com/taeyun1215/mydb-ide/discussions) or a [feature request](https://github.com/taeyun1215/mydb-ide/issues/new?template=feature_request.yml).

## Contributing

Contributions are welcome. Start with [CONTRIBUTING.md](CONTRIBUTING.md), and please follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Security

Please report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## License

MyDB IDE is licensed under the [Apache License 2.0](LICENSE).

It is built on the IntelliJ Platform Community Edition (Apache License 2.0). Third-party libraries are listed in [NOTICE](NOTICE) and in the `license/` directory of every distribution. MyDB IDE is an independent project and is not affiliated with or endorsed by JetBrains.
