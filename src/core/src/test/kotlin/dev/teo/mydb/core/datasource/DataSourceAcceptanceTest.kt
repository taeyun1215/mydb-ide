package dev.teo.mydb.core.datasource

import dev.teo.mydb.core.SeededPostgres
import dev.teo.mydb.core.connection.ConnectionTestResult
import dev.teo.mydb.core.connection.ConnectionTester
import dev.teo.mydb.core.connection.IdentifierCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Data source settings, storage and connection tests. */
class DataSourceAcceptanceTest {

    @Test
    fun `new postgres data source defaults`() {
        val f = ConnectionForm(Dbms.POSTGRES)
        assertEquals("postgres@localhost", f.name)
        assertEquals("localhost", f.host)
        assertEquals(5432, f.port)
        assertEquals("postgres", f.database)
        assertEquals("jdbc:postgresql://localhost:5432/postgres", f.url)
    }

    @Test
    fun `fields rebuild the url`() {
        val f = ConnectionForm(Dbms.POSTGRES)
        f.port = 55432
        f.database = "obs"
        assertEquals("jdbc:postgresql://localhost:55432/obs", f.url)
    }

    @Test
    fun `url edit overwrites fields`() {
        val f = ConnectionForm(Dbms.POSTGRES)
        f.editUrl("jdbc:postgresql://h:1/d")
        assertEquals("h", f.host)
        assertEquals(1, f.port)
        assertEquals("d", f.database)
        assertEquals("jdbc:postgresql://h:1/d", f.url)
    }

    @Test
    fun `name follows user until edited`() {
        val f = ConnectionForm(Dbms.POSTGRES)
        f.user = "obs"
        assertEquals("obs@localhost", f.name)
        f.editName("mine")
        f.user = "other"
        f.host = "db"
        assertEquals("mine", f.name)
        assertTrue(f.nameEditedByUser)
    }

    @Test
    fun `test connection success reports server details`() {
        val r = ConnectionTester().test(SeededPostgres.config(), SeededPostgres.PASSWORD)
        assertIs<ConnectionTestResult.Success>(r)
        assertEquals("PostgreSQL", r.productName)
        assertTrue(r.productVersion.startsWith("17"))
        assertEquals(IdentifierCase.LOWER, r.plainIdentifierCase)
        assertEquals(IdentifierCase.EXACT, r.quotedIdentifierCase)
        assertEquals("PostgreSQL JDBC Driver", r.driverName)
        assertTrue(r.pingMillis >= 0)
        assertFalse(r.ssl)
    }

    @Test
    fun `test connection failure shows server message`() {
        val r = ConnectionTester().test(SeededPostgres.config(), "wrong")
        assertIs<ConnectionTestResult.Failure>(r)
        assertEquals("28P01", r.sqlState)
        assertTrue("password" in r.message)
    }

    @Test
    fun `sessions announce the product as application_name`() {
        val name = dev.teo.mydb.core.connection.Connector().open(SeededPostgres.config(), SeededPostgres.PASSWORD).use { c ->
            c.createStatement().executeQuery("show application_name").use { rs -> rs.next(); rs.getString(1) }
        }
        assertEquals("MyDB IDE 0.1.0", name)
    }
}
