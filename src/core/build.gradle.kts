plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
    // sources live in the product module so the platform (Bazel/JPS) build compiles the same files
    sourceSets["main"].kotlin.setSrcDirs(listOf("../product/mydb.core/src"))
}

dependencies {
    // provided by the IDE platform at runtime (versions pinned to the 262 platform)
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.9.0")
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.10")
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    implementation("com.hierynomus:sshj:0.41.2")

    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-mariadb:2.0.5")
}

tasks.test {
    useJUnitPlatform()
    // the seed database is shared with the plugin tests
    systemProperty("mydb.seedDir", rootProject.file("../testdb/seed").absolutePath)
}
