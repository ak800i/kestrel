package dev.kestrel.perception

/**
 * The outputs that RapidOCR's Python reference (v3.10.0, with the same models and Kestrel's settings) wrote once for
 * these tests (design D20 of sub-project 5): `ocr/reference.txt` and `ocr/maps.txt` in the test resources.
 */
internal object Reference {
    fun lines(name: String): List<String> =
        (Reference::class.java.getResource("/ocr/$name") ?: error("Missing test resource ocr/$name")).readText().split('\n').filter { it.isNotBlank() }

    /** A test image whose pixel at (x, y) has red x·255/(w−1), green y·255/(h−1) and blue (x/4 + y/8) mod 256. */
    fun synthetic(width: Int, height: Int): OcrImage = OcrImage(width, height, IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        val red = x * 255 / (width - 1)
        val green = y * 255 / (height - 1)
        val blue = (x / 4 + y / 8) % 256
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    })
}
