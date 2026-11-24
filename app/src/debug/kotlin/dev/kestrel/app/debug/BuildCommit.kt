package dev.kestrel.app.debug

import android.content.Context

/**
 * The commit of the worktree that this debug build came from, and whether that worktree had changes outside the tool and
 * the documentation, which the debug build writes into its assets as "<commit> <0|1>" (BuildCommitTask of build-logic).
 * The hook `state` answers them as `commit=<commit> dirty=<0|1>`, or `commit=unknown dirty=unknown` when the asset is
 * missing or malformed, so that `:tools:eval` can tell which Kestrel ran a suite (review I2 of plan 6B).
 */
internal object BuildCommit {
    const val ASSET = "build/commit.txt"
    private val CONTENT = Regex("([0-9a-f]{40}) ([01])")

    fun answer(asset: String?): String {
        val match = asset?.trim()?.let { CONTENT.matchEntire(it) } ?: return "commit=unknown dirty=unknown"
        return "commit=${match.groupValues[1]} dirty=${match.groupValues[2]}"
    }

    fun read(context: Context): String = answer(runCatching { context.assets.open(ASSET).use { it.readBytes().decodeToString() } }.getOrNull())
}
