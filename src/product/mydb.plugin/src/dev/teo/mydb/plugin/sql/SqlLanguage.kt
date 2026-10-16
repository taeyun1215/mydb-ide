package dev.teo.mydb.plugin.sql

import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object SqlLanguage : Language("MyDbSql") {
    private fun readResolve(): Any = SqlLanguage
    override fun getDisplayName() = "SQL (PostgreSQL)"
    override fun isCaseSensitive() = false
}

object SqlFileType : LanguageFileType(SqlLanguage) {
    override fun getName() = "MyDB SQL"
    override fun getDescription() = "SQL file"
    override fun getDefaultExtension() = "sql"
    override fun getIcon(): Icon = AllIcons.Nodes.DataTables
}
