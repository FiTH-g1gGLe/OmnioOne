package com.omnioone.commons.helpers

/**
 * Omnio One: the Fossify code checks the app's package name in several places
 * (to unlock dialer features and so on).
 * Omnio One installs as com.omnioone.app, so these helpers treat it as a genuine build.
 */
const val OMNIO_PACKAGE = "com.omnioone.app"
const val OMNIO_GITHUB_URL = "https://github.com/FiTH-g1gGLe/OmnioOne"

fun isOurPackage(packageName: String): Boolean =
    packageName.startsWith("org.fossify", true) || packageName.startsWith(OMNIO_PACKAGE, true)

fun isOurDialerPackage(packageName: String): Boolean =
    packageName.startsWith("com.omnioone.app") || packageName.startsWith(OMNIO_PACKAGE)
