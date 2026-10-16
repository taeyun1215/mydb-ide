package dev.teo.mydb.plugin.sql

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class SqlElementType(debugName: String) : IElementType(debugName, SqlLanguage)

object SqlElementTypes {
    val FILE = IFileElementType(SqlLanguage)

    // statements
    val SELECT_STATEMENT = SqlElementType("SELECT_STATEMENT")
    val INSERT_STATEMENT = SqlElementType("INSERT_STATEMENT")
    val UPDATE_STATEMENT = SqlElementType("UPDATE_STATEMENT")
    val DELETE_STATEMENT = SqlElementType("DELETE_STATEMENT")
    val CREATE_TABLE_STATEMENT = SqlElementType("CREATE_TABLE_STATEMENT")
    val ALTER_TABLE_STATEMENT = SqlElementType("ALTER_TABLE_STATEMENT")
    val DROP_STATEMENT = SqlElementType("DROP_STATEMENT")
    val EXPLAIN_STATEMENT = SqlElementType("EXPLAIN_STATEMENT")
    val OTHER_STATEMENT = SqlElementType("OTHER_STATEMENT")

    // query structure
    val QUERY_EXPRESSION = SqlElementType("QUERY_EXPRESSION") // select core possibly with set operations
    val WITH_CLAUSE = SqlElementType("WITH_CLAUSE")
    val CTE = SqlElementType("CTE")
    val SELECT_CLAUSE = SqlElementType("SELECT_CLAUSE")
    val SELECT_ITEM = SqlElementType("SELECT_ITEM")
    val FROM_CLAUSE = SqlElementType("FROM_CLAUSE")
    val JOIN_CLAUSE = SqlElementType("JOIN_CLAUSE")
    val JOIN_CONDITION = SqlElementType("JOIN_CONDITION")
    val WHERE_CLAUSE = SqlElementType("WHERE_CLAUSE")
    val GROUP_BY_CLAUSE = SqlElementType("GROUP_BY_CLAUSE")
    val HAVING_CLAUSE = SqlElementType("HAVING_CLAUSE")
    val ORDER_BY_CLAUSE = SqlElementType("ORDER_BY_CLAUSE")
    val LIMIT_CLAUSE = SqlElementType("LIMIT_CLAUSE")
    val SET_CLAUSE = SqlElementType("SET_CLAUSE")
    val VALUES_CLAUSE = SqlElementType("VALUES_CLAUSE")
    val RETURNING_CLAUSE = SqlElementType("RETURNING_CLAUSE")
    val COLUMN_LIST = SqlElementType("COLUMN_LIST")
    val SUBQUERY = SqlElementType("SUBQUERY")

    // references and definitions
    val TABLE_REF = SqlElementType("TABLE_REF")
    val ALIAS_DEF = SqlElementType("ALIAS_DEF")
    val COLUMN_REF = SqlElementType("COLUMN_REF")
    val FUNCTION_CALL = SqlElementType("FUNCTION_CALL")
    val TABLE_DEF = SqlElementType("TABLE_DEF")
    val COLUMN_DEF = SqlElementType("COLUMN_DEF")
    val CONSTRAINT_DEF = SqlElementType("CONSTRAINT_DEF")
    val TYPE_ELEMENT = SqlElementType("TYPE_ELEMENT")

    // expressions
    val EXPRESSION = SqlElementType("EXPRESSION")
    val BINARY_EXPRESSION = SqlElementType("BINARY_EXPRESSION")
    val LITERAL = SqlElementType("LITERAL")
    val PAREN_EXPRESSION = SqlElementType("PAREN_EXPRESSION")
    val CASE_EXPRESSION = SqlElementType("CASE_EXPRESSION")

    val STATEMENTS = TokenSet.create(
        SELECT_STATEMENT, INSERT_STATEMENT, UPDATE_STATEMENT, DELETE_STATEMENT, CREATE_TABLE_STATEMENT,
        ALTER_TABLE_STATEMENT, DROP_STATEMENT, EXPLAIN_STATEMENT, OTHER_STATEMENT,
    )
    val CLAUSES = TokenSet.create(
        WITH_CLAUSE, SELECT_CLAUSE, FROM_CLAUSE, WHERE_CLAUSE, GROUP_BY_CLAUSE, HAVING_CLAUSE, ORDER_BY_CLAUSE,
        LIMIT_CLAUSE, SET_CLAUSE, VALUES_CLAUSE, RETURNING_CLAUSE,
    )
}
