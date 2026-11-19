package dev.kestrel.catalogsync

import java.io.File

/**
 * Runs the sync: `<checkout> <commit> <output>`. The Gradle task `syncCatalog` passes the checkout that
 * `scripts/sync-catalog.ps1` made, the commit, and the repository's `catalog/providers.json`.
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "Usage: <OpenClaw checkout> <commit> <providers.json to write>" }
    val result = CatalogSync.sync(File(args[0]), args[1])
    val output = File(args[2])
    output.parentFile.mkdirs()
    output.writeText(result.json)
    println("Wrote $output")
    result.summary.forEach(::println)
}
