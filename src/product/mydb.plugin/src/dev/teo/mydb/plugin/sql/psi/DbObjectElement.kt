package dev.teo.mydb.plugin.sql.psi

import com.intellij.icons.AllIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import dev.teo.mydb.core.model.DbColumn
import dev.teo.mydb.core.model.DbTable
import javax.swing.Icon

/**
 * A database object as a PSI element, so references can resolve to it and the platform's
 * navigation, documentation and rename work.
 */
class DbObjectElement(
    private val myProject: Project,
    val dataSourceId: String,
    val database: String,
    val schema: String,
    val table: DbTable,
    val column: DbColumn? = null,
) : FakePsiElement() {
    val kind: String get() = when { column != null -> "column"; table.isView -> "view"; else -> "table" }

    override fun getName(): String = column?.name ?: table.name
    /** SQL, so the SQL documentation provider answers for it outside an editor too (explorer F1). */
    override fun getLanguage(): com.intellij.lang.Language = dev.teo.mydb.plugin.sql.SqlLanguage
    override fun getParent(): PsiElement? = null
    override fun getProject() = myProject
    override fun getManager(): PsiManager = PsiManager.getInstance(myProject)
    override fun isValid() = true
    override fun getContainingFile() = null
    override fun getIcon(open: Boolean): Icon = when {
        column != null && table.primaryKey?.columns?.contains(column.name) == true -> AllIcons.Nodes.DataColumn
        column != null -> AllIcons.Nodes.DataColumn
        table.isView -> AllIcons.Nodes.DataSchema
        else -> AllIcons.Nodes.DataTables
    }
    override fun getPresentableText() = name
    override fun getLocationString() = "$database.$schema" + if (column != null) ".${table.name}" else ""
    override fun getPresentation(): ItemPresentation = this

    override fun canNavigate() = true
    override fun navigate(requestFocus: Boolean) = DbNavigation.openDdl(myProject, this)

    override fun equals(other: Any?) = other is DbObjectElement && other.dataSourceId == dataSourceId &&
        other.database == database && other.schema == schema && other.table.name == table.name && other.column?.name == column?.name
    override fun hashCode() = listOf(dataSourceId, database, schema, table.name, column?.name).hashCode()
    override fun toString() = "DbObject($kind ${schema}.${table.name}${column?.let { ".${it.name}" } ?: ""})"
}

/** ⌘B on a database object opens its generated DDL. */
object DbNavigation {
    fun openDdl(project: Project, o: DbObjectElement) {
        val config = dev.teo.mydb.plugin.datasource.DataSourceStorage.getInstance(project).find(o.dataSourceId) ?: return
        dev.teo.mydb.plugin.explorer.ExplorerActions.openDdl(project, config, o.database, o.schema, o.table)
    }
}
