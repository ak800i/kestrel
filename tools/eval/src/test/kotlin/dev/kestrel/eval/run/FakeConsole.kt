package dev.kestrel.eval.run

/** A console for the tests, which keeps what was printed. */
class FakeConsole : Console {
    val lines = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    override fun line(text: String) {
        lines.add(text)
    }

    override fun warn(text: String) {
        warnings.add(text)
    }
}
