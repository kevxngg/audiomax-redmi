package com.kevxngg.audiomax

/**
 * An update is only mandatory when the published release says so with a minimum
 * build (MIN_BUILD file in the repository). By default it never blocks.
 */
object UpdatePolicy {
    fun isRequired(installed: Int, minBuild: Int): Boolean =
        installed > 0 && minBuild > installed

    fun isOffered(installed: Int, latest: Int, skipped: Int, ignoreSkipped: Boolean = false, minBuild: Int = 0): Boolean =
        latest > installed && (ignoreSkipped || latest > skipped || isRequired(installed, minBuild))
}
