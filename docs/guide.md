# MyDB IDE User Guide

**English** · [한국어](guide.ko.md)

This guide walks through the everyday tasks in MyDB IDE. Shortcuts are shown for macOS; on Windows and Linux use <kbd>Ctrl</kbd> instead of <kbd>⌘</kbd> (see the [shortcut table](../README.md#keyboard-shortcuts)).

- [1. Projects and the window](#1-projects-and-the-window)
- [2. Connecting to a database](#2-connecting-to-a-database)
- [3. SSH tunnels and SSL](#3-ssh-tunnels-and-ssl)
- [4. Writing and running SQL](#4-writing-and-running-sql)
- [5. Transactions](#5-transactions)
- [6. Viewing and editing data](#6-viewing-and-editing-data)
- [7. Creating and changing objects](#7-creating-and-changing-objects)
- [8. Comparing schemas](#8-comparing-schemas)
- [9. Diagrams and execution plans](#9-diagrams-and-execution-plans)
- [10. Import and export](#10-import-and-export)
- [11. Virtual foreign keys](#11-virtual-foreign-keys)
- [12. Bringing data sources from another IDE](#12-bringing-data-sources-from-another-ide)
- [13. Settings](#13-settings)
- [14. Troubleshooting](#14-troubleshooting)

---

## 1. Projects and the window

MyDB IDE works with **projects**. A project is a folder that keeps your data sources, consoles and SQL files together. Create one with **File → New Project…**, or open any folder with **File → Open…**.

The main window has three parts:

| Area | What it shows |
|---|---|
| **MyDB Explorer** (left) | Data sources and their databases, schemas, tables, views and other objects |
| **Editor** (center) | Query consoles, SQL files, table data, diagrams |
| **MyDB Services** (bottom) | Query results, the output log, query plans and open sessions |

Data source settings are stored in `.idea/mydbDataSources.xml` inside the project, **without passwords**. You can commit that file to share connection settings with a team; each person enters their own password.

## 2. Connecting to a database

1. Press <kbd>⌘</kbd> <kbd>;</kbd> (or click **+** in the explorer toolbar).
2. Click **+** and choose **PostgreSQL**, **MySQL**, **MariaDB**, **SQLite** or **DDL Files**.
3. On the **General** tab, fill in:
   - **Host**, **Port**, **Database**, **User**, **Password** (for SQLite: the database file)
   - The **URL** is built from these fields. Editing the URL directly overrides the fields.
   - **Name** follows the host and database until you change it yourself.
4. Click **Test Connection**. On success it shows the server version.
5. On the **Schemas** tab, check the schemas you want to load. Unchecked schemas are not read, which keeps large servers fast.
6. Click **OK**. A query console opens for the new data source.

Useful options on the other tabs:

| Tab | Option | Use it for |
|---|---|---|
| **Options** | **Read-only** | Production databases: blocks statements that change data |
| **Options** | **Transaction control** | Start in **Auto** or **Manual** commit mode |
| **Options** | **Synchronize metadata automatically** | Refresh the model after DDL you run in a console |
| **Advanced** | Driver properties | JDBC properties such as timeouts or `ApplicationName` |

In the explorer, the badge next to a database (`3/12`) means 3 of 12 schemas are loaded. Right-click → **Schemas…** to change the selection, and **Refresh** to re-read changed objects.

Right-click a data source → **Color…** to give it a color. The color also marks its editor tabs, which helps tell production from staging.

## 3. SSH tunnels and SSL

### SSH tunnel

1. Open the data source (<kbd>⌘</kbd> <kbd>;</kbd>) and go to **SSH/SSL**.
2. Check **Use SSH tunnel**.
3. Choose an existing **SSH configuration** or create one with:
   - **Host**, **Port**, **User**
   - **Authentication**: **Password**, or **Key pair** with a **Private key** file and its passphrase
4. **Local port** can stay empty; a free port is picked automatically.
5. Click **Test Connection**.

SSH configurations are shared between data sources and can be managed in **Settings → Tools → SSH Configurations**. The first connection to a host remembers its host key; if the key later changes, the connection is refused.

### SSL

1. On **SSH/SSL**, check **Use SSL**.
2. Pick a **Mode**: `require` (encrypted), `verify-ca` (also checks the certificate authority) or `verify-full` (also checks the host name).
3. For `verify-ca` and `verify-full`, set the **CA file** or a **Trust store**.
4. If the server asks for client certificates, set **Client certificate**, **Client key** and **Key password**.

Passwords and passphrases go to the system credential store (Keychain on macOS).

## 4. Writing and running SQL

Open a console with **Query Console** in the explorer toolbar, or <kbd>⇧</kbd> <kbd>⌘</kbd> <kbd>L</kbd> for a new one. The console runs against the data source shown in its toolbar.

**Running**

| What | How |
|---|---|
| Statement at the caret | <kbd>⌘</kbd> <kbd>↵</kbd>. If the caret is between statements, a popup asks which one to run. |
| Selection | Select text, then <kbd>⌘</kbd> <kbd>↵</kbd> |
| Whole script | Select all, then <kbd>⌘</kbd> <kbd>↵</kbd>. Each result set gets its own tab. |
| Cancel | Click the progress indicator at the bottom of the window and cancel |

Results appear in **MyDB Services** at the bottom. Large results are paged (the page size is in settings). Previous statements are in **Query History** on the console toolbar.

**Code assistance**

- **Completion** (<kbd>⌃</kbd> <kbd>Space</kbd>): schemas, tables, columns, aliases, CTEs and keywords. After `JOIN … ON`, conditions based on foreign keys come first.
- **Errors and warnings**: unknown tables and columns are underlined. <kbd>⌥</kbd> <kbd>↵</kbd> shows quick fixes and intentions such as qualifying a name, flipping join sides or wrapping a query in a subquery.
- **Safety**: running `DELETE` or `UPDATE` without `WHERE` asks for confirmation.
- **Navigation**: <kbd>⌘</kbd> <kbd>B</kbd> on a table name jumps to it in the explorer; <kbd>F1</kbd> shows its DDL and comments; <kbd>⌥</kbd> <kbd>F7</kbd> finds its usages.
- **Formatting**: **Code → Reformat Code**.
- **Rename**: <kbd>⇧</kbd> <kbd>F6</kbd> on a table, column or alias. Renaming a database object shows the DDL before it runs.

**SQL files**: `.sql` files in the project run like consoles. Choose which data source each folder's files use in **Settings → Database → SQL Resolution Scopes**. To run a script file regularly, create a **SQL Script** run configuration (**Run → Edit Configurations…**).

## 5. Transactions

The **Tx** button on the console toolbar shows the current mode.

- **Auto**: each statement is committed when it completes.
- **Manual**: statements run in one transaction until you press **Commit** or **Rollback** (both on the toolbar). Other sessions do not see the changes until you commit.

The same menu sets the **isolation level** (Read Committed, Repeatable Read, Serializable) for the next transaction. Open transactions can also be committed or rolled back from the session list in **MyDB Services**.

The console, the table editor and the explorer use separate sessions, so a long query in a console does not block browsing or editing a table.

## 6. Viewing and editing data

Select a table and press <kbd>⌘</kbd> <kbd>↓</kbd> (or double-click it) to open its data. Query results from a single table are editable in the same way.

**Filtering and sorting**

- Type a condition in **WHERE** (for example `status = 'paid' and total > 100`) and press <kbd>↵</kbd>. Column names complete as you type.
- Type in **ORDER BY** (for example `created_at desc`) and press <kbd>↵</kbd>.
- Click a column header to sort the loaded rows locally, without a new query.
- Right-click a value for a local filter on that value.

**Editing**

1. Double-click a cell (or press <kbd>F2</kbd>) and type a new value.
2. Add rows with **+** and delete with **−**. New rows show their default values.
3. Changed cells are highlighted. **Nothing has been sent to the server yet.**
4. **Preview Pending Changes** shows the exact `INSERT`, `UPDATE` and `DELETE` statements.
5. **Submit** (<kbd>⌘</kbd> <kbd>↵</kbd>) runs them in one transaction. If one fails, everything is rolled back, the failing row is marked in red with the server message, and your edits stay so you can fix them.
6. **Revert** discards all pending changes.

**More grid tools** (results toolbar or right-click):

- **Value panel** (<kbd>⇧</kbd> <kbd>↵</kbd> on a cell) for long text and JSON
- **Transpose** to show one row as a column
- **Count rows** for the exact total
- **Show Chart** to plot result columns
- **Compare Data With…** to diff two result sets
- **Show DDL** of the table behind the result

## 7. Creating and changing objects

**Create**: right-click a schema → **New…** (<kbd>⌘</kbd> <kbd>N</kbd>) → **Table**, **View**, **Materialized View**, **Sequence** or **Schema**.

**Modify a table**: right-click → **Modify Table…** (<kbd>⌘</kbd> <kbd>F6</kbd>).

The table dialog has a tree of **Columns**, **Keys**, **Foreign Keys**, **Indexes** and **Checks**:

- **+** adds an item to the selected folder; select an item to edit it on the right.
- Columns: name, **Data type**, **Default**, **Not null**, **Identity (serial)**, **Generated** expression, **Comment**.
- Foreign keys: **Target table**, then pairs of local and target columns.
- The **DDL preview** at the bottom updates as you type. **Problems Found** lists anything that would fail.
- **OK** runs the DDL; you can also copy it and run it yourself.

Other object actions (right-click):

- **SQL Scripts → SQL Generator…** (<kbd>⌥</kbd> <kbd>⌘</kbd> <kbd>G</kbd>): DDL of the selected objects
- **SQL Scripts → SELECT / INSERT / UPDATE / DELETE Statement**: templates in a console
- **Object Actions → Truncate…**, **Delete…**: with confirmation
- **Go to DDL** (<kbd>⌘</kbd> <kbd>B</kbd>), **Copy Reference**, **Quick Documentation** (<kbd>F1</kbd>)

## 8. Comparing schemas

1. In the explorer, select two schemas (<kbd>⌘</kbd>-click), or two data sources.
2. Press <kbd>⌘</kbd> <kbd>D</kbd> (**Compare Structure…**).
3. The tree shows each object as added, removed, changed or identical. **Show Identical Items** toggles identical ones.
4. Select an object to see the **DDL Diff** side by side, or **Object Properties Diff** for the details.
5. Check or uncheck objects to include them. **Swap Source and Target** reverses the direction.
6. **Script Preview** shows the migration script. Use **Open in New Query Console** to review and run it yourself, or **Execute** to run it directly.

To compare **data** rather than structure, right-click a table → **Compare Data**, or use **Compare Data With…** on a result grid.

## 9. Diagrams and execution plans

### ER diagrams

Select a schema or some tables and press <kbd>⌥</kbd> <kbd>⇧</kbd> <kbd>⌘</kbd> <kbd>U</kbd> (**Diagrams → Show Diagram**). <kbd>⌥</kbd> <kbd>⌘</kbd> <kbd>U</kbd> opens a quick popup instead.

- Solid lines are foreign keys; dashed lines are view dependencies and virtual foreign keys.
- **View Options**: **All Columns**, **Key Columns Only**, **No Columns**, **Column Types**, **View Dependencies**.
- **Re-layout**, **Zoom In/Out**, **Fit Content**, **Actual Size**.
- **Export Diagram**: **PNG Image**, **SVG**, **Mermaid**, **PlantUML** or **Graphviz**. **Copy Mermaid** puts Mermaid text on the clipboard for docs and pull requests.

### Execution plans

1. Put the caret in a query.
2. Click **Explain Plan** on the console toolbar and choose **Explain Plan** or **Explain Analyze**. Explain Analyze actually runs the statement; data-changing statements are rolled back afterwards.
3. The **Query Plan** tab shows the plan as a tree. Select a node to see all its properties (rows, cost, timing, buffers).
4. Switch to the diagram view to see the plan as a graph.

## 10. Import and export

### Export

- **A query result**: on the results toolbar, **Export Data…**
- **A whole table**: right-click → **Import/Export → Export Data to File…**

Choose the **Extractor** (CSV, TSV, JSON, SQL Inserts, Markdown, HTML, Excel and others), options such as **Add column header** or **Transpose**, then **Export to File** or **Copy to Clipboard**. CSV details (separator, quoting, null text) are in **Settings → Database → CSV Formats**.

### Import a CSV or TSV file

1. Right-click a schema or table → **Import/Export → Import Data from File…**
2. Pick the file. **Data Preview** shows how it is parsed.
3. Choose an existing **Table** or type a new name. For a new table, adjust column names and types; **DDL Preview** shows the `CREATE TABLE`.
4. Options: **Insert values that cannot be converted as NULL**, **Write errors to file**.
5. Click **Import**.

### Dump and restore tools

| Database | Menu | Tool |
|---|---|---|
| PostgreSQL | **Import/Export → Export with pg_dump…** | `pg_dump` (plain SQL, custom, directory or tar) |
| PostgreSQL | **Import/Export → Restore with pg_restore or psql…** | `pg_restore` or `psql` |
| MySQL / MariaDB | **Export with mysqldump…** / **Restore with mysql…** | `mysqldump` / `mysql` |

The tools must be installed; set their path in the dialog the first time. The password is passed through the environment, not the command line. Output appears in the **Output** tab.

### DDL data sources

**Import/Export → Dump to DDL Data Source…** writes each object's DDL into `.sql` files and adds a **DDL Files** data source that reads them. It is useful for keeping a schema in Git and comparing it with a live database.

### CSV files as tables

Opening a `.csv` or `.tsv` file shows a **Data** tab next to the text. Edit it like a table; submitting writes the file back.

## 11. Virtual foreign keys

Many databases have relations that are not declared as foreign keys. Declare them in MyDB IDE so completion, join suggestions and diagrams know about them:

1. Right-click a table → **Object Actions → Add Virtual Foreign Key…**
2. Choose the target table and the column pairs.
3. They show in the explorer like real foreign keys, and as dashed lines in diagrams.

Virtual foreign keys are stored in `.idea/mydbVirtualObjects.xml` and never change the database. Remove one with **Remove Virtual Foreign Key**.

## 12. Bringing data sources from another IDE

If another IDE project already has your data sources (`.idea/dataSources.xml`):

1. Click **Import Data Sources from IDE Project…** in the explorer toolbar.
2. Choose the project folder. Projects found on this computer are suggested.
3. Check the data sources to import. SSH settings come along.
4. Optionally copy the saved passwords. Your system may ask you to allow access to the keychain.

Data sources whose driver is not supported are listed but cannot be imported.

## 13. Settings

Open **Settings** with <kbd>⌘</kbd> <kbd>,</kbd>. Database settings are under **Database**:

| Page | Main options |
|---|---|
| **Query Execution** | What to run when the caret is inside a statement; open results in a new tab; show execution time; ask before `DELETE`/`UPDATE` without `WHERE` |
| **Data Editor and Viewer** | Result page size; what double-clicking a table opens |
| **CSV Formats** | Separators, quoting and header rows for CSV/TSV |
| **Database Explorer** | Show column types, key columns and object counts |
| **Query Files and Consoles** | Open a console after creating a data source |
| **SQL Resolution Scopes** | Which data source each folder's SQL files resolve against (per project) |
| **Other** | Refresh the object after running DDL from the create and modify dialogs |

SSH configurations are under **Tools → SSH Configurations**.

## 14. Troubleshooting

**Connection refused / timed out**
Check host and port, that the server accepts remote connections, and any firewall or VPN. For databases behind a bastion host, use an [SSH tunnel](#3-ssh-tunnels-and-ssl).

**SSL errors**
`verify-ca` and `verify-full` need the server's CA certificate in **CA file** or a trust store. To test quickly, try `require`, which encrypts without verifying.

**A table or column is underlined as unknown, but it exists**
Its schema may not be loaded: right-click the database → **Schemas…**. If it was just created elsewhere, **Refresh**.

**The app does not open on macOS or Windows**
The builds are not code-signed yet. See [Download](../README.md#download).

**Where are the logs?**
**Help → Show Log in Finder** (Explorer on Windows). Include the relevant part when you [report a bug](https://github.com/taeyun1215/mydb-ide/issues/new?template=bug_report.yml), after removing host names and user names.
