package dev.teo.mydb.core.connection

import dev.teo.mydb.core.datasource.DataSourceConfig
import java.util.Properties

/** One driver property for the advanced tab; [changed] when the data source overrides it. */
data class DriverProperty(
    val name: String,
    val value: String?,
    val defaultValue: String?,
    val description: String?,
    val choices: List<String>,
    val changed: Boolean,
)

object DriverProperties {
    /** Every property the driver reports (`Driver.getPropertyInfo`), by name, with the data source's overrides applied. */
    fun describe(config: DataSourceConfig, drivers: DriverRegistry = DriverRegistry.DEFAULT): List<DriverProperty> {
        val driver = drivers.driverFor(config.dbms)
        val info = driver.getPropertyInfo(config.url, Properties())
        val known = info.map { p ->
            val override = config.driverProperties[p.name]
            DriverProperty(p.name, override ?: p.value, p.value, p.description, p.choices?.toList().orEmpty(), override != null && override != p.value)
        }
        val custom = config.driverProperties.filterKeys { k -> info.none { it.name == k } }
            .map { (k, v) -> DriverProperty(k, v, null, null, emptyList(), changed = true) }
        return (known + custom).sortedBy { it.name.lowercase() }
    }
}
