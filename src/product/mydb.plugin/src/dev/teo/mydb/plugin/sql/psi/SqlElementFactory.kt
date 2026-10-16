package dev.teo.mydb.plugin.sql.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import dev.teo.mydb.core.ddl.PgDdlGenerator
import dev.teo.mydb.plugin.sql.SqlFileType
import dev.teo.mydb.plugin.sql.SqlFile
import dev.teo.mydb.plugin.sql.SqlTokenTypes

object SqlElementFactory {
    fun file(project: Project, text: String): SqlFile =
        PsiFileFactory.getInstance(project).createFileFromText("dummy.sql", SqlFileType, text) as SqlFile

    /** Identifier token for [name], quoted when needed. */
    fun identifier(project: Project, name: String): ASTNode {
        val f = file(project, "select ${PgDdlGenerator.quote(name)}")
        var leaf = f.node.findLeafElementAt(7)!!
        while (leaf.elementType !in SqlTokenTypes.IDENTIFIERS && leaf.elementType !in SqlTokenTypes.ALL_KEYWORDS) leaf = leaf.treeNext
        return leaf
    }
}
