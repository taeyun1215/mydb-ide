import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(25)
    sourceSets["main"].kotlin.setSrcDirs(listOf("../product/mydb.plugin/src"))
}

sourceSets["main"].resources.setSrcDirs(listOf("../product/mydb.plugin/resources"))

dependencies {
    implementation(project(":core")) {
        // stdlib and kotlinx-serialization come from the platform
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion")) { useInstaller = false }
        bundledPlugin("intellij.grid.core.plugin")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-mariadb:2.0.5")
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.teo.mydb"
        name = "MyDB"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "262"
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}

tasks.test {
    systemProperty("mydb.seedDir", rootProject.file("../testdb/seed").absolutePath)
}
