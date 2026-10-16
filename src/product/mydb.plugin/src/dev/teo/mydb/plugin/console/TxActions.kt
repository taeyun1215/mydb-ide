package dev.teo.mydb.plugin.console

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import dev.teo.mydb.core.datasource.IsolationLevel
import dev.teo.mydb.core.datasource.TxMode
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding
import javax.swing.JComponent

/**
 * Console toolbar "Tx" dropdown: transaction mode, isolation level with the current
 * value checked, and a line describing the current mode.
 */
class TxModeComboAction : ComboBoxAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val tx = e.consoleTx()
        e.presentation.isEnabledAndVisible = tx != null
        e.presentation.text = "Tx: " + when (tx?.mode) { TxMode.MANUAL -> "Manual"; else -> "Auto" }
    }

    override fun shouldShowDisabledActions() = true

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup = popupGroup()

    companion object {
        fun modeText(mode: TxMode) = if (mode == TxMode.AUTO) "Auto" else "Manual"
        fun isolationText(level: IsolationLevel) = when (level) {
            IsolationLevel.DATABASE_DEFAULT -> "Database Default"
            IsolationLevel.READ_COMMITTED -> "Read Committed"
            IsolationLevel.REPEATABLE_READ -> "Repeatable Read"
            IsolationLevel.SERIALIZABLE -> "Serializable"
        }
        fun description(mode: TxMode) = when (mode) {
            TxMode.AUTO -> "Each statement is committed when it completes"
            TxMode.MANUAL -> "Statements run in one transaction until Commit or Rollback"
        }

        fun popupGroup(): DefaultActionGroup = DefaultActionGroup().apply {
            add(Separator.create("Transaction Mode"))
            TxMode.entries.forEach { add(ModeToggle(it)) }
            add(Separator.create("Transaction Isolation"))
            IsolationLevel.entries.forEach { add(IsolationToggle(it)) }
            add(Separator.create())
            add(Description())
        }
    }

    private abstract class TxToggle(text: String) : DumbAwareToggleAction(text) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        abstract fun selected(tx: TxSettings): Boolean
        abstract fun applied(tx: TxSettings): TxSettings
        override fun isSelected(e: AnActionEvent) = e.consoleTx()?.let(::selected) == true
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (!state) return
            val project = e.project ?: return
            val (key, config) = e.txTarget() ?: return
            val consoles = ConsoleService.getInstance(project)
            val next = applied(consoles.txSettings(key, config))
            // switching a live session may commit (manual -> auto); keep JDBC off the EDT
            ApplicationManager.getApplication().executeOnPooledThread { consoles.setTxSettings(key, config, next) }
        }
    }

    private class ModeToggle(val mode: TxMode) : TxToggle(modeText(mode)) {
        override fun selected(tx: TxSettings) = tx.mode == mode
        override fun applied(tx: TxSettings) = tx.copy(mode = mode)
    }

    private class IsolationToggle(val level: IsolationLevel) : TxToggle(isolationText(level)) {
        override fun selected(tx: TxSettings) = tx.isolation == level
        override fun applied(tx: TxSettings) = tx.copy(isolation = level)
    }

    private class Description : DumbAwareAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            e.presentation.text = e.consoleTx()?.mode?.let(::description) ?: ""
            e.presentation.isEnabled = false
        }
        override fun actionPerformed(e: AnActionEvent) {}
    }
}
