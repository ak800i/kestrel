package dev.kestrel.commands

/**
 * Words of the instant commands that the recogniser may miss without help, boosted with every utterance (design spec,
 * section 8.3). The common command words, such as "tap", "scroll" or "back", need no boost. Not "Wi-Fi": boosted with
 * every utterance, it made the recogniser write "Wi-Fi" for other words, such as "Delete 3 Wi-Fi" for "Delete three
 * words", in plan 3C's corpus run, and without the boost the recogniser still wrote "Wi-Fi" or "Wi Fi", which the grammar
 * reads alike; a screen that shows "Wi-Fi" still boosts it as a label. A device test, `ScreenBoostWordsTest`, checks that
 * sherpa-onnx keeps every one of them.
 */
object CommandVocabulary {
    val BOOST: List<String> = listOf(
        "Bluetooth",
        "Quick Settings",
        "unselect",
        "uppercase",
        "lowercase",
        "semicolon",
        "magnification",
    )
}
