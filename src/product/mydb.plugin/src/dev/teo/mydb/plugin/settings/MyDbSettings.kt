package dev.teo.mydb.plugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import dev.teo.mydb.core.exec.CaretInsidePolicy
import javax.swing.JComponent

/** IDE-wide database settings. Only settings that change behaviour are offered. */
@Service(Service.Level.APP)
@State(name = "MyDbSettings", storages = [Storage("mydb.xml")])
class MyDbSettings : PersistentStateComponent<MyDbSettings.Values> {
    class Values {
        /** What ⌘↩ runs when the caret is inside a statement of a script. */
        var caretInside: CaretInsidePolicy = CaretInsidePolicy.ASK
        /** Keep earlier result tabs and add new ones instead of replacing them. */
        var resultsInNewTab: Boolean = false
        /** Rows fetched per page in result grids and table editors. */
        var pageSize: Int = 500
        /** Column header line on by default when exporting delimited formats. */
        var csvHeaderByDefault: Boolean = false
        /** Execution time next to each executed statement in the console. */
        var showExecutionTime: Boolean = true
        /** Ask before running DELETE / UPDATE without WHERE. */
        var confirmUnsafeStatements: Boolean = true
        /** Gray details in the explorer: column types, key columns, folder counts. */
        var explorerShowDetails: Boolean = true
        /** What double-clicking a table in the explorer opens. */
        var explorerDoubleClick: DoubleClickTarget = DoubleClickTarget.DATA
        /** Open the data source's console after creating it. */
        var openConsoleOnCreate: Boolean = true
        /** Re-read the edited object after DDL from the object dialogs. */
        var refreshAfterDdl: Boolean = true
        /** Paths of the PostgreSQL client tools last used for dump and restore. */
        var pgDumpPath: String = ""
        var pgRestorePath: String = ""
        var psqlPath: String = ""
        var mysqldumpPath: String = ""
        var mysqlPath: String = ""
    }

    enum class DoubleClickTarget { DATA, DDL }

    private var values = Values()
    override fun getState() = values
    override fun loadState(state: Values) { values = state }

    companion object { fun get(): Values = service<MyDbSettings>().state }
}

/** "Database" settings group; its pages are registered as children. */
class DatabaseConfigurable : SearchableConfigurable {
    override fun getId() = "mydb.database"
    override fun getDisplayName() = "Database"
    override fun createComponent(): JComponent = panel {
        row { comment("Settings of database tools. Choose a page on the left.") }
    }
    override fun isModified() = false
    override fun apply() {}
}

class QueryExecutionConfigurable : BoundSearchableConfigurable("Query Execution", "mydb.database.execution", "mydb.database.execution") {
    override fun createPanel(): DialogPanel {
        val s = MyDbSettings.get()
        return panel {
            row("When the caret is inside a statement:") {
                comboBox(CaretInsidePolicy.entries, com.intellij.ui.SimpleListCellRenderer.create("") {
                    when (it) {
                        CaretInsidePolicy.ASK -> "Ask what to execute"
                        CaretInsidePolicy.SMALLEST -> "Smallest statement"
                        CaretInsidePolicy.LARGEST -> "Largest statement"
                    }
                }).bindItem(s::caretInside.toNullableProperty())
            }
            row { checkBox("Open results in a new tab").bindSelected(s::resultsInNewTab) }
            row { checkBox("Show execution time next to executed statements").bindSelected(s::showExecutionTime) }
            row { checkBox("Ask before running DELETE or UPDATE without WHERE").bindSelected(s::confirmUnsafeStatements) }
        }
    }
}

class ExplorerConfigurable : BoundSearchableConfigurable("Database Explorer", "mydb.database.explorer", "mydb.database.explorer") {
    override fun createPanel(): DialogPanel {
        val s = MyDbSettings.get()
        return panel {
            row { checkBox("Show details (column types, key columns, object counts)").bindSelected(s::explorerShowDetails) }
            row("Double-click on a table opens:") {
                comboBox(MyDbSettings.DoubleClickTarget.entries, com.intellij.ui.SimpleListCellRenderer.create("") {
                    if (it == MyDbSettings.DoubleClickTarget.DATA) "Table data" else "DDL"
                }).bindItem(s::explorerDoubleClick.toNullableProperty())
            }
        }
    }
}

class ConsolesConfigurable : BoundSearchableConfigurable("Query Files and Consoles", "mydb.database.consoles", "mydb.database.consoles") {
    override fun createPanel(): DialogPanel {
        val s = MyDbSettings.get()
        return panel {
            row { checkBox("Open a query console after creating a data source").bindSelected(s::openConsoleOnCreate) }
        }
    }
}

class OtherConfigurable : BoundSearchableConfigurable("Other", "mydb.database.other", "mydb.database.other") {
    override fun createPanel(): DialogPanel {
        val s = MyDbSettings.get()
        return panel {
            row { checkBox("Refresh the object after running DDL from create and modify dialogs").bindSelected(s::refreshAfterDdl) }
        }
    }
}

class DataEditorConfigurable : BoundSearchableConfigurable("Data Editor and Viewer", "mydb.database.editor", "mydb.database.editor") {
    override fun createPanel(): DialogPanel {
        val s = MyDbSettings.get()
        return panel {
            row("Result set page size:") { intTextField(1..1_000_000).bindIntText(s::pageSize) }
        }
    }
}

class CsvFormatsConfigurable : BoundSearchableConfigurable("CSV Formats", "mydb.database.csv", "mydb.database.csv") {
    override fun createPanel(): DialogPanel {
        val s = MyDbSettings.get()
        return panel {
            row { checkBox("Add column header when exporting").bindSelected(s::csvHeaderByDefault) }
        }
    }
}

private fun <T : Any> kotlin.reflect.KMutableProperty0<T>.toNullableProperty(): com.intellij.ui.dsl.builder.MutableProperty<T?> =
    com.intellij.ui.dsl.builder.MutableProperty({ get() }, { v -> if (v != null) set(v) })
