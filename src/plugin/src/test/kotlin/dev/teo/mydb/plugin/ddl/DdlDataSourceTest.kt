package dev.teo.mydb.plugin.ddl

import dev.teo.mydb.core.diff.DiffStatus
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.compare.CompareSide
import dev.teo.mydb.plugin.compare.SchemaCompare
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.model.ModelService
import dev.teo.mydb.plugin.transfer.DdlDump
import java.nio.file.Files

/** DDL data sources in the explorer. */
class DdlDataSourceTest : MyDbTestCase() {
    fun `test dump a schema to a DDL data source and compare it with the database`() {
        val file = Files.createTempDirectory("ddl-ds").resolve("shop.sql")
        val ddl = DdlDump.dump(project, dataSource, "obs", "shop", file)
        try {
            assertTrue(Files.readString(file).contains("create table shop.orders"))
            val shop = ModelService.getInstance(project).model(ddl.id)!!.databases.single().schema("shop")!!
            val live = ModelService.getInstance(project).model(dataSource.id)!!.schema("obs", "shop")!!
            assertEquals(live.tables.map { it.name }.toSet(), shop.tables.map { it.name }.toSet())
            assertEquals(live.table("orders")!!.foreignKeys.map { it.refTable }, shop.table("orders")!!.foreignKeys.map { it.refTable })

            val compare = SchemaCompare(project, CompareSide(dataSource, "obs", "shop"), CompareSide(ddl, "ddl", "shop"))
            val changed = compare.rows(showIdentical = false).filter { it.first.status != DiffStatus.SAME && it.first.kind != "view" }
            assertEquals(changed.map { it.first.id + " " + it.first.source + " / " + it.first.target }.toString(), 0, changed.size)

            // a change in the files shows up after a refresh
            Files.writeString(file, Files.readString(file) + "\ncreate table shop.audit (id integer primary key);\n")
            ModelService.getInstance(project).refreshNow(ddl)
            val again = SchemaCompare(project, CompareSide(dataSource, "obs", "shop"), CompareSide(ddl, "ddl", "shop"))
            assertEquals(DiffStatus.ONLY_IN_TARGET, again.diff.items.single { it.name == "audit" }.status)
            assertTrue(again.script(), again.script().contains("drop table shop.audit;"))
        } finally {
            DataSourceStorage.getInstance(project).remove(ddl.id)
            ModelService.getInstance(project).remove(ddl.id)
        }
    }
}
