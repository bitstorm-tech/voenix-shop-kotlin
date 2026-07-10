package shop.voenix.config

import io.ktor.server.config.ApplicationConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

object AppSettingsSecrets {
    fun section(
        config: ApplicationConfig,
        sectionName: String,
    ): Map<String, String> {
        val path =
            Path.of(
                config.propertyOrNull("Secrets.AppSettingsPath")?.getString()
                    ?: "/etc/secrets/appsettings.json",
            )
        if (!Files.isRegularFile(path)) return emptyMap()

        val root = json.parseToJsonElement(Files.readString(path)).jsonObject
        val section = root.caseInsensitiveValue(sectionName) as? JsonObject ?: return emptyMap()
        return section.mapValues { (_, value) -> value.jsonPrimitive.content }
    }

    private fun JsonObject.caseInsensitiveValue(name: String) =
        entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

    private val json = Json { isLenient = true }
}
