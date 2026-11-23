package dev.kestrel.eval.device

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/**
 * A harmless test photo (design D5 of sub-project 6; the port of scripts\lib\Photos.ps1): a card of one colour with as
 * many white bars as its number, as a JPEG with Kestrel's own EXIF segment right after the JPEG's start, from which
 * Android's media scanner takes DATE_TAKEN: DateTimeOriginal and OffsetTimeOriginal. The bars need no font, so the card
 * draws the same on every computer.
 */
object PhotoCard {
    private val COLOURS = listOf(Color(70, 130, 180), Color(255, 140, 0), Color(46, 139, 87), Color(147, 112, 219))
    private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /** The offset of `date +%z` (+0100) in EXIF's form (+01:00). */
    fun exifOffset(zone: String): String {
        val match = Regex("^([+-])(\\d{2})(\\d{2})$").matchEntire(zone.trim()) ?: throw IllegalArgumentException("Not an offset of 'date +%z': '$zone'")
        val (sign, hours, minutes) = match.destructured
        return "$sign$hours:$minutes"
    }

    fun jpeg(number: Int, taken: LocalDateTime, offset: String): ByteArray {
        System.setProperty("java.awt.headless", "true")
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = COLOURS[(number - 1).mod(COLOURS.size)]
            graphics.fillRect(0, 0, WIDTH, HEIGHT)
            graphics.color = Color.WHITE
            val left = (WIDTH - (number * 2 - 1) * BAR) / 2
            for (bar in 0 until number) graphics.fillRect(left + bar * 2 * BAR, HEIGHT / 4, BAR, HEIGHT / 2)
        } finally {
            graphics.dispose()
        }
        val bytes = ByteArrayOutputStream()
        check(ImageIO.write(image, "jpg", bytes)) { "ImageIO has no JPEG writer." }
        val jpeg = bytes.toByteArray()
        check(jpeg.size >= 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "ImageIO did not write a JPEG." }
        return jpeg.copyOfRange(0, 2) + exifSegment(taken, offset) + jpeg.copyOfRange(2, jpeg.size)
    }

    /**
     * The APP1 segment of Kestrel's EXIF writer, byte for byte as Photos.ps1 wrote it: a little-endian TIFF header, IFD0
     * with only the pointer to the Exif IFD (tag 0x8769), and the Exif IFD with DateTimeOriginal (0x9003,
     * "YYYY:MM:DD HH:MM:SS") and OffsetTimeOriginal (0x9011, "+HH:MM"), both ASCII with their final zero byte.
     */
    fun exifSegment(taken: LocalDateTime, offset: String): ByteArray {
        require(Regex("^[+-]\\d{2}:\\d{2}$").matches(offset)) { "Not an EXIF offset: '$offset'" }
        val date = (taken.format(EXIF_DATE) + "\u0000").toByteArray(Charsets.US_ASCII)
        val zone = (offset + "\u0000").toByteArray(Charsets.US_ASCII)
        val exifIfd = 26
        val dateAt = exifIfd + 2 + 2 * 12 + 4
        val zoneAt = dateAt + date.size
        var length = zoneAt + zone.size
        // TIFF values start on even offsets.
        if (length % 2 == 1) length++
        val tiff = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        tiff.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        tiff.putShort(1).putShort(0x8769.toShort()).putShort(4).putInt(1).putInt(exifIfd).putInt(0)
        tiff.putShort(2)
        tiff.putShort(0x9003.toShort()).putShort(2).putInt(date.size).putInt(dateAt)
        tiff.putShort(0x9011.toShort()).putShort(2).putInt(zone.size).putInt(zoneAt)
        tiff.putInt(0).put(date).put(zone)
        val body = "Exif".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0) + tiff.array()
        val size = body.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (size shr 8).toByte(), (size and 0xFF).toByte()) + body
    }

    private const val WIDTH = 640
    private const val HEIGHT = 480
    private const val BAR = 40
}
