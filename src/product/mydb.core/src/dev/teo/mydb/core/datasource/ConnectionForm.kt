package dev.teo.mydb.core.datasource

/**
 * Editable state of the "general" tab of the data source dialog.
 *
 * Keeps host/port/database and the JDBC URL in sync in both directions and derives the
 * data source name from `<user>@<host>` until the user edits the name.
 */
class ConnectionForm(val dbms: Dbms) {
    private var _host = "localhost"
    private var _port = dbms.defaultPort
    private var _database = dbms.defaultDatabase
    private var _user = dbms.defaultUser

    var host: String
        get() = _host
        set(value) { _host = value; rebuildUrl(); refreshAutoName() }
    var port: Int
        get() = _port
        set(value) { _port = value; rebuildUrl() }
    var database: String?
        get() = _database
        set(value) { _database = value; rebuildUrl(); if (dbms == Dbms.SQLITE || dbms == Dbms.DDL) refreshAutoName() }
    var user: String?
        get() = _user
        set(value) { _user = value; refreshAutoName() }

    var url: String = ""
        private set
    var name: String = ""
        private set
    var nameEditedByUser: Boolean = false
        private set

    init {
        rebuildUrl()
        refreshAutoName()
    }

    /** User typed a URL directly: individual fields are overwritten from it. */
    fun editUrl(newUrl: String) {
        url = newUrl
        val parts = JdbcUrls.parse(dbms, newUrl) ?: return
        _host = parts.host
        _port = parts.port ?: dbms.defaultPort
        _database = parts.database
        refreshAutoName()
    }

    fun editName(newName: String) {
        name = newName
        nameEditedByUser = true
    }

    fun toConfig(base: DataSourceConfig? = null): DataSourceConfig =
        (base ?: DataSourceConfig(name = name, dbms = dbms, url = url)).copy(name = name, dbms = dbms, url = url, user = user)

    private fun rebuildUrl() {
        url = JdbcUrls.build(dbms, _host, _port, _database)
    }

    private fun refreshAutoName() {
        if (nameEditedByUser) return
        // a SQLite data source is a file: it is named after the file
        name = if (dbms == Dbms.SQLITE || dbms == Dbms.DDL) _database?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() } ?: "sqlite"
               else listOfNotNull(_user?.takeIf { it.isNotBlank() }, _host).joinToString("@")
    }
}
