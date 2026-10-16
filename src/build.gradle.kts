plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    id("org.jetbrains.intellij.platform") version "2.19.0" apply false
}

allprojects {
    group = "dev.teo.mydb"
    version = "0.1.0"
}
