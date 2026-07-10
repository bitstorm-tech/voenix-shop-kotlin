package shop.voenix.country

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

suspend fun ApplicationCall.receiveCountryFields(requestTypeName: String): CountryRequestFields {
    val body = receiveText()
    if (body.isEmpty()) throw missingRequestBody()
    val parsed =
        try {
            requestJson.parseToJsonElement(body)
        } catch (exception: SerializationException) {
            val property =
                PROPERTY_PATTERN
                    .findAll(body)
                    .lastOrNull()
                    ?.groupValues
                    ?.get(1)
            val path = property?.let { "$.$it" } ?: "$"
            val (lineNumber, bytePosition) = body.positionAt(body.length)
            val detail =
                if (body.trimEnd().endsWith(':')) {
                    "Expected depth to be zero at the end of the JSON payload. " +
                        "There is an open JSON object or array that should be closed. " +
                        "Path: $path | LineNumber: $lineNumber | " +
                        "BytePositionInLine: $bytePosition."
                } else {
                    "The JSON payload is invalid. Path: $path | LineNumber: $lineNumber | " +
                        "BytePositionInLine: $bytePosition."
                }
            throw CountryRequestException(
                errors =
                    linkedMapOf(
                        "request" to listOf("The request field is required."),
                        path to listOf(detail),
                    ),
                cause = exception,
            )
        }
    if (parsed === JsonNull) throw missingRequestBody()
    val bodyObject = parsed as? JsonObject ?: throw topLevelConversionError(body, requestTypeName)
    val properties = body.topLevelProperties()
    return CountryRequestFields(
        name = bodyObject.stringValue("name", body, properties),
        countryCode = bodyObject.stringValue("countryCode", body, properties),
    )
}

private fun JsonObject.stringValue(
    propertyName: String,
    body: String,
    properties: List<JsonPropertyOccurrence>,
): String? {
    val property = properties.lastOrNull { it.name.equals(propertyName, ignoreCase = true) } ?: return null
    val value = getValue(property.name)
    if (value === JsonNull) return null
    if (value !is JsonPrimitive || !value.isString) {
        val path = "$.${property.name}"
        val valueEnd = body.jsonValueEnd(property.valueStart)
        val (lineNumber, bytePosition) = body.positionAt(valueEnd)
        val detail =
            "The JSON value could not be converted to System.String. Path: $path | " +
                "LineNumber: $lineNumber | BytePositionInLine: $bytePosition."
        throw CountryRequestException(
            linkedMapOf(
                "request" to listOf("The request field is required."),
                path to listOf(detail),
            ),
        )
    }
    return value.content
}

private fun String.jsonValueEnd(startIndex: Int): Int {
    var index = startIndex.coerceAtMost(length)
    while (index < length && this[index].isWhitespace()) index++
    if (index >= length) return length

    if (this[index] == '"') return jsonStringEnd(index)
    if (this[index] != '{' && this[index] != '[') {
        while (index < length && this[index] !in charArrayOf(',', '}', ']') && !this[index].isWhitespace()) {
            index++
        }
        return index
    }

    var depth = 0
    var inString = false
    var escaped = false
    while (index < length) {
        val character = this[index++]
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
        } else {
            if (character == '"') {
                inString = true
            } else if (character == '{' || character == '[') {
                depth++
            } else if (character == '}' || character == ']') {
                depth--
                if (depth == 0) return index
            }
        }
    }
    return length
}

private fun String.jsonStringEnd(startIndex: Int): Int {
    var index = startIndex + 1
    var escaped = false
    while (index < length) {
        val character = this[index++]
        when {
            escaped -> escaped = false
            character == '\\' -> escaped = true
            character == '"' -> return index
        }
    }
    return length
}

private fun String.topLevelProperties(): List<JsonPropertyOccurrence> {
    val properties = mutableListOf<JsonPropertyOccurrence>()
    var index = indexOfFirst { character -> !character.isWhitespace() } + 1
    while (index in indices) {
        while (index < length && (this[index].isWhitespace() || this[index] == ',')) index++
        if (index >= length || this[index] == '}') break

        val nameStart = index
        val nameEnd = jsonStringEnd(nameStart)
        val name =
            requestJson
                .parseToJsonElement(substring(nameStart, nameEnd))
                .jsonPrimitive.content
        index = nameEnd
        while (index < length && this[index].isWhitespace()) index++
        check(getOrNull(index) == ':') { "A parsed JSON object property must contain a colon" }
        index++
        while (index < length && this[index].isWhitespace()) index++

        properties += JsonPropertyOccurrence(name, index)
        index = jsonValueEnd(index)
    }
    return properties
}

private fun String.positionAt(endExclusive: Int): Pair<Int, Int> {
    val safeEnd = endExclusive.coerceIn(0, length)
    val prefix = substring(0, safeEnd)
    val lineNumber = prefix.count { character -> character == '\n' }
    val lineStart = prefix.lastIndexOf('\n') + 1
    val bytePosition = prefix.substring(lineStart).toByteArray(Charsets.UTF_8).size
    return lineNumber to bytePosition
}

private fun missingRequestBody(): CountryRequestException =
    CountryRequestException(
        linkedMapOf(
            "" to listOf("A non-empty request body is required."),
            "request" to listOf("The request field is required."),
        ),
    )

private fun topLevelConversionError(
    body: String,
    requestTypeName: String,
): CountryRequestException {
    val valueStart = body.indexOfFirst { character -> !character.isWhitespace() }.coerceAtLeast(0)
    val valueEnd =
        if (body.getOrNull(valueStart) == '[') valueStart + 1 else body.jsonValueEnd(valueStart)
    val (lineNumber, bytePosition) = body.positionAt(valueEnd)
    val detail =
        "The JSON value could not be converted to $requestTypeName. Path: $ | " +
            "LineNumber: $lineNumber | BytePositionInLine: $bytePosition."
    return CountryRequestException(
        linkedMapOf(
            "$" to listOf(detail),
            "request" to listOf("The request field is required."),
        ),
    )
}

private val requestJson = Json { ignoreUnknownKeys = true }
private val PROPERTY_PATTERN = Regex("\"([^\"]+)\"\\s*:")
