# Changelog

All notable changes to MyDB IDE are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [2026.2.3] - 2026-10-16

First public release.

### Added

- **Databases**: PostgreSQL, MySQL, MariaDB and SQLite, plus DDL data sources read from `.sql` files
- **Explorer**: schemas, tables, views, materialized views, routines, sequences, columns, keys and indexes; schema selection with `shown/total` badges; incremental refresh; quick documentation; Go to DDL; Copy Reference; filter; data source colors; Go to Symbol; virtual foreign keys
- **SQL consoles**: completion with foreign-key join conditions, resolution, inspections, quick fixes, intentions, formatter, rename with preview, find usages, query history, manual transactions and isolation levels, SQL Script run configurations
- **Result grid and table editor**: paging, sorting, local filters, `WHERE` / `ORDER BY` filters with column completion, pending edits with DML preview and transactional submit, value panel, transpose, row count, charts, data compare
- **Schema objects**: create and modify dialogs for tables, views, materialized views, sequences and schemas with a live DDL preview; SQL generator
- **Compare, diagrams, plans**: schema compare with DDL and property diffs and a migration script; ER diagrams with export to PNG, SVG, Mermaid, PlantUML and GraphML; execution plans as a tree and a diagram
- **Import and export**: CSV, TSV, JSON, SQL inserts, Markdown, HTML and Excel; CSV import; `pg_dump`, `pg_restore`, `psql`, `mysqldump` and `mysql`; CSV files as an editable table
- **Connectivity**: SSH tunnels with password or key, shared SSH configurations, SSL up to `verify-full` with client certificates, driver properties, read-only data sources, import of data sources from another IDE project
- **Privacy**: no telemetry, error uploads, update checks, plugin marketplace or account sync

[Unreleased]: https://github.com/taeyun1215/mydb-ide/compare/v2026.2.3...HEAD
[2026.2.3]: https://github.com/taeyun1215/mydb-ide/releases/tag/v2026.2.3
