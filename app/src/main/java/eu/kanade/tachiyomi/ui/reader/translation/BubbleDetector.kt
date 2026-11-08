package eu.kanade.tachiyomi.ui.reader.translation

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Pure Kotlin (no Android imports) so it can be unit tested on a plain JVM.
// Finds speech bubbles / caption boxes and the text blocks inside them, so OCR only has to read
// small clean crops instead of a whole noisy page.

internal data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width get() = x1 - x0 + 1
    val height get() = y1 - y0 + 1
    fun union(o: Box) = Box(min(x0, o.x0), min(y0, o.y0), max(x1, o.x1), max(y1, o.y1))
}

/** 8-bit grayscale image. */
internal class Gray(val w: Int, val h: Int, val px: ByteArray) {
    fun at(i: Int): Int = px[i].toInt() and 0xFF
    fun at(x: Int, y: Int): Int = px[y * w + x].toInt() and 0xFF

    fun inverted(): Gray {
        val out = ByteArray(px.size)
        for (i in px.indices) out[i] = (255 - at(i)).toByte()
        return Gray(w, h, out)
    }

    /** Bilinear resize. */
    fun scaled(factor: Float): Gray {
        val nw = max(1, (w * factor).roundToInt())
        val nh = max(1, (h * factor).roundToInt())
        if (nw == w && nh == h) return this
        val out = ByteArray(nw * nh)
        val sx = w.toFloat() / nw
        val sy = h.toFloat() / nh
        for (y in 0 until nh) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(h - 1, y0 + 1)
            val wy = fy - y0
            for (x in 0 until nw) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(w - 1, x0 + 1)
                val wx = fx - x0
                val top = at(x0, y0) * (1 - wx) + at(x1, y0) * wx
                val bot = at(x0, y1) * (1 - wx) + at(x1, y1) * wx
                out[y * nw + x] = (top * (1 - wy) + bot * wy).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return Gray(nw, nh, out)
    }
}

internal class Labels(
    val lab: IntArray,
    val count: Int,
    val area: IntArray,
    val x0: IntArray,
    val y0: IntArray,
    val x1: IntArray,
    val y1: IntArray,
) {
    fun box(id: Int) = Box(x0[id], y0[id], x1[id], y1[id])
}

internal class Glyph(val box: Box, val id: Int)

internal class TextBlock(
    val box: Box,
    val glyphs: List<Glyph>,
    val vertical: Boolean,
    /** Row/column band counts were too close to call, so both reading directions are worth trying. */
    val ambiguous: Boolean,
    /** Typical character size in pixels (75th percentile of glyph size). */
    val size: Int,
    /** Index of the bubble candidate that owns this block (used to wipe strokes that belong to something else). */
    val owner: Int,
)

internal class Bubble(val white: Box, val fill: Float, val fillFull: Float, val blocks: List<TextBlock>) {
    val isRect get() = fillFull >= 0.9f
}

internal class Detection(val bubbles: List<Bubble>, val dark: Labels, val owner: IntArray)

internal class Cut(val y: Int, val clean: Boolean)

internal object BubbleDetector {
    const val WHITE = 205 // gray values at or above this count as "bubble paper"
    const val DARK = 125 // gray values below this count as "ink"

