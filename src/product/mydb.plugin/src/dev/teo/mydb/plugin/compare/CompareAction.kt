package dev.teo.mydb.plugin.compare

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.explorer.ExplorerDataKeys
import dev.teo.mydb.plugin.explorer.ExplorerNode
import dev.teo.mydb.plugin.model.ModelService

/**
 * ⌘D in the explorer: two schemas or two tables are compared directly; with one
 * schema or table selected, the other side is chosen from a list of introspected ones.
 */
class CompareAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val nodes = e.getData(ExplorerDataKeys.SELECTED).orEmpty()
        // both sides of one DBMS: the script is written in that DBMS's DDL
        e.presentation.isEnabledAndVisible = nodes.map { dev.teo.mydb.core.exec.SqlDialect.of(it.config.dbms) }.distinct().size == 1 &&
            (sides(nodes) != null || nodes.singleOrNull()?.let(::side) != null)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val nodes = e.getData(ExplorerDataKeys.SELECTED).orEmpty()
        sides(nodes)?.let { (a, b) -> return open(project, a, b) }
        val one = nodes.singleOrNull()?.let(::side) ?: return
        val candidates = candidates(project, one)
        if (candidates.isEmpty()) return Messages.showInfoMessage(project, "No other introspected ${if (one.table != null) "table" else "schema"} to compare with.", "Compare")
        val step = object : BaseListPopupStep<CompareSide>("Compare ${one.title} With", candidates) {
            override fun getTextFor(value: CompareSide) = value.title
            override fun isSpeedSearchEnabled() = true
            override fun onChosen(selectedValue: CompareSide, finalChoice: Boolean): PopupStep<*>? {
                doFinalStep { open(project, one, selectedValue) }
                return FINAL_CHOICE
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).showInBestPositionFor(e.dataContext)
    }

    companion object {
        fun side(node: ExplorerNode): CompareSide? = when (node) {
            is ExplorerNode.Schema -> CompareSide(node.config, node.database, node.schema.name)
            is ExplorerNode.Table -> CompareSide(node.config, node.database, node.schema, node.table.name)
            else -> null
        }

        /** Two selected objects of the same kind, in selection order (source first). */
        fun sides(nodes: List<ExplorerNode>): Pair<CompareSide, CompareSide>? {
            if (nodes.size != 2) return null
            val a = side(nodes[0]) ?: return null
            val b = side(nodes[1]) ?: return null
            return if ((a.table == null) == (b.table == null)) a to b else null
        }

        /** Other introspected schemas (or tables) across all data sources. */
        fun candidates(project: Project, one: CompareSide): List<CompareSide> =
            DataSourceStorage.getInstance(project).all()
                .filter { dev.teo.mydb.core.exec.SqlDialect.of(it.dbms) == dev.teo.mydb.core.exec.SqlDialect.of(one.config.dbms) }
                .flatMap { config ->
                val model = ModelService.getInstance(project).model(config.id) ?: return@flatMap emptyList()
                model.databases.flatMap { db ->
                    db.schemas.flatMap { s ->
                        if (one.table == null) listOf(CompareSide(config, db.name, s.name))
                        else s.tables.filter { !it.isView }.map { CompareSide(config, db.name, s.name, it.name) }
                    }
                }
            }.filter { it != one }

        fun open(project: Project, source: CompareSide, target: CompareSide) {
            val compare = try { SchemaCompare(project, source, target) } catch (e: IllegalStateException) {
                return Messages.showErrorDialog(project, e.message, "Compare")
            }
            SchemaCompareDialog(project, compare).show()
        }
    }
}
