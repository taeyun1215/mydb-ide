package dev.teo.mydb.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SmokeTest : BasePlatformTestCase() {
    fun testPlatformStarts() {
        assertNotNull(project)
    }
}
