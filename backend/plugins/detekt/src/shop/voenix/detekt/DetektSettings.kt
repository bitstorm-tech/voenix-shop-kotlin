package shop.voenix.detekt

import org.jetbrains.amper.plugins.Configurable

@Configurable
public interface DetektSettings {
    /** Whether this module owns checking the shared quality-plugin sources. */
    public val includePluginSources: Boolean
        get() = false
}
