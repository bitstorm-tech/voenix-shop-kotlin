package shop.voenix.ktfmt

import org.jetbrains.amper.plugins.Configurable

@Configurable
public interface KtfmtSettings {
    /** Whether this module owns formatting and checking the shared quality-plugin sources. */
    public val includePluginSources: Boolean
        get() = false
}
