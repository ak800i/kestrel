package dev.kestrel.commands

/**
 * Words of the instant commands that the recogniser may miss without help, boosted with every utterance (design spec,
 * section 8.3). The common command words, such as "tap", "scroll" or "back", need no boost. A device test,
 * `ScreenBoostWordsTest`, checks that sherpa-onnx keeps every one of them.
 */
object CommandVocabulary {
    val BOOST: List<String> = listOf(
        "Bluetooth",
        "Wi-Fi",
        "Quick Settings",
        "unselect",
        "uppercase",
        "lowercase",
        "semicolon",
        "magnification",
    )
}
