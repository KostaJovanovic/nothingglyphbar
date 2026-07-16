package com.kosta.glyphbar

import android.util.Base64
import android.util.Log
import java.io.InputStream
import java.util.zip.Inflater
import kotlin.math.roundToInt

private const val TAG = "Glyphtone"

/** Glyph Composer writes one CSV line per 16.666ms — i.e. 60 Hz. */
private const val COMPOSER_FRAME_MS = 16.666f

/** Composer brightness is 12-bit. */
private const val COMPOSER_MAX = 4095f

/**
 * Reads Nothing "Glyph Composer" compositions out of .ogg files.
 *
 * Format (reverse-engineered by the custom-nothing-glyph-tools project, and
 * matching what we see on device):
 *   AUTHOR  = base64(zlib(CSV))  — the light data, one line per 16.666ms,
 *             one column per zone, values 0..4095, CRLF, trailing comma.
 *   CUSTOM2 = plain zone-count string, e.g. "6cols" for the Phone (4a).
 *   CUSTOM1 = Composer timeline dots. Not light data — ignored here.
 *
 * Note this only works for compositions authored in Glyph Composer. The
 * ringtones that ship with the phone carry no glyph tags at all; the built-in
 * animations live as CSV resources inside a privileged system APK and are not
 * reachable from a third-party app.
 */
object Glyphtone {

    data class Parsed(
        val title: String,
        val zoneCount: Int,
        val frames: List<Frame>,
        /** Non-fatal notes worth surfacing (e.g. zone-count remapping). */
        val notes: List<String>,
    )

    sealed interface Result {
        data class Ok(val parsed: Parsed) : Result
        data class Err(val message: String) : Result
    }

    fun parse(input: InputStream, fallbackName: String): Result {
        val tags = try {
            readVorbisComments(input)
        } catch (e: Exception) {
            Log.e(TAG, "ogg parse failed", e)
            return Result.Err("Couldn't read this as an Ogg file: ${e.message}")
        } ?: return Result.Err("No Vorbis comment header found — is this really an .ogg?")

        val author = tags["AUTHOR"]
            ?: return Result.Err(
                "No glyph data in this file. Only Glyph Composer compositions carry it — " +
                    "the ringtones that ship with the phone don't, and neither do ordinary " +
                    ".ogg files."
            )

        val csv = try {
            inflate(decodeBase64Loose(author))
        } catch (e: Exception) {
            Log.e(TAG, "inflate failed", e)
            return Result.Err("The AUTHOR tag isn't valid zlib+base64: ${e.message}")
        }

        val notes = mutableListOf<String>()

        // CUSTOM2 looks like "6cols" / "33cols". Trust the CSV if they disagree.
        val declared = tags["CUSTOM2"]?.filter { it.isDigit() }?.toIntOrNull()

        val rows = csv.split('\n')
            .map { it.trim().trimEnd(',') }
            .filter { it.isNotEmpty() }
        if (rows.isEmpty()) return Result.Err("Glyph data decoded but contained no rows.")

        val cols = rows.first().split(',').size
        if (declared != null && declared != cols) {
            notes += "File declares ${declared}cols but the data has $cols — using $cols."
        }

        val frames = rows.mapNotNull { row ->
            val cells = row.split(',')
            if (cells.isEmpty()) return@mapNotNull null
            val values = cells.map { it.trim().toFloatOrNull() ?: 0f }
            mapToBar(values, cols)
        }
        if (frames.isEmpty()) return Result.Err("Glyph data decoded but no frames parsed.")

        if (cols != ZONE_COUNT) {
            notes += "Authored for a $cols-zone device; downmixed onto the (4a)'s 6-zone bar."
        }

        return Result.Ok(
            Parsed(
                title = tags["TITLE"]?.takeIf { it.isNotBlank() } ?: fallbackName,
                zoneCount = cols,
                frames = frames,
                notes = notes,
            )
        )
    }

    /**
     * Map a composition row onto our 6 zones.
     *
     * A 6-column file maps 1:1. Anything else was authored for a different
     * Glyph layout (33-zone Phone (2), 36-zone (3a)...), so average each
     * contiguous slice — cruder than a real layout mapping, but it keeps
     * other-device compositions watchable instead of rejecting them.
     */
    private fun mapToBar(values: List<Float>, cols: Int): Frame {
        if (cols == ZONE_COUNT) {
            return IntArray(ZONE_COUNT) { i ->
                ((values.getOrElse(i) { 0f } / COMPOSER_MAX) * MAX_LIGHT).roundToInt().coerceIn(0, MAX_LIGHT)
            }
        }
        return IntArray(ZONE_COUNT) { z ->
            val lo = (z * cols) / ZONE_COUNT
            val hi = (((z + 1) * cols) / ZONE_COUNT).coerceAtLeast(lo + 1)
            var sum = 0f
            var n = 0
            for (k in lo until hi.coerceAtMost(values.size)) {
                sum += values[k]; n++
            }
            if (n == 0) 0 else (((sum / n) / COMPOSER_MAX) * MAX_LIGHT).roundToInt().coerceIn(0, MAX_LIGHT)
        }
    }

