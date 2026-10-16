package dev.teo.mydb.plugin.diagram

import com.intellij.openapi.project.Project
import dev.teo.mydb.core.diagram.Diagram
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** Node positions of ERDs, kept in `.idea/mydb/diagrams/<key>.json` of the project. */
object DiagramPositions {
    fun file(project: Project, key: String): Path? = project.basePath?.let {
        Path.of(it, ".idea", "mydb", "diagrams", key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
    }

    fun save(project: Project, key: String, diagram: Diagram) {
        val f = file(project, key) ?: return
        val json = JsonObject(diagram.nodes.associate { it.id to JsonArray(listOf(JsonPrimitive(it.x), JsonPrimitive(it.y))) })
        Files.createDirectories(f.parent)
        Files.writeString(f, Json.encodeToString(JsonObject.serializer(), json))
    }

    /** Moves the nodes that have a saved position; returns true when anything was restored. */
    fun restore(project: Project, key: String, diagram: Diagram): Boolean {
        val f = file(project, key)?.takeIf(Files::exists) ?: return false
        val saved = runCatching { Json.parseToJsonElement(Files.readString(f)).jsonObject }.getOrNull() ?: return false
        var any = false
        for (n in diagram.nodes) {
            val xy = saved[n.id]?.jsonArray ?: continue
            n.x = xy[0].jsonPrimitive.double; n.y = xy[1].jsonPrimitive.double
            any = true
        }
        return any
    }
}