    fun labelComponents(mask: BooleanArray, w: Int, h: Int, eight: Boolean): Labels {
        val lab = IntArray(w * h)
        val parent = IntArray(w * h / 2 + 3)
        var next = 1
        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                if (!mask[i]) continue
                val a = if (x > 0) lab[i - 1] else 0
                val b = if (y > 0) lab[i - w] else 0
                val c = if (eight && x > 0 && y > 0) lab[i - w - 1] else 0
                val d = if (eight && x < w - 1 && y > 0) lab[i - w + 1] else 0
                var m = 0
                if (a != 0) m = a
                if (b != 0 && (m == 0 || find(b) < find(m))) m = b
                if (c != 0 && (m == 0 || find(c) < find(m))) m = c
                if (d != 0 && (m == 0 || find(d) < find(m))) m = d
                if (m == 0) {
                    parent[next] = next
                    lab[i] = next++
                    continue
                }
                val r = find(m)
                lab[i] = r
                for (v in intArrayOf(a, b, c, d)) {
                    if (v == 0) continue
                    val rv = find(v)
                    if (rv != r) {
                        if (rv < r) parent[r] = rv else parent[rv] = r
                    }
                }
            }
        }
        val idOf = IntArray(next)
        var n = 0
        for (k in 1 until next) if (find(k) == k) idOf[k] = ++n
        val area = IntArray(n + 1)
        val x0 = IntArray(n + 1) { Int.MAX_VALUE }
        val y0 = IntArray(n + 1) { Int.MAX_VALUE }
        val x1 = IntArray(n + 1) { -1 }
        val y1 = IntArray(n + 1) { -1 }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val v = lab[i]
                if (v == 0) continue
                val id = idOf[find(v)]
                lab[i] = id
                area[id]++
                if (x < x0[id]) x0[id] = x
                if (x > x1[id]) x1[id] = x
                if (y < y0[id]) y0[id] = y
                if (y > y1[id]) y1[id] = y
            }
        }
        return Labels(lab, n, area, x0, y0, x1, y1)
    }

    private fun median(list: List<Int>): Int {
        if (list.isEmpty()) return 0
        val s = list.sorted()
        return s[s.size shr 1]
    }

    /** Groups glyphs whose boxes are within [thr] pixels of each other (glyphs -> text blocks). */
    fun cluster(items: List<Glyph>, thr: Int): List<List<Glyph>> {
        val n = items.size
        val parent = IntArray(n) { it }
        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        for (i in 0 until n) {
            val a = items[i].box
            for (j in i + 1 until n) {
                val b = items[j].box
                val dx = max(0, max(a.x0, b.x0) - min(a.x1, b.x1))
                val dy = max(0, max(a.y0, b.y0) - min(a.y1, b.y1))
                if (dx <= thr && dy <= thr) {
                    val ri = find(i)
                    val rj = find(j)
                    if (ri != rj) parent[rj] = ri
                }
            }
        }
        val groups = LinkedHashMap<Int, MutableList<Glyph>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { ArrayList() }.add(items[i])
        return groups.values.toList()
    }

    /** Counts text "bands" along each axis: horizontal text has few row bands, vertical text few column bands. */
    fun bands(g: Gray, r: Box, minGap: Int): Pair<Int, Int> {
        val rw = r.width
        val rh = r.height
        val rows = IntArray(rh)
        val cols = IntArray(rw)
        for (y in r.y0..r.y1) {
            for (x in r.x0..r.x1) {
                if (g.at(x, y) < DARK + 20) {
                    rows[y - r.y0]++
                    cols[x - r.x0]++
                }
            }
        }
        fun count(arr: IntArray): Int {
            var n = 0
            var inInk = false
            var gap = 0
            for (v in arr) {
                if (v > 0) {
                    if (!inInk) {
                        n++
                        inInk = true
                    }
                    gap = 0
                } else if (inInk && ++gap >= minGap) {
                    inInk = false
                }
            }
            return n
        }
        return count(rows) to count(cols)
    }

    private class Cand(val id: Int, val area: Int, val fill: Float, val white: Box) {
        val glyphs = ArrayList<Glyph>()
        var ink = 0L
    }

    fun detect(g: Gray): Detection {
        val w = g.w
        val h = g.h
        val n = w * h
        val whiteMask = BooleanArray(n)
        val darkMask = BooleanArray(n)
        for (i in 0 until n) {
            val v = g.at(i)
            if (v >= WHITE) whiteMask[i] = true else if (v < DARK) darkMask[i] = true
        }
        val wc = labelComponents(whiteMask, w, h, false)
        val dc = labelComponents(darkMask, w, h, true)

        val minDim = max(26, (w * 0.025).roundToInt())
        val minArea = (minDim * minDim * 0.7).roundToInt()
        val sliceArea = w.toLong() * h

        val candIdx = IntArray(wc.count + 1) { -1 }
        val cands = ArrayList<Cand>()
        for (id in 1..wc.count) {
            val area = wc.area[id]
            if (area < minArea || area > sliceArea * 0.45) continue
            val bb = wc.box(id)
            if (bb.width < minDim || bb.height < minDim) continue
            if (bb.width > w * 0.85 && bb.height > h * 0.85) continue
            val fill = area.toFloat() / (bb.width.toFloat() * bb.height)
            if (fill < 0.42f) continue
            candIdx[id] = cands.size
            cands.add(Cand(id, area, fill, bb))
        }

        // assign small dark components (glyph strokes) to the candidate they sit inside
        val owner = IntArray(dc.count + 1) { -1 }
        val pts = IntArray(16)
        for (id in 1..dc.count) {
            if (dc.area[id] < 2) continue
            val x0 = dc.x0[id]
            val y0 = dc.y0[id]
            val x1 = dc.x1[id]
            val y1 = dc.y1[id]
            val gw = x1 - x0 + 1
            val gh = y1 - y0 + 1
            val cx = (x0 + x1) shr 1
            val cy = (y0 + y1) shr 1
            val d = 2
            val sx = intArrayOf(x0 - d, x1 + d, x0 - d, x1 + d, cx, cx, x0 - d, x1 + d)
            val sy = intArrayOf(y0 - d, y0 - d, y1 + d, y1 + d, y0 - d, y1 + d, cy, cy)
            var best = 0
            var bv = 0
            for (k in 0 until 8) {
                val px = sx[k]
                val py = sy[k]
                pts[k] = if (px < 0 || py < 0 || px >= w || py >= h) 0 else wc.lab[py * w + px]
            }
            for (k in 0 until 8) {
                val l = pts[k]
                if (l == 0 || candIdx[l] < 0) continue
                var v = 0
                for (m in 0 until 8) if (pts[m] == l) v++
                if (v > bv) {
                    bv = v
                    best = l
                }
            }
            if (bv < 2) continue
            val ci = candIdx[best]
            val c = cands[ci]
            // a real glyph sits completely inside its bubble; strokes that cross the outline are artwork
            if (x0 < c.white.x0 - 1 || y0 < c.white.y0 - 1 || x1 > c.white.x1 + 1 || y1 > c.white.y1 + 1) continue
            if (gw > c.white.width * 0.9 || gh > c.white.height * 0.9) continue
            owner[id] = ci
            c.glyphs.add(Glyph(Box(x0, y0, x1, y1), id))
            c.ink += dc.area[id]
        }

        val bubbles = ArrayList<Bubble>()
        for ((ci, c) in cands.withIndex()) {
            if (c.glyphs.isEmpty()) continue
            val inkRatio = c.ink.toFloat() / c.area
            if (inkRatio < 0.003f || inkRatio > 0.4f) continue
            val sizes = c.glyphs.map { max(it.box.width, it.box.height) }
            val med = median(sizes)
            if (sizes.max() < 9) continue
            val keep = c.glyphs.filterIndexed { i, _ -> sizes[i] >= max(3, (med * 0.25f).roundToInt()) || sizes[i] >= 6 }
            if (keep.isEmpty()) continue
            val thr = min(max((med * 1.6f).roundToInt(), 8), (w * 0.12f).roundToInt())
            val blocks = ArrayList<TextBlock>()
            for (grp in cluster(keep, thr)) {
                var u = grp[0].box
                for (q in grp) u = u.union(q.box)
                val gl = grp.filter { max(it.box.width, it.box.height) >= 6 }
                if (gl.isEmpty() || max(u.width, u.height) < 14) continue
                val gsz = gl.map { max(it.box.width, it.box.height) }.sorted()
                val mg = gsz[gsz.size shr 1]
                val size = gsz[min(gsz.size - 1, (gsz.size * 0.75f).toInt())]
                val (rowBands, colBands) = bands(g, u, max(2, (mg * 0.07f).roundToInt()))
                val vertical = colBands < rowBands
                val ambiguous = abs(colBands - rowBands) <= 1 && rowBands > 0 && colBands > 0 && u.width > 25 && u.height > 25
                blocks.add(TextBlock(u, gl, vertical, ambiguous, size, ci))
            }
            // fillFull counts the text holes too: ~1.0 for rectangles, ~0.79 for ellipses
            val fillFull = min(1f, (c.area + c.ink).toFloat() / (c.white.width.toFloat() * c.white.height))
            if (blocks.isNotEmpty()) bubbles.add(Bubble(c.white, c.fill, fillFull, blocks))
        }
        return Detection(bubbles, dc, owner)
    }

    /**
     * Cut tall images where a row is uniform across the whole width (panel gutters).
     * clean=false means no real gutter was found near the target, so neighbouring slices must overlap.
     */
    fun cutRows(g: Gray, target: Int): List<Cut> {
        val w = g.w
        val h = g.h
        val cuts = ArrayList<Cut>()
        cuts.add(Cut(0, true))
        var pos = 0
        val bins = IntArray(32)
        while (h - pos > target * 1.25f) {
            val lo = pos + (target * 0.7f).roundToInt()
            val hi = min(h - 1, pos + (target * 1.2f).roundToInt())
            var best = pos + target
            var bestScore = Float.MAX_VALUE
            var bestClean = false
            for (y in lo..hi) {
                bins.fill(0)
                val row = y * w
                var n = 0
                var x = 0
                while (x < w) {
                    bins[g.at(row + x) shr 3]++
                    n++
                    x += 2
                }
                var mode = 0
                for (i in 1 until 32) if (bins[i] > bins[mode]) mode = i
                val mv = mode * 8 + 4
                var off = 0
                x = 0
                while (x < w) {
                    if (abs(g.at(row + x) - mv) > 24) off++
                    x += 2
                }
                val frac = off.toFloat() / n
                val score = frac + abs(y - (pos + target)) * 0.00002f
                if (score < bestScore) {
                    bestScore = score
                    best = y
                    bestClean = frac <= 0.02f
                }
            }
            cuts.add(Cut(best, bestClean))
            pos = best
        }
        cuts.add(Cut(h, true))
        return cuts
    }

    private const val BORDER = 12

    /** Crop of a text block for OCR: strokes owned by other things (bubble outline, artwork) are wiped to white. */
    fun cropForOcr(g: Gray, block: TextBlock, dark: Labels, owner: IntArray, pad: Int = 10): Gray {
        val x0 = max(0, block.box.x0 - pad)
        val y0 = max(0, block.box.y0 - pad)
        val x1 = min(g.w - 1, block.box.x1 + pad)
        val y1 = min(g.h - 1, block.box.y1 + pad)
        val cw = x1 - x0 + 1
        val ch = y1 - y0 + 1
        val ow = cw + BORDER * 2
        val oh = ch + BORDER * 2
        val out = ByteArray(ow * oh) { -1 }
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                val gi = (y + y0) * g.w + (x + x0)
                var v = g.at(gi)
                val dl = dark.lab[gi]
                if (dl != 0 && owner[dl] != block.owner) v = 255
                out[(y + BORDER) * ow + (x + BORDER)] = v.toByte()
            }
        }
        return Gray(ow, oh, out)
    }

    /**
     * Vertical text -> one horizontal line. Columns are read right to left, each column top to bottom, and every
     * character cell is pasted left to right, so a recogniser only ever sees ordinary horizontal text.
     */
    fun horizontalize(g: Gray, block: TextBlock, dark: Labels, owner: IntArray): Gray {
        val size = max(8, block.size)
        // 1) columns: merge glyphs whose x-ranges overlap (or nearly)
        val gap = max(2, (size * 0.2f).roundToInt())
        val byX = block.glyphs.sortedBy { it.box.x0 }
        val columns = ArrayList<MutableList<Glyph>>()
        var curX1 = Int.MIN_VALUE
        for (gl in byX) {
            if (columns.isEmpty() || gl.box.x0 > curX1 + gap) {
                columns.add(ArrayList())
                curX1 = gl.box.x1
            } else {
                curX1 = max(curX1, gl.box.x1)
            }
            columns.last().add(gl)
        }
        columns.reverse() // right-to-left
        // 2) character cells inside every column (a cell is at most about one character tall)
        val cells = ArrayList<Box>()
        val cellGap = max(1, (size * 0.3f).roundToInt())
        for (col in columns) {
            var colBox = col[0].box
            for (q in col) colBox = colBox.union(q.box)
            val charSize = max(size, (colBox.width * 0.95f).roundToInt())
            val maxCell = (charSize * 1.25f).roundToInt()
            val byY = col.sortedBy { it.box.y0 }
            val startSize = cells.size
            var cell: Box? = null
            for (gl in byY) {
                val c = cell
                if (c == null) {
                    cell = gl.box
                } else if (gl.box.y0 <= c.y1 + cellGap && c.union(gl.box).height <= maxCell) {
                    cell = c.union(gl.box)
                } else {
                    cells.add(c)
                    cell = gl.box
                }
            }
            cell?.let { cells.add(it) }
            val colCells = cells.size - startSize
            // "!" and "?" are a bar plus a dot: keep them as one cell so the bar is not mistaken for a long-vowel mark
            val firstOfCol = cells.size - colCells
            var k = cells.size - 1
            while (k > firstOfCol) {
                val dot = cells[k]
                val bar = cells[k - 1]
                val dotSmall = dot.width <= size * 0.35f && dot.height <= size * 0.35f
                val barThin = bar.width <= bar.height * 0.3f
                if (dotSmall && barThin && bar.union(dot).height <= maxCell * 1.15f) {
                    cells[k - 1] = bar.union(dot)
                    cells.removeAt(k)
                }
                k--
            }
        }
        if (cells.isEmpty()) return cropForOcr(g, block, dark, owner)
        // 3) lay the cells out on one line
        val items = cells.map { cb ->
            // only a lone bar is a long-vowel mark; a bar with a dot ("!") must stay upright
            val inside = block.glyphs.count {
                val cx = (it.box.x0 + it.box.x1) / 2
                val cy = (it.box.y0 + it.box.y1) / 2
                cx in cb.x0..cb.x1 && cy in cb.y0..cb.y1
            }
            val thinLine = inside == 1 && cb.width <= cb.height * 0.3f && cb.height >= size * 0.4f
            Triple(cb, thinLine, if (thinLine) cb.height else cb.width) // long-vowel mark: rotate 90 degrees
        }
        val spacing = max(2, (size * 0.18f).roundToInt())
        val lineH = items.maxOf { (cb, thin, _) -> if (thin) cb.width else cb.height }
        val outH = lineH + BORDER * 2
        val outW = items.sumOf { it.third } + spacing * (items.size - 1) + BORDER * 2
        val out = ByteArray(outW * outH) { -1 }
        var penX = BORDER
        for ((cb, thin, itemW) in items) {
            val itemH = if (thin) cb.width else cb.height
            val oy = BORDER + (lineH - itemH) / 2
            for (y in 0 until cb.height) {
                for (x in 0 until cb.width) {
                    val gi = (cb.y0 + y) * g.w + (cb.x0 + x)
                    var v = g.at(gi)
                    val dl = dark.lab[gi]
                    if (dl != 0 && owner[dl] != block.owner) v = 255
                    val tx: Int
                    val ty: Int
                    if (thin) {
                        tx = penX + y
                        ty = oy + x
                    } else {
                        tx = penX + x
                        ty = oy + y
                    }
                    out[ty * outW + tx] = v.toByte()
                }
            }
            penX += itemW + spacing
        }
        return Gray(outW, outH, out)
    }
}
