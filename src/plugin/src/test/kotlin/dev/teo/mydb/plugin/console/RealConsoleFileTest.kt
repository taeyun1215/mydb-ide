package dev.teo.mydb.plugin.console

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding
import java.nio.file.Files

/** Consoles are real files outside the project (config dir); editing and completion must work there. */
class RealConsoleFileTest : MyDbTestCase() {
    fun `test completion in an on-disk console outside the project`() {
        val dir = Files.createTempDirectory("mydb-console")
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, dir.toRealPath().toString(), dir.toString())
        val path = dir.resolve("console.sql")
        Files.writeString(path, "select o.id from shop.orders o join shop.customers c on ")
        val vf = ApplicationManager.getApplication().runWriteAction<com.intellij.openapi.vfs.VirtualFile> { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!! }
        SqlDataSourceBinding.getInstance(project).bind(vf, dataSource.id, "shop")
        myFixture.configureFromExistingVirtualFile(vf)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.textLength)
        val items = myFixture.completeBasic()?.map { it.lookupString }
        assertNotNull("lookup shown", items)
        assertEquals("o.customer_id = c.id", items!!.first())
    }

    fun `test consoles in the config directory are writable and execute wins over split line`() {
        val consoles = com.intellij.openapi.application.PathManager.getConfigDir().resolve("mydb/consoles/db/x")
        Files.createDirectories(consoles)
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, consoles.toString())
        val path = consoles.resolve("console.sql").also { Files.writeString(it, "select 1") }
        val vf = ApplicationManager.getApplication().runWriteAction<com.intellij.openapi.vfs.VirtualFile> { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!! }
        assertTrue(ConsoleWritingAccess().isWritable(vf))

        SqlDataSourceBinding.getInstance(project).bind(vf, dataSource.id, "shop")
        myFixture.configureFromExistingVirtualFile(vf)
        val am = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        val split = am.getAction("EditorSplitLine"); val execute = am.getAction("MyDb.Execute")
        val ctx = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder().add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE, vf).add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, myFixture.editor).build()
        assertEquals(listOf(execute), ExecutePromoter().promote(listOf(split, execute), ctx))
    }

    fun `test unresolved names are highlighted in an on-disk console outside the project`() {
        val dir = Files.createTempDirectory("mydb-console")
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, dir.toRealPath().toString(), dir.toString())
        val path = dir.resolve("console.sql").also { Files.writeString(it, "select o.statsu from shop.orders o join shop.custmers c on c.id = o.customer_id") }
        val vf = ApplicationManager.getApplication().runWriteAction<com.intellij.openapi.vfs.VirtualFile> { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!! }
        SqlDataSourceBinding.getInstance(project).bind(vf, dataSource.id, "shop")
        myFixture.configureFromExistingVirtualFile(vf)
        val all = myFixture.doHighlighting()
        val errors = all.filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }.map { it.text }
        val ref = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(myFixture.file, dev.teo.mydb.plugin.sql.psi.SqlTableRef::class.java).last()
        val lm = com.intellij.codeInsight.daemon.impl.analysis.HighlightingLevelManager.getInstance(project)
        val gates = "phf=${com.intellij.codeInsight.daemon.ProblemHighlightFilter.shouldHighlightFile(myFixture.file)} hl=${lm.shouldHighlight(myFixture.file)} insp=${lm.shouldInspect(myFixture.file)} " +
            "inContent=${com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isInContent(vf)} writable=${vf.isWritable}"
        val diag = "$gates lang=${myFixture.file.language} ctx=${SqlDataSourceBinding.context(myFixture.file)?.model != null} " +
            "ref=${ref.text} resolves=${ref.references.map { it.resolve() }} infos=${all.map { it.severity.name + ":" + it.text }}"
        assertEquals(diag, setOf("statsu", "custmers"), errors.toSet())
    }

    fun `test alias rename in an on-disk console is in place, without the non-project warning`() {
        val dir = Files.createTempDirectory("mydb-console")
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, dir.toRealPath().toString(), dir.toString())
        val text = "select r.id from shop.products r where r.id > 0"
        val path = dir.resolve("console.sql").also { Files.writeString(it, text) }
        val vf = ApplicationManager.getApplication().runWriteAction<com.intellij.openapi.vfs.VirtualFile> { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!! }
        SqlDataSourceBinding.getInstance(project).bind(vf, dataSource.id, "shop")
        myFixture.configureFromExistingVirtualFile(vf)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("products r") + "products ".length)
        val ctx = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, myFixture.editor)
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, myFixture.file)
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT, com.intellij.codeInsight.TargetElementUtil.findTargetElement(myFixture.editor, com.intellij.codeInsight.TargetElementUtil.getInstance().allAccepted))
            .build()
        val handler = com.intellij.refactoring.rename.RenameHandlerRegistry.getInstance().getRenameHandler(ctx)!!
        com.intellij.testFramework.fixtures.CodeInsightTestUtil.doInlineRename(handler as com.intellij.refactoring.rename.inplace.VariableInplaceRenameHandler, "p", myFixture)
        assertEquals("select p.id from shop.products p where p.id > 0", myFixture.editor.document.text)
    }
}
