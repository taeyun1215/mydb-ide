package dev.teo.mydb.plugin.sql.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import dev.teo.mydb.core.model.DbColumn
import dev.teo.mydb.core.model.DbTable
import dev.teo.mydb.plugin.sql.SqlContext
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding
import dev.teo.mydb.plugin.sql.SqlElementTypes as E

/** Something rows can be selected from, as seen by column references in a query. */
sealed interface FromSource {
    /** Name used to qualify columns: the alias, else the table name. */
    val visibleName: String
    val element: PsiElement
    fun columnNames(): List<String>

    data class Table(override val visibleName: String, override val element: PsiElement, val ref: SqlTableRef, val table: DbTable?, val obj: DbObjectElement?) : FromSource {
        override fun columnNames() = table?.columns?.map { it.name } ?: emptyList()
    }
    data class Derived(override val visibleName: String, override val element: PsiElement, val columns: List<String>) : FromSource {
        override fun columnNames() = columns
    }
}

object SqlResolve {

    fun context(e: PsiElement): SqlContext? = e.containingFile?.let { SqlDataSourceBinding.context(it) }

    /** Resolves a table reference to the model: CTEs first, then the database objects. */
    fun resolveTable(ref: SqlTableRef): PsiElement? {
        val segs = ref.segments
        if (segs.isEmpty()) return null
        if (segs.size == 1) findCte(ref, segs[0].name)?.let { return it.alias ?: it }
        return resolveTableObject(ref, segs.map { it.name })
    }

    fun resolveTableObject(e: PsiElement, names: List<String>): DbObjectElement? {
        val ctx = context(e) ?: return null
        val db = ctx.db ?: return null
        val tableName = names.last()
        val schemas = when (names.size) {
            1 -> ctx.searchPath
            2 -> listOfNotNull(db.schema(names[0]))
            else -> listOfNotNull(ctx.model?.schema(names[names.size - 3], names[names.size - 2]))
        }
        for (s in schemas) s.table(tableName)?.let { return DbObjectElement(e.project, ctx.config.id, db.name, s.name, it) }
        return null
    }

    fun findCte(e: PsiElement, name: String): SqlCte? {
        var p: PsiElement? = e
        while (p != null && p !is PsiFile) {
            if (p is SqlQueryExpression || p is SqlStatement) {
                p.node.findChildByType(E.WITH_CLAUSE)?.psi?.let { with ->
                    PsiTreeUtil.getChildrenOfTypeAsList(with, SqlCte::class.java).firstOrNull { it.cteName == name }?.let { return it }
                }
            }
            p = p.parent
        }
        return null
    }

    /**
     * FROM sources visible to [e], innermost query first. A column reference inside a subquery
     * also sees the sources of enclosing queries (correlated references).
     */
    fun visibleSources(e: PsiElement): List<List<FromSource>> {
        val scopes = mutableListOf<List<FromSource>>()
        var p: PsiElement? = e.parent
        while (p != null && p !is PsiFile) {
            when {
                p is SqlQueryExpression -> scopes += sourcesOfQuery(p)
                p is SqlStatement && p.kind in setOf(E.UPDATE_STATEMENT, E.DELETE_STATEMENT, E.INSERT_STATEMENT) -> scopes += sourcesOfDml(p)
                p is SqlStatement && p.kind == E.CREATE_TABLE_STATEMENT -> scopes += sourcesOfCreate(p)
            }
            p = p.parent
        }
        return scopes
    }

    private fun sourcesOfQuery(q: SqlQueryExpression): List<FromSource> {
        val froms = q.node.getChildren(com.intellij.psi.tree.TokenSet.create(E.FROM_CLAUSE)).map { it.psi }
        return froms.flatMap { sourcesIn(it) }
    }

    private fun sourcesOfDml(s: SqlStatement): List<FromSource> {
        val direct = s.node.getChildren(com.intellij.psi.tree.TokenSet.create(E.TABLE_REF)).map { it.psi as SqlTableRef }.map(::tableSource)
        val froms = s.node.getChildren(com.intellij.psi.tree.TokenSet.create(E.FROM_CLAUSE)).flatMap { sourcesIn(it.psi) }
        return direct + froms
    }

    private fun sourcesOfCreate(s: SqlStatement): List<FromSource> {
        val def = PsiTreeUtil.getChildOfType(s, SqlTableDef::class.java) ?: return emptyList()
        val cols = PsiTreeUtil.getChildrenOfTypeAsList(s, SqlColumnDef::class.java).mapNotNull { it.columnName }
        return listOf(FromSource.Derived(def.segments.lastOrNull()?.name ?: "", def, cols))
    }

    /** Table references and subqueries of a FROM clause, including joined items. */
    private fun sourcesIn(from: PsiElement): List<FromSource> {
        val result = mutableListOf<FromSource>()
        fun visit(el: PsiElement) {
            for (c in el.children) {
                when (c) {
                    is SqlTableRef -> result += tableSource(c)
                    is SqlSubquery -> {
                        val alias = generateSequence(c.nextSibling) { it.nextSibling }.firstOrNull { it is SqlAliasDef || it is SqlCompositeElement || it is SqlTableRef } as? SqlAliasDef
                        result += FromSource.Derived(alias?.aliasName ?: "", alias ?: c, outputColumns(c.query))
                    }
                    is SqlFunctionCall -> {
                        val alias = generateSequence(c.nextSibling) { it.nextSibling }.firstOrNull { it is SqlAliasDef || it is SqlCompositeElement } as? SqlAliasDef
                        if (alias != null) result += FromSource.Derived(alias.aliasName, alias, listOf(alias.aliasName))
                    }
                    is SqlJoinClause -> visit(c)
                    else -> if (c.node.elementType == E.PAREN_EXPRESSION) visit(c)
                }
            }
        }
        visit(from)
        return result
    }

    private fun tableSource(ref: SqlTableRef): FromSource {
        val alias = ref.alias
        val name = alias?.aliasName ?: ref.tableName ?: ""
        val segs = ref.segments
        if (segs.size == 1) findCte(ref, segs[0].name)?.let { cte ->
            return FromSource.Derived(name, alias ?: cte, outputColumns(cte.query))
        }
        val obj = resolveTableObject(ref, segs.map { it.name })
        return FromSource.Table(name, alias ?: ref, ref, obj?.table, obj)
    }

    fun outputColumns(q: SqlQueryExpression?): List<String> {
        if (q == null) return emptyList()
        val select = q.node.findChildByType(E.SELECT_CLAUSE)?.psi ?: return emptyList()
        return PsiTreeUtil.getChildrenOfTypeAsList(select, SqlSelectItem::class.java).flatMap { item ->
            if (item.isStar) sourcesOfQuery(q).flatMap { it.columnNames() } else listOfNotNull(item.outputName)
        }
    }

    /** Target table of INSERT column lists and UPDATE SET targets. */
    fun dmlTarget(e: PsiElement): FromSource.Table? {
        val stmt = PsiTreeUtil.getParentOfType(e, SqlStatement::class.java) ?: return null
        val ref = stmt.node.findChildByType(E.TABLE_REF)?.psi as? SqlTableRef ?: return null
        return tableSource(ref) as? FromSource.Table
    }

    fun column(source: FromSource, name: String): PsiElement? = when (source) {
        is FromSource.Table -> source.table?.column(name)?.let { c -> source.obj?.let { DbObjectElement(it.project, it.dataSourceId, it.database, it.schema, it.table, c) } }
        is FromSource.Derived -> if (name in source.columns) source.element else null
    }

    fun columnOf(obj: DbObjectElement, name: String): DbColumn? = obj.table.column(name)
}
