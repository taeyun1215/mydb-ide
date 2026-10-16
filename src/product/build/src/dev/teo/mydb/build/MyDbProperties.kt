package dev.teo.mydb.build

import com.intellij.platform.buildScripts.licenses.CommunityLibraryLicenses
import com.intellij.platform.buildScripts.licenses.LibraryLicense
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.plus
import org.jetbrains.intellij.build.ApplicationInfoProperties
import org.jetbrains.intellij.build.LinuxDistributionCustomizer
import org.jetbrains.intellij.build.MacDistributionCustomizer
import org.jetbrains.intellij.build.ProductProperties
import org.jetbrains.intellij.build.WindowsDistributionCustomizer
import org.jetbrains.intellij.build.knownMissingModuleDependencies
import org.jetbrains.intellij.build.productLayout.CommunityModuleSets
import org.jetbrains.intellij.build.productLayout.DEFAULT_BUNDLED_PLUGINS
import org.jetbrains.intellij.build.productLayout.ProductModulesContentSpec
import org.jetbrains.intellij.build.productLayout.productModules
import org.jetbrains.intellij.build.windowsCustomizer
import java.nio.file.Path
import com.intellij.platform.ijent.community.buildConstants.IJENT_BOOT_CLASSPATH_MODULE
import org.jetbrains.intellij.build.impl.PlatformJarNames.PLATFORM_CORE_NIO_FS

/**
 * MyDB IDE: the open-source IDE platform with our database plugin and the platform plugins a
 * DB IDE needs.
 */
class MyDbProperties(private val communityHome: Path, private val productHome: Path) : ProductProperties() {
    override val baseFileName: String get() = "mydb"

    init {
        platformPrefix = "MyDb"
        applicationInfoModule = "mydb.resources"
        imagesDirectoryPath = productHome.resolve("build/images")
        brandingResourcePaths = listOf(productHome.resolve("mydb.resources/src"))
        customJvmMemoryOptions = persistentMapOf("-Xms" to "256m", "-Xmx" to "2048m")
        scrambleMainJar = false
        // Native libraries extracted from their jars into lib/<name>/ (the platform's usual set).
        presignedNativeLibs = mapOf(
            "pty4j" to "pty4j",
            "jna" to "jna",
            "native" to "native",
            "async-profiler" to "async-profiler",
            "skiko-awt-runtime-all" to "skiko-awt-runtime-all",
        )
        buildSourcesArchive = false

        productLayout.productImplementationModules = listOf(
            "intellij.platform.starter",
            "mydb.resources",
        )
        productLayout.bundledPluginModules = DEFAULT_BUNDLED_PLUGINS + persistentListOf(
            "mydb.plugin", // database tools (our code)
            "intellij.json",
            "intellij.yaml",
            "intellij.markdown",
            "intellij.textmate.plugin",
            "intellij.terminal",
            "intellij.vcs.git",
            "intellij.vcs.github",
            "intellij.grazie",
            "intellij.sh.plugin",
            "intellij.editorconfig.plugin",
            "intellij.configurationScript",
            "intellij.searchEverywhereMl",
            "intellij.color.scheme.github",
            "intellij.xpath",
            "intellij.mcpserver",
        )
        productLayout.addPlatformSpec { layout, _ -> layout.withModule(IJENT_BOOT_CLASSPATH_MODULE, PLATFORM_CORE_NIO_FS) }
        productLayout.skipUnresolvedContentModules = true
        productLayout.prepareCustomPluginRepositoryForPublishedPlugins = false
        productLayout.buildAllCompatiblePlugins = false
        allLibraryLicenses = CommunityLibraryLicenses.LICENSES_LIST + MYDB_LIBRARY_LICENSES
        // the product's own LICENSE and NOTICE next to the third-party license files
        additionalDirectoriesWithLicenses = listOf(productHome.resolve("build/license"))
        additionalVmOptions = persistentListOf("-Dllm.show.ai.promotion.window.on.start=false", "-Dide.no.platform.update=true")
    }

