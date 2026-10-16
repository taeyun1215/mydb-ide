package dev.teo.mydb.customization

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.actionSystem.ex.ActionRuntimeRegistrar
import com.intellij.openapi.actionSystem.impl.ActionConfigurationCustomizer
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.updateSettings.impl.UpdateSettings

/**
 * MyDB IDE is a standalone product: no menu items or services that lead to the platform vendor's sites,
 * update server, plugin marketplace or error tracker.
 */
class MyDbActionRemover : ActionConfigurationCustomizer, ActionConfigurationCustomizer.LightCustomizeStrategy {
    override suspend fun customize(actionRegistrar: ActionRuntimeRegistrar) {
        REMOVED_ACTIONS.forEach { id -> if (actionRegistrar.getActionOrStub(id) != null) actionRegistrar.unregisterAction(id) }
    }

    companion object {
        val REMOVED_ACTIONS = listOf(
            "HelpTopics", "OnlineDocAction", "Help.JetBrainsTV", "Help.KeymapReference", "TechnicalSupport", "ReportProblem",
            "SendFeedback", "WhatsNewAction", "CheckForUpdate", "ShowTips",
            "WelcomeScreen.Plugins", "WelcomeScreen.Configure.Import", "ImportSettings", "ShowPlugins",
            // cloud settings sync needs the vendor's account service
            "SettingsSyncSeparator", "SettingsSyncStatusAction", "SettingsSyncOpenSettingsAction",
        )
    }
}

/** Removes platform extensions that talk to the vendor's services, before any frame opens. */
class MyDbStandaloneSetup : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        remove("com.intellij.errorHandler", "com.intellij.diagnostic.ITNReporter")
        remove("com.intellij.welcomeTabFactory", "com.intellij.openapi.wm.impl.welcomeScreen.PluginsTabFactory")
        remove("com.intellij.welcomeTabFactory", "com.intellij.openapi.wm.impl.welcomeScreen.LearnIdeTabFactory")
        removeConfigurables(setOf("preferences.pluginManager", "preferences.updates", "settings.sync"))
        // without this activity the "plugins supporting this file" banner has no marketplace data to show
        remove("com.intellij.postStartupActivity", "com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginsAdvertiserStartupActivity")
        remove("com.intellij.openAnotherToolHandler", "com.intellij.openapi.updateSettings.impl.upgradeToUltimate.installation.install.TryUltimateActionHandler")
        // no update server: never check for IDE or plugin updates
        UpdateSettings.getInstance().state.apply { isCheckNeeded = false; isPluginsCheckNeeded = false }
    }

    private fun remove(point: String, implementation: String) {
        runCatching { ExtensionPointName<Any>(point).point.unregisterExtensions({ className, _ -> className != implementation }, false) }
            .onFailure { thisLogger().warn("cannot remove $implementation from $point", it) }
    }

    private fun removeConfigurables(ids: Set<String>) {
        val ep = Configurable.APPLICATION_CONFIGURABLE.point
        ep.extensionList.filter { it.id in ids }.forEach(ep::unregisterExtension)
    }
}
