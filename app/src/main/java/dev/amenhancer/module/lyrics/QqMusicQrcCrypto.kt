package dev.amenhancer.module.lyrics

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * QQ cloud QRC uses the historical QQMusicDecoder DES bit layout rather
 * than the JCE DESede block layout. This implementation is intentionally
 * equivalent to the decryptors used by LDDC and HyperLyrics-Enhanced.
 */
internal object QrcCrypto {
    private const val KEY = "!@#)(*$%123ZXC!@!@#)(NHL"
    private val schedules by lazy {
        QqMusicTripleDes.decryptionSchedules(KEY.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(hex: String): String = runCatching {
        val normalized = hex.replace(Regex("[^0-9A-Fa-f]"), "")
        if (normalized.isEmpty() || normalized.length % 2 != 0) return@runCatching ""
        val encrypted = ByteArray(normalized.length / 2) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        if (encrypted.size % BLOCK_SIZE != 0) return@runCatching ""
        inflate(QqMusicTripleDes.decrypt(encrypted, schedules))
    }.getOrDefault("")

    private fun inflate(bytes: ByteArray): String {
        val inflater = Inflater(false).apply { setInput(bytes) }
        val output = ByteArrayOutputStream(bytes.size * 2)
        return try {
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                    error("QRC inflater made no progress")
                }
                output.write(buffer, 0, count)
            }
            if (!inflater.finished()) return ""
            output.toString(Charsets.UTF_8.name())
        } finally {
            inflater.end()
            output.close()
        }
    }

    private const val BLOCK_SIZE = 8
}

private object QqMusicTripleDes {
    private const val ENCRYPT = 1
    private const val DECRYPT = 0
    private const val BLOCK_SIZE = 8

    private val sboxes = arrayOf(
        intArrayOf(
            14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
            0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
            4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
            15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
        ),
        intArrayOf(
            15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
            3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
            0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
            13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
        ),
        intArrayOf(
            10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
            13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
            13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
            1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
        ),
        intArrayOf(
            7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
            13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
            10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
            3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
        ),
        intArrayOf(
            2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
            14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
            4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
            11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
        ),
        intArrayOf(
            12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
            10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
            9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
            4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
        ),
        intArrayOf(
            4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
            13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
            1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
            6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
        ),
        intArrayOf(
            13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
            1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
            7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
            2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
        ),
    )

    fun decryptionSchedules(key: ByteArray): List<Array<IntArray>> {
        require(key.size >= 24) { "QQ QRC key must contain 24 bytes" }
        return listOf(
            keySchedule(key.sliceArray(16 until 24), DECRYPT),
            keySchedule(key.sliceArray(8 until 16), ENCRYPT),
            keySchedule(key.sliceArray(0 until 8), DECRYPT),
        )
    }

    fun decrypt(data: ByteArray, schedules: List<Array<IntArray>>): ByteArray {
        require(data.size % BLOCK_SIZE == 0) { "QQ QRC payload must be block aligned" }
        val result = ByteArray(data.size)
        for (offset in data.indices step BLOCK_SIZE) {
            var block = data.copyOfRange(offset, offset + BLOCK_SIZE)
            schedules.forEach { schedule -> block = cryptBlock(block, schedule) }
            block.copyInto(result, offset)
        }
        return result
    }

    private fun bitnum(bytes: ByteArray, bit: Int, targetBit: Int): Int {
        val byteIndex = (bit / 32) * 4 + 3 - (bit % 32) / 8
        if (byteIndex >= bytes.size) return 0
        return (((bytes[byteIndex].toInt() and 0xff) shr (7 - bit % 8)) and 1) shl targetBit
    }

    private fun bitnumRight(value: Int, bit: Int, targetBit: Int): Int =
        ((value ushr (31 - bit)) and 1) shl targetBit

    private fun bitnumLeft(value: Int, bit: Int, targetBit: Int): Int =
        ((value shl bit) and Int.MIN_VALUE) ushr targetBit

    private fun sboxBit(value: Int): Int =
        (value and 32) or ((value and 31) ushr 1) or ((value and 1) shl 4)

