package dev.kestrel.providers.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A tool that the model may call. Its parameters stay within the schema subset that every style accepts (design D3). */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject) {
    init {
        require(NAME.matches(name)) { "A tool name must match ${NAME.pattern}" }
        ToolSchemas.check(parameters)
    }

    override fun toString(): String =
        "ToolSpec(name=$name, descriptionLength=${description.length}, schemaLength=${parameters.toString().length})"

    private companion object {
        val NAME = Regex("^[a-zA-Z0-9_-]{1,64}$")
    }
}

/**
 * The JSON Schema subset of design D3: the types object, string, integer, number, boolean and array, with `properties`,
 * `required`, `items`, `enum` (on strings only, because Gemini accepts no other) and `description`. A nested object needs
 * at least one property; the top-level object may have none. The adapters never rewrite a schema.
 */
object ToolSchemas {
    private val TYPES = setOf("object", "string", "integer", "number", "boolean", "array")
    private val KEYS = setOf("type", "properties", "required", "items", "enum", "description")

    /** Throws IllegalArgumentException when [schema] leaves the subset. */
    fun check(schema: JsonObject) {
        require(schema.string("type") == "object") { "A tool's parameters must be an object" }
        checkNode(schema, path = "parameters", topLevel = true)
    }

    private fun checkNode(node: JsonObject, path: String, topLevel: Boolean) {
        val unknown = node.keys - KEYS
        require(unknown.isEmpty()) { "$path uses keywords outside the subset: ${unknown.sorted()}" }
        val type = node.string("type")
        require(type in TYPES) { "$path has no supported type" }
        node["description"]?.let { require(it.isString()) { "$path.description must be a string" } }
        if ("enum" in node) {
            require(type == "string") { "$path has an enum, which only strings may have" }
            val values = node["enum"] as? JsonArray
            require(values != null && values.isNotEmpty() && values.all { it.isString() }) { "$path.enum must list strings" }
        }
        require(type == "object" || ("properties" !in node && "required" !in node)) { "$path has properties but is no object" }
        require(type == "array" || "items" !in node) { "$path has items but is no array" }
        when (type) {
            "object" -> checkObject(node, path, topLevel)
            "array" -> {
                val items = node["items"] as? JsonObject
                require(items != null) { "$path is an array without items" }
                checkNode(items, "$path.items", topLevel = false)
            }
        }
    }

    private fun checkObject(node: JsonObject, path: String, topLevel: Boolean) {
        val properties = node["properties"]?.let { it as? JsonObject ?: throw IllegalArgumentException("$path.properties must be an object") }
            ?: JsonObject(emptyMap())
        require(topLevel || properties.isNotEmpty()) { "$path is a nested object without properties" }
        for ((name, value) in properties) {
            val child = value as? JsonObject ?: throw IllegalArgumentException("$path.$name must be a schema")
            checkNode(child, "$path.$name", topLevel = false)
        }
        node["required"]?.let { required ->
            require(required is JsonArray && required.all { it.isString() }) { "$path.required must list names" }
            val missing = required.map { (it as JsonPrimitive).content }.filter { it !in properties }
            require(missing.isEmpty()) { "$path.required names unknown properties: $missing" }
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement.isString(): Boolean = this is JsonPrimitive && isString
}
