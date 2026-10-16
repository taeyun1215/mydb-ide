package dev.teo.mydb.customization

import com.intellij.platform.ide.customization.ExternalProductResourceUrls

/**
 * MyDB IDE has no update server, issue tracker or online help, so every link stays unset
 * Replaces the platform's deprecated fallback.
 */
class MyDbExternalResourceUrls : ExternalProductResourceUrls