    private fun initialPermutation(input: ByteArray): Pair<Int, Int> {
        val left =
            bitnum(input, 57, 31) or bitnum(input, 49, 30) or bitnum(input, 41, 29) or bitnum(input, 33, 28) or
                bitnum(input, 25, 27) or bitnum(input, 17, 26) or bitnum(input, 9, 25) or bitnum(input, 1, 24) or
                bitnum(input, 59, 23) or bitnum(input, 51, 22) or bitnum(input, 43, 21) or bitnum(input, 35, 20) or
                bitnum(input, 27, 19) or bitnum(input, 19, 18) or bitnum(input, 11, 17) or bitnum(input, 3, 16) or
                bitnum(input, 61, 15) or bitnum(input, 53, 14) or bitnum(input, 45, 13) or bitnum(input, 37, 12) or
                bitnum(input, 29, 11) or bitnum(input, 21, 10) or bitnum(input, 13, 9) or bitnum(input, 5, 8) or
                bitnum(input, 63, 7) or bitnum(input, 55, 6) or bitnum(input, 47, 5) or bitnum(input, 39, 4) or
                bitnum(input, 31, 3) or bitnum(input, 23, 2) or bitnum(input, 15, 1) or bitnum(input, 7, 0)
        val right =
            bitnum(input, 56, 31) or bitnum(input, 48, 30) or bitnum(input, 40, 29) or bitnum(input, 32, 28) or
                bitnum(input, 24, 27) or bitnum(input, 16, 26) or bitnum(input, 8, 25) or bitnum(input, 0, 24) or
                bitnum(input, 58, 23) or bitnum(input, 50, 22) or bitnum(input, 42, 21) or bitnum(input, 34, 20) or
                bitnum(input, 26, 19) or bitnum(input, 18, 18) or bitnum(input, 10, 17) or bitnum(input, 2, 16) or
                bitnum(input, 60, 15) or bitnum(input, 52, 14) or bitnum(input, 44, 13) or bitnum(input, 36, 12) or
                bitnum(input, 28, 11) or bitnum(input, 20, 10) or bitnum(input, 12, 9) or bitnum(input, 4, 8) or
                bitnum(input, 62, 7) or bitnum(input, 54, 6) or bitnum(input, 46, 5) or bitnum(input, 38, 4) or
                bitnum(input, 30, 3) or bitnum(input, 22, 2) or bitnum(input, 14, 1) or bitnum(input, 6, 0)
        return left to right
    }

    private fun inversePermutation(left: Int, right: Int): ByteArray = ByteArray(8).also { data ->
        data[3] = inverseByte(left, right, 7, 15, 23, 31)
        data[2] = inverseByte(left, right, 6, 14, 22, 30)
        data[1] = inverseByte(left, right, 5, 13, 21, 29)
        data[0] = inverseByte(left, right, 4, 12, 20, 28)
        data[7] = inverseByte(left, right, 3, 11, 19, 27)
        data[6] = inverseByte(left, right, 2, 10, 18, 26)
        data[5] = inverseByte(left, right, 1, 9, 17, 25)
        data[4] = inverseByte(left, right, 0, 8, 16, 24)
    }

    private fun inverseByte(left: Int, right: Int, a: Int, b: Int, c: Int, d: Int): Byte =
        (bitnumRight(right, a, 7) or bitnumRight(left, a, 6) or
            bitnumRight(right, b, 5) or bitnumRight(left, b, 4) or
            bitnumRight(right, c, 3) or bitnumRight(left, c, 2) or
            bitnumRight(right, d, 1) or bitnumRight(left, d, 0)).toByte()

