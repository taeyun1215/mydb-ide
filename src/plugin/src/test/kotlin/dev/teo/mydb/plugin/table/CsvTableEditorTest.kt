package dev.teo.mydb.plugin.table

import com.intellij.database.datagrid.GridRequestSource
import com.intellij.database.datagrid.ModelIndexSet
import com.intellij.database.run.ui.DataAccessType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import dev.teo.mydb.core.edit.SubmitResult
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.grid.JdbcGridHookUp

/** CSV files edited as a table. */
class CsvTableEditorTest : MyDbTestCase() {
    fun `test csv rows are edited, added and deleted through the grid and written back`() {
        val f = myFixture.addFileToProject("people.csv", "name,city\nAnn,\"Oslo, NO\"\nBob,Rome\n").virtualFile
        assertTrue(CsvTableEditorProvider().accept(project, f))
        val editor = CsvTableEditor(project, f)
        try {
            val grid = editor.grid!!
            assertEquals(listOf("name", "city"), editor.columns)
            val m = grid.getDataModel(DataAccessType.DATABASE_DATA)
            assertEquals(2, m.rowCount)
            assertFalse("order column hidden", grid.isColumnEnabled(com.intellij.database.datagrid.ModelIndex.forColumn(m, 0)))
            val hookUp = grid.dataHookup as JdbcGridHookUp
            val mutator = hookUp.mutator as JdbcGridHookUp.Mutator
            mutator.mutate(GridRequestSource(null), ModelIndexSet.forRows(m, 1), ModelIndexSet.forColumns(m, 2), "Paris", false)
            mutator.insertRows(GridRequestSource(null), 1)
            mutator.mutate(GridRequestSource(null), ModelIndexSet.forRows(m, 2), ModelIndexSet.forColumns(m, 1), "Cy", false)
            mutator.deleteRows(GridRequestSource(null), ModelIndexSet.forRows(m, 0))
            mutator.submit(GridRequestSource(null), true)
            waitFor("submit") { hookUp.lastSubmit != null }
            assertTrue(hookUp.lastSubmit.toString(), hookUp.lastSubmit is SubmitResult.Ok)
            assertEquals("name,city\nBob,Paris\nCy,\n", FileDocumentManager.getInstance().getDocument(f)!!.text)
        } finally { Disposer.dispose(editor) }
    }
}
