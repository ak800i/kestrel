package dev.kestrel.perception

import java.io.File

/**
 * The OCR model files below Kestrel's model folder (design D20 of sub-project 5), with the paths of
 * `models/manifest.json`'s entries `ocr-ppocrv5-det`, `ocr-ppocrv5-latin` and `ocr-ppocrv5-cyrillic`. Each recogniser
 * has its dictionary as a file of its own, because ONNX Runtime's Java API garbles the two characters beyond U+FFFF in
 * the Cyrillic model's `character` metadata.
 */
class OcrFiles(val detector: File, val latin: File, val latinDictionary: File, val cyrillic: File, val cyrillicDictionary: File) {
    override fun toString(): String = "OcrFiles"

    companion object {
        const val DETECTOR = "ocr-ppocrv5-det/ch_PP-OCRv5_det_mobile.onnx"
        const val LATIN = "ocr-ppocrv5-latin/latin_PP-OCRv5_mobile_rec.onnx"
        const val LATIN_DICTIONARY = "ocr-ppocrv5-latin/ppocrv5_latin_dict.txt"
        const val CYRILLIC = "ocr-ppocrv5-cyrillic/cyrillic_PP-OCRv5_rec_mobile.onnx"
        const val CYRILLIC_DICTIONARY = "ocr-ppocrv5-cyrillic/ppocrv5_cyrillic_dict.txt"

        /** The files in the folder [root], which holds every model of the manifest. */
        fun under(root: File): OcrFiles = OcrFiles(
            File(root, DETECTOR), File(root, LATIN), File(root, LATIN_DICTIONARY), File(root, CYRILLIC), File(root, CYRILLIC_DICTIONARY),
        )
    }
}
