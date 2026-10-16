package dev.teo.mydb.core.model

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Persists introspected models so the tree and completion work before connecting. */
class ModelStore(private val dir: Path) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = false }

    fun save(model: DbModel) {
        Files.createDirectories(dir)
        val target = file(model.dataSourceId)
        val tmp = Files.createTempFile(dir, "model", ".tmp")
        Files.writeString(tmp, json.encodeToString(DbModel.serializer(), model))
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun load(dataSourceId: String): DbModel? {
        val f = file(dataSourceId)
        if (!Files.exists(f)) return null
        return runCatching { json.decodeFromString(DbModel.serializer(), Files.readString(f)) }.getOrNull()
    }

    fun delete(dataSourceId: String) = Files.deleteIfExists(file(dataSourceId))

    private fun file(id: String): Path = dir.resolve("$id.json")
}
