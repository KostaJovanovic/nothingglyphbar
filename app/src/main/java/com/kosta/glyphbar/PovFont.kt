package com.kosta.glyphbar

/**
 * A 6-pixel-tall font, because the bar is exactly 6 zones tall.
 *
 * Glyphs are authored as rows so they're readable in source; they get
 * transposed to columns at load. Row 0 is the top of the bar (A1).
 * Blank leading/trailing columns are trimmed, so "I" and "!" come out narrow
 * instead of padded to a fixed cell.
 */
object PovFont {

    private val ROWS: Map<Char, List<String>> = mapOf(
        'A' to listOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#"),
        'B' to listOf("####.", "#...#", "####.", "#...#", "#...#", "####."),
        'C' to listOf(".####", "#....", "#....", "#....", "#....", ".####"),
        'D' to listOf("####.", "#...#", "#...#", "#...#", "#...#", "####."),
        'E' to listOf("#####", "#....", "####.", "#....", "#....", "#####"),
        'F' to listOf("#####", "#....", "####.", "#....", "#....", "#...."),
        'G' to listOf(".####", "#....", "#..##", "#...#", "#...#", ".####"),
        'H' to listOf("#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'I' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "#####"),
        'J' to listOf("....#", "....#", "....#", "....#", "#...#", ".###."),
        'K' to listOf("#...#", "#..#.", "###..", "#..#.", "#...#", "#...#"),
        'L' to listOf("#....", "#....", "#....", "#....", "#....", "#####"),
        'M' to listOf("#...#", "##.##", "#.#.#", "#...#", "#...#", "#...#"),
        'N' to listOf("#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#"),
        'O' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", ".###."),
        'P' to listOf("####.", "#...#", "####.", "#....", "#....", "#...."),
        'Q' to listOf(".###.", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
        'R' to listOf("####.", "#...#", "####.", "#.#..", "#..#.", "#...#"),
        'S' to listOf(".####", "#....", ".###.", "....#", "....#", "####."),
        'T' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'U' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'V' to listOf("#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
        'W' to listOf("#...#", "#...#", "#...#", "#.#.#", "##.##", "#...#"),
        'X' to listOf("#...#", ".#.#.", "..#..", "..#..", ".#.#.", "#...#"),
        'Y' to listOf("#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
        'Z' to listOf("#####", "....#", "...#.", "..#..", ".#...", "#####"),

        '0' to listOf(".###.", "#...#", "#..##", "##..#", "#...#", ".###."),
        '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", ".###."),
        '2' to listOf(".###.", "#...#", "...#.", "..#..", ".#...", "#####"),
        '3' to listOf("####.", "....#", ".###.", "....#", "....#", "####."),
        '4' to listOf("#..#.", "#..#.", "#..#.", "#####", "...#.", "...#."),
        '5' to listOf("#####", "#....", "####.", "....#", "....#", "####."),
        '6' to listOf(".###.", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to listOf("#####", "....#", "...#.", "..#..", "..#..", "..#.."),
        '8' to listOf(".###.", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", ".###."),

        '!' to listOf("..#..", "..#..", "..#..", "..#..", ".....", "..#.."),
        '?' to listOf(".###.", "#...#", "...#.", "..#..", ".....", "..#.."),
        '.' to listOf(".....", ".....", ".....", ".....", ".....", "..#.."),
        ',' to listOf(".....", ".....", ".....", ".....", "..#..", ".#..."),
        ':' to listOf(".....", "..#..", ".....", ".....", "..#..", "....."),
        '-' to listOf(".....", ".....", ".###.", ".....", ".....", "....."),
        '+' to listOf(".....", "..#..", ".###.", "..#..", ".....", "....."),
        '=' to listOf(".....", ".###.", ".....", ".###.", ".....", "....."),
        '/' to listOf("....#", "...#.", "..#..", "..#..", ".#...", "#...."),
        '\'' to listOf("..#..", "..#..", ".....", ".....", ".....", "....."),
        '"' to listOf(".#.#.", ".#.#.", ".....", ".....", ".....", "....."),
        '(' to listOf("...#.", "..#..", "..#..", "..#..", "..#..", "...#."),
        ')' to listOf(".#...", "..#..", "..#..", "..#..", "..#..", ".#..."),
        '<' to listOf("...#.", "..#..", ".#...", ".#...", "..#..", "...#."),
        '>' to listOf(".#...", "..#..", "...#.", "...#.", "..#..", ".#..."),
        '*' to listOf("#.#.#", ".###.", "#####", ".###.", "#.#.#", "....."),
        '#' to listOf(".#.#.", "#####", ".#.#.", "#####", ".#.#.", "....."),
        '@' to listOf(".###.", "#...#", "#.###", "#.#.#", "#....", ".###."),
        '%' to listOf("#...#", "...#.", "..#..", "..#..", ".#...", "#...#"),
        '&' to listOf(".##..", "#..#.", ".##..", "#..#.", "#...#", ".##.#"),
        // A heart, because a POV wand should be able to draw one.
        '~' to listOf(".#.#.", "#####", "#####", ".###.", ".###.", "..#.."),
    )

    /** Space is a gap, not a glyph — no columns of its own beyond the run. */
    private const val SPACE_COLUMNS = 3

    /** Blank columns inserted between characters. */
    private const val KERNING = 1

    /** Column bitmask: bit r set => zone r lit. Bit 0 = top. */
    private fun columnsFor(rows: List<String>): List<Int> {
        val width = rows.maxOf { it.length }
        val cols = (0 until width).map { c ->
            var mask = 0
            rows.forEachIndexed { r, row ->
                if (c < row.length && row[c] == '#') mask = mask or (1 shl r)
            }
            mask
        }
        // Trim blank edges so glyph widths are natural.
        val first = cols.indexOfFirst { it != 0 }
        val last = cols.indexOfLast { it != 0 }
        return if (first < 0) emptyList() else cols.subList(first, last + 1)
    }

    private val CACHE: Map<Char, List<Int>> = ROWS.mapValues { columnsFor(it.value) }

    val supported: String = (ROWS.keys.sorted().joinToString("")) + " "

    /** Render text to column bitmasks, with kerning and word gaps. */
    fun columns(text: String): List<Int> {
        val out = mutableListOf<Int>()
        text.uppercase().forEach { ch ->
            if (ch == ' ') {
                repeat(SPACE_COLUMNS) { out.add(0) }
                return@forEach
            }
            val glyph = CACHE[ch] ?: return@forEach
            if (out.isNotEmpty()) repeat(KERNING) { out.add(0) }
            out.addAll(glyph)
        }
        return out
    }

    /** Turn a column bitmask into a bar frame at the given brightness. */
    fun frameOfColumn(mask: Int, level: Int = MAX_LIGHT): Frame =
        IntArray(ZONE_COUNT) { if ((mask shr it) and 1 == 1) level else 0 }

    fun frames(text: String, level: Int = MAX_LIGHT): List<Frame> =
        columns(text).map { frameOfColumn(it, level) }
}