    /** Composer writes ~60fps; expose it as Keyframes the player understands. */
    fun toKeyframes(parsed: Parsed): Keyframes =
        Keyframes(frames = parsed.frames, stepMs = COMPOSER_FRAME_MS.roundToInt(), loop = true)

    // -------------------------------------------------------------- ogg

    /**
     * Walk Ogg pages and reassemble the Vorbis comment header.
     *
     * A real AUTHOR tag is large, so the comment packet spans multiple pages —
     * reading only from the file start silently truncates it. Concatenate page
     * payloads until the packet ends (a segment < 255 terminates it).
     */
    private fun readVorbisComments(input: InputStream): Map<String, String>? {
        val data = input.readBytes()
        var pos = 0
        val packet = java.io.ByteArrayOutputStream()
        var collecting = false

        while (pos + 27 <= data.size) {
            if (!(data[pos] == 'O'.code.toByte() && data[pos + 1] == 'g'.code.toByte() &&
                    data[pos + 2] == 'g'.code.toByte() && data[pos + 3] == 'S'.code.toByte())
            ) {
                pos++
                continue
            }
            val segCount = data[pos + 26].toInt() and 0xFF
            val segTableAt = pos + 27
            if (segTableAt + segCount > data.size) break

            var payloadLen = 0
            val segs = IntArray(segCount) { data[segTableAt + it].toInt() and 0xFF }
            segs.forEach { payloadLen += it }

            var payloadAt = segTableAt + segCount
            if (payloadAt + payloadLen > data.size) break

            // Walk this page's packets segment-by-segment.
            var segIdx = 0
            while (segIdx < segCount) {
                var packetLen = 0
                var lacing: Int
                do {
                    lacing = segs[segIdx]
                    packetLen += lacing
                    segIdx++
                } while (lacing == 255 && segIdx < segCount)

                val chunk = data.copyOfRange(payloadAt, (payloadAt + packetLen).coerceAtMost(data.size))
                payloadAt += packetLen

                if (!collecting && chunk.size >= 7 && chunk[0] == 0x03.toByte() &&
                    String(chunk, 1, 6, Charsets.US_ASCII) == "vorbis"
                ) {
                    collecting = true
                    packet.write(chunk)
                    // A packet continued across pages ends with a 255 lacing.
                    if (lacing != 255) return parseComments(packet.toByteArray())
                } else if (collecting) {
                    packet.write(chunk)
                    if (lacing != 255) return parseComments(packet.toByteArray())
                }
            }
            pos = payloadAt
        }
        return if (collecting && packet.size() > 0) parseComments(packet.toByteArray()) else null
    }

    private fun parseComments(packet: ByteArray): Map<String, String> {
        // layout: 0x03 "vorbis" | u32 vendorLen | vendor | u32 count | count*(u32 len | KEY=VALUE)
        var p = 7
        fun u32(): Int {
            if (p + 4 > packet.size) throw IllegalStateException("truncated comment header")
            val v = (packet[p].toInt() and 0xFF) or
                ((packet[p + 1].toInt() and 0xFF) shl 8) or
                ((packet[p + 2].toInt() and 0xFF) shl 16) or
                ((packet[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }

        val vendorLen = u32()
        p += vendorLen
        val count = u32()
        val out = mutableMapOf<String, String>()
        repeat(count.coerceAtMost(512)) {
            val len = u32()
            if (len < 0 || p + len > packet.size) return@repeat
            val kv = String(packet, p, len, Charsets.UTF_8)
            p += len
            val eq = kv.indexOf('=')
            if (eq > 0) out[kv.substring(0, eq).uppercase()] = kv.substring(eq + 1)
        }
        return out
    }

    /** Composer strips '=' padding and wraps at 76 chars. Undo both. */
    private fun decodeBase64Loose(s: String): ByteArray {
        val clean = s.filterNot { it.isWhitespace() }
        val padded = when (clean.length % 4) {
            2 -> "$clean=="
            3 -> "$clean="
            else -> clean
        }
        return Base64.decode(padded, Base64.DEFAULT)
    }

    private fun inflate(bytes: ByteArray): String {
        val inf = Inflater()
        inf.setInput(bytes)
        val buf = ByteArray(1 shl 16)
        val out = java.io.ByteArrayOutputStream()
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
        } finally {
            inf.end()
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
