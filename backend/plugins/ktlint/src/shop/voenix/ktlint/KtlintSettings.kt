package shop.voenix.ktlint

import org.jetbrains.amper.plugins.Configurable

@Configurable
public interface KtlintSettings {
    /** Whether this module owns checking the shared quality-plugin sources. */
    public val includePluginSources: Boolean
        get() = false
}
