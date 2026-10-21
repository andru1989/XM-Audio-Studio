package io.github.andru1989.xmaudiostudio.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Loads files from the repository's shared/ directory (path injected by Gradle). */
object SharedFixtures {
    val root: File by lazy {
        val configured = System.getProperty("xmaudio.sharedDir")
        val dir = configured?.let(::File) ?: File("../../shared")
        require(dir.isDirectory) { "shared/ directory not found at ${dir.absolutePath}" }
        dir
    }

    fun file(path: String): File = File(root, path)

    fun json(path: String): JsonObject = Json.parseToJsonElement(file(path).readText()) as JsonObject
}

val JsonElement.str: String get() = jsonPrimitive.content
val JsonElement.int: Int get() = jsonPrimitive.content.toInt()
val JsonElement.double: Double get() = jsonPrimitive.content.toDouble()
val JsonElement.bool: Boolean get() = (this as JsonPrimitive).content.toBooleanStrict()