    override fun getProductContentDescriptor(): ProductModulesContentSpec = productModules {
        alias("com.intellij.modules.mydb")
        alias("com.intellij.platform.ide.provisioner")
        module("intellij.platform.ide.newUiOnboarding")
        moduleSet(CommunityModuleSets.ideCommon())
        moduleSet(CommunityModuleSets.rdCommon())
        deprecatedInclude("intellij.platform.resources", "META-INF/PlatformLangPlugin.xml")
        deprecatedInclude("mydb.resources", "META-INF/mydb-customization.xml")
        embeddedModule("intellij.platform.remoteServers.impl")
        allowMissingDependencies(knownMissingModuleDependencies)
        allowMissingDependencies("intellij.platform.commercial.dependencies")
        bundledPlugins(productLayout.bundledPluginModules)
    }

    override fun getBaseArtifactName(appInfo: ApplicationInfoProperties, buildNumber: String) = "mydb-$buildNumber"

    override fun getSystemSelector(appInfo: ApplicationInfoProperties, buildNumber: String) =
        "MyDB${appInfo.majorVersion}.${appInfo.minorVersionMainPart}"

    override fun createWindowsCustomizer(projectHome: Path): WindowsDistributionCustomizer = windowsCustomizer(communityHome) {
        fullName { "MyDB IDE" }
        installDirNameHandler { "MyDB IDE" }
    }

    override fun createMacCustomizer(projectHome: Path): MacDistributionCustomizer = object : MacDistributionCustomizer() {
        init { bundleIdentifier = "dev.teo.mydb" }
        override fun getRootDirectoryName(appInfo: ApplicationInfoProperties, buildNumber: String) = "MyDB IDE.app"
    }

    override fun createLinuxCustomizer(projectHome: Path): LinuxDistributionCustomizer = object : LinuxDistributionCustomizer() {
        override fun getRootDirectoryName(appInfo: ApplicationInfoProperties, buildNumber: String) = "mydb-ide"
    }

    override fun getOutputDirectoryName(appInfo: ApplicationInfoProperties) = "mydb"
}

/** Licenses of the libraries used only by MyDB modules (mydb.core). */
private val MYDB_LIBRARY_LICENSES = listOf(
    LibraryLicense("PostgreSQL JDBC Driver", libraryName = "postgresql", url = "https://jdbc.postgresql.org/")
        .simplifiedBsd("https://github.com/pgjdbc/pgjdbc/blob/master/LICENSE"),
    // used unmodified, as a separate jar (LGPL 2.1 section 6)
    LibraryLicense("MariaDB Connector/J", libraryName = "mariadb-java-client", url = "https://mariadb.com/kb/en/about-mariadb-connector-j/")
        .lgpl21("https://github.com/mariadb-corporation/mariadb-connector-j/blob/master/LICENSE"),
    LibraryLicense("Apache POI", libraryName = "poi", url = "https://poi.apache.org/")
        .additionalLibraryNames("poi-ooxml", "poi-ooxml-lite")
        .apache("https://github.com/apache/poi/blob/trunk/LICENSE"),
    LibraryLicense("Apache XMLBeans", libraryName = "xmlbeans", url = "https://xmlbeans.apache.org/")
        .apache("https://github.com/apache/xmlbeans/blob/trunk/LICENSE.txt"),
    LibraryLicense("Apache Commons Collections 4", libraryName = "commons-collections4", url = "https://commons.apache.org/proper/commons-collections/")
        .apache("https://github.com/apache/commons-collections/blob/master/LICENSE.txt"),
    LibraryLicense("Apache Log4j API", libraryName = "log4j-api", url = "https://logging.apache.org/log4j/2.x/")
        .apache("https://github.com/apache/logging-log4j2/blob/2.x/LICENSE.txt"),
    LibraryLicense("SparseBitSet", libraryName = "SparseBitSet", url = "https://github.com/brettwooldridge/SparseBitSet")
        .apache("https://github.com/brettwooldridge/SparseBitSet/blob/master/LICENSE"),
    LibraryLicense("curvesapi", libraryName = "curvesapi", url = "https://github.com/virtuald/curvesapi")
        .newBsd("https://github.com/virtuald/curvesapi/blob/master/license.txt"),
)
