package shop.voenix.production

import io.ktor.server.config.ApplicationConfig
import java.nio.file.Path

/**
 * Deployment configuration of the Production module. [artifactRoot] is the private filesystem root
 * for generated production PDFs; the module creates it at installation, so an unusable root fails
 * the application startup instead of the first background generation.
 */
public class ProductionSettings internal constructor(internal val artifactRoot: Path) {
    public companion object {
        public fun from(config: ApplicationConfig): ProductionSettings {
            val artifactRoot =
                config
                    .propertyOrNull("Production.ArtifactRoot")
                    ?.getString()
                    ?.takeIf(String::isNotBlank)
                    ?: error("Missing required configuration value: Production.ArtifactRoot")
            return ProductionSettings(Path.of(artifactRoot))
        }
    }
}
