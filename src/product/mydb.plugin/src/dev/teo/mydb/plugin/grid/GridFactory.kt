package dev.teo.mydb.plugin.grid

import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.GridHelper
import com.intellij.database.datagrid.GridHelperImpl
import com.intellij.database.datagrid.GridHelperPropertyProvider
import com.intellij.database.datagrid.GridUtil
import com.intellij.database.datagrid.color.GridColorModelImpl
import com.intellij.ide.util.PropertiesComponent
import com.intellij.database.run.ui.TableResultPanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.teo.mydb.core.exec.ResultPage
import dev.teo.mydb.core.exec.Session

/** Creates a community data grid bound to a session. */
object GridFactory {
    fun create(project: Project, session: Session, page: ResultPage, query: PageQuery, edit: EditTarget?, parent: Disposable,
               counter: RowCounter? = null, log: GridLog? = null, sessionKey: String? = null): DataGrid {
        val hookUp = JdbcGridHookUp(project, session, query, edit, page, counter, log, sessionKey)
        val popup = ActionManager.getInstance().getAction("Console.TableResult.PopupGroup") as? ActionGroup ?: DefaultActionGroup()
        val grid = TableResultPanel(project, hookUp, popup) { g, appearance ->
            GridUtil.configureCsvTable(g, appearance)
            val formatter = ScaleAwareFormatter()
            g.setObjectFormatterProvider { formatter }
            GridHelper.set(g, GridHelperImpl(GridDefaults))
        }
        (grid.colorModel as? GridColorModelImpl)?.addLayer(FailedRowColorLayer { hookUp.mutator as? JdbcGridHookUp.Mutator })
        Disposer.register(parent, grid)
        return grid
    }
}

/** Default page size of result grids: 500 rows, changeable with "Set as Default". */
object GridDefaults : GridHelperPropertyProvider {
    const val PAGE_SIZE = 500
    override var defaultPageSize: Int
        get() = PropertiesComponent.getInstance().getInt("mydb.grid.default.page.size", PAGE_SIZE)
        set(v) = PropertiesComponent.getInstance().setValue("mydb.grid.default.page.size", v, PAGE_SIZE)
    override var defaultLimitPageSize: Boolean
        get() = PropertiesComponent.getInstance().getBoolean("mydb.grid.limit.page.size", true)
        set(v) = PropertiesComponent.getInstance().setValue("mydb.grid.limit.page.size", v, true)
}
