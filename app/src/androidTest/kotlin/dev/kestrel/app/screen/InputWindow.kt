package dev.kestrel.app.screen

import android.graphics.Rect

/**
 * One window of Android's input dispatcher, as a line of `dumpsys input` lists it, with what decides whether it obscures
 * a window below it: a touch on a window that such a window covers carries `MotionEvent.FLAG_WINDOW_IS_OBSCURED` or
 * `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`, and a view with `filterTouchesWhenObscured`, such as the conversation screen's
 * (design 12.2 of sub-project 7), drops the first.
 */
internal data class InputWindow(
    val name: String,
    val displayId: Int,
    val ownerUid: Int,
    val alpha: Float,
    val config: Set<String>,
    val frame: Rect,
) {
    /**
     * Whether this window, which the input dispatcher lists above [below], obscures it, by the rules of the dispatcher's
     * `canBeObscuredBy` and `isWindowObscuredLocked`: it is visible, not both transparent and untouchable, another app's
     * and no trusted overlay, on the same display, and its frame overlaps [below]'s.
     */
    fun obscures(below: InputWindow): Boolean =
        NOT_VISIBLE !in config &&
            !(alpha == 0f && NOT_TOUCHABLE in config) &&
            ownerUid != below.ownerUid &&
            TRUSTED_OVERLAY !in config &&
            displayId == below.displayId &&
            Rect.intersects(frame, below.frame)

    companion object {
        private const val NOT_VISIBLE = "NOT_VISIBLE"
        private const val NOT_TOUCHABLE = "NOT_TOUCHABLE"
        private const val TRUSTED_OVERLAY = "TRUSTED_OVERLAY"

        // A window's line, such as "9: name=be8cdaa Splash Screen dev.kestrel, id=18112, displayId=0, inputConfig=NOT_FOCUSABLE |
        // NOT_TOUCHABLE, alpha=0.666504, frame=[0,0][1080,2400], ..., ownerUid=10199, ...". The lists of monitors name no frame.
        private val LINE = Regex("""^\s*(\d+): name=(.*?), id=\d+, displayId=(-?\d+), inputConfig=([^,]*), alpha=([\d.]+), frame=\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)].*\bownerUid=(\d+)""")

        /** Android's lists of input windows in [dump], the output of `dumpsys input`, each top first. */
        fun lists(dump: String): List<List<InputWindow>> {
            val lists = mutableListOf<MutableList<InputWindow>>()
            for (line in dump.lineSequence()) {
                val match = LINE.find(line) ?: continue
                val values = match.groupValues
                // Each list numbers its windows from 0.
                if (values[1] == "0" || lists.isEmpty()) lists += mutableListOf<InputWindow>()
                lists.last() += InputWindow(
                    name = values[2],
                    displayId = values[3].toInt(),
                    ownerUid = values[10].toInt(),
                    alpha = values[5].toFloat(),
                    config = values[4].split('|').map { it.trim() }.toSet(),
                    frame = Rect(values[6].toInt(), values[7].toInt(), values[8].toInt(), values[9].toInt()),
                )
            }
            return lists
        }
    }
}
