import dev.teo.mydb.build.MyDbProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.intellij.build.BuildOptions
import org.jetbrains.intellij.build.BuildPaths.Companion.COMMUNITY_ROOT
import org.jetbrains.intellij.build.impl.buildDistributions
import org.jetbrains.intellij.build.impl.createBuildContext

/** Builds the MyDB IDE distribution for the current OS. */
object MyDbInstallersBuildTarget {
    @JvmStatic
    fun main(args: Array<String>) {
        runBlocking(Dispatchers.Default) {
            val options = BuildOptions().apply {
                incrementalCompilation = true
                useCompiledClassesFromProjectOutput = false
                buildStepsToSkip += listOf(
                    BuildOptions.MAC_SIGN_STEP,
                    BuildOptions.WIN_SIGN_STEP,
                    BuildOptions.CROSS_PLATFORM_DISTRIBUTION_STEP,
                    BuildOptions.WINDOWS_ZIP_STEP,
                    BuildOptions.SOURCES_ARCHIVE_STEP,
                )
            }
            val home = COMMUNITY_ROOT.communityRoot
            val context = createBuildContext(
                projectHome = home,
                productProperties = MyDbProperties(home, home.resolve("mydb")),
                options = options,
            )
            buildDistributions(context)
        }
    }
}