    private fun roundFunction(state: Int, key: IntArray): Int {
        val first =
            bitnumLeft(state, 31, 0) or ((state and -0x10000000) ushr 1) or bitnumLeft(state, 4, 5) or
                bitnumLeft(state, 3, 6) or ((state and 0x0f000000) ushr 3) or bitnumLeft(state, 8, 11) or
                bitnumLeft(state, 7, 12) or ((state and 0x00f00000) ushr 5) or bitnumLeft(state, 12, 17) or
                bitnumLeft(state, 11, 18) or ((state and 0x000f0000) ushr 7) or bitnumLeft(state, 16, 23)
        val second =
            bitnumLeft(state, 15, 0) or ((state and 0x0000f000) shl 15) or bitnumLeft(state, 20, 5) or
                bitnumLeft(state, 19, 6) or ((state and 0x00000f00) shl 13) or bitnumLeft(state, 24, 11) or
                bitnumLeft(state, 23, 12) or ((state and 0x000000f0) shl 11) or bitnumLeft(state, 28, 17) or
                bitnumLeft(state, 27, 18) or ((state and 0x0000000f) shl 9) or bitnumLeft(state, 0, 23)
        val expanded = intArrayOf(
            (first ushr 24) and 0xff,
            (first ushr 16) and 0xff,
            (first ushr 8) and 0xff,
            (second ushr 24) and 0xff,
            (second ushr 16) and 0xff,
            (second ushr 8) and 0xff,
        )
        expanded.indices.forEach { index -> expanded[index] = expanded[index] xor key[index] }
        val substituted =
            (sboxes[0][sboxBit(expanded[0] ushr 2)] shl 28) or
                (sboxes[1][sboxBit(((expanded[0] and 3) shl 4) or (expanded[1] ushr 4))] shl 24) or
                (sboxes[2][sboxBit(((expanded[1] and 15) shl 2) or (expanded[2] ushr 6))] shl 20) or
                (sboxes[3][sboxBit(expanded[2] and 63)] shl 16) or
                (sboxes[4][sboxBit(expanded[3] ushr 2)] shl 12) or
                (sboxes[5][sboxBit(((expanded[3] and 3) shl 4) or (expanded[4] ushr 4))] shl 8) or
                (sboxes[6][sboxBit(((expanded[4] and 15) shl 2) or (expanded[5] ushr 6))] shl 4) or
                sboxes[7][sboxBit(expanded[5] and 63)]
        return bitnumLeft(substituted, 15, 0) or bitnumLeft(substituted, 6, 1) or
                bitnumLeft(substituted, 19, 2) or bitnumLeft(substituted, 20, 3) or
                bitnumLeft(substituted, 28, 4) or bitnumLeft(substituted, 11, 5) or
                bitnumLeft(substituted, 27, 6) or bitnumLeft(substituted, 16, 7) or
                bitnumLeft(substituted, 0, 8) or bitnumLeft(substituted, 14, 9) or
                bitnumLeft(substituted, 22, 10) or bitnumLeft(substituted, 25, 11) or
                bitnumLeft(substituted, 4, 12) or bitnumLeft(substituted, 17, 13) or
                bitnumLeft(substituted, 30, 14) or bitnumLeft(substituted, 9, 15) or
                bitnumLeft(substituted, 1, 16) or bitnumLeft(substituted, 7, 17) or
                bitnumLeft(substituted, 23, 18) or bitnumLeft(substituted, 13, 19) or
                bitnumLeft(substituted, 31, 20) or bitnumLeft(substituted, 26, 21) or
                bitnumLeft(substituted, 2, 22) or bitnumLeft(substituted, 8, 23) or
                bitnumLeft(substituted, 18, 24) or bitnumLeft(substituted, 12, 25) or
                bitnumLeft(substituted, 29, 26) or bitnumLeft(substituted, 5, 27) or
                bitnumLeft(substituted, 21, 28) or bitnumLeft(substituted, 10, 29) or
                bitnumLeft(substituted, 3, 30) or bitnumLeft(substituted, 24, 31)
    }

    private fun cryptBlock(input: ByteArray, key: Array<IntArray>): ByteArray {
        var (left, right) = initialPermutation(input)
        repeat(15) { round ->
            val previousRight = right
            right = roundFunction(right, key[round]) xor left
            left = previousRight
        }
        left = roundFunction(right, key[15]) xor left
        return inversePermutation(left, right)
    }

    private fun keySchedule(key: ByteArray, mode: Int): Array<IntArray> {
        val schedule = Array(16) { IntArray(6) }
        val shifts = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
        val permC = intArrayOf(56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35)
        val permD = intArrayOf(62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3)
        val compression = intArrayOf(
            13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
            25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
            50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
        )
        var c = (0 until 28).sumOf { bitnum(key, permC[it], 31 - it) }
        var d = (0 until 28).sumOf { bitnum(key, permD[it], 31 - it) }
        repeat(16) { round ->
            c = ((c shl shifts[round]) or (c ushr (28 - shifts[round]))) and -0x10
            d = ((d shl shifts[round]) or (d ushr (28 - shifts[round]))) and -0x10
            val target = if (mode == DECRYPT) 15 - round else round
            repeat(24) { bit ->
                schedule[target][bit / 8] = schedule[target][bit / 8] or
                    bitnumRight(c, compression[bit], 7 - bit % 8)
            }
            for (bit in 24 until 48) {
                schedule[target][bit / 8] = schedule[target][bit / 8] or
                    bitnumRight(d, compression[bit] - 27, 7 - bit % 8)
            }
        }
        return schedule
    }
}
