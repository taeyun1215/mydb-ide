package dev.teo.mydb.core

object ProductInfo {
    const val NAME = "MyDB IDE"
    const val VERSION = "0.1.0"
    val applicationName: String get() = "$NAME $VERSION"
}
