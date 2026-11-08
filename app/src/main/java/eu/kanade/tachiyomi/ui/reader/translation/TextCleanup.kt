package eu.kanade.tachiyomi.ui.reader.translation

import kotlin.math.min

// Pure Kotlin helpers (no Android imports) for cleaning OCR output and Arabic text.

internal enum class OcrScript(val code: String, val google: String) {
    LATIN("en", "en"),
    CHINESE("zh", "zh-CN"),
    JAPANESE("ja", "ja"),
    KOREAN("ko", "ko"),
    ;

    val isCjk get() = this != LATIN

    companion object {
        fun fromCode(code: String?): OcrScript? = when (code?.lowercase()?.substringBefore('-')) {
            "en" -> LATIN
            "zh" -> CHINESE
            "ja" -> JAPANESE
            "ko" -> KOREAN
            else -> null
        }
    }
}

internal object TextCleanup {
    private fun isHan(c: Char) = c in '㐀'..'䶿' || c in '一'..'鿿'
    private fun isKana(c: Char) = c in '぀'..'ヿ'
    private fun isHangul(c: Char) = c in '가'..'힯' || c in 'ᄀ'..'ᇿ'
    private fun isLatin(c: Char) = c in 'A'..'Z' || c in 'a'..'z'

    private fun isCjkChar(c: Char) =
        c in '぀'..'ヿ' || c in '㐀'..'䶿' || c in '一'..'鿿' ||
            c in '＀'..'￯' || c in '　'..'〿'

    /** Removes spaces the recogniser puts between two CJK characters. */
    private fun removeCjkGaps(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            sb.append(c)
            if (isCjkChar(c)) {
                var j = i + 1
                while (j < s.length && (s[j] == ' ' || s[j] == '\t')) j++
                if (j > i + 1 && j < s.length && isCjkChar(s[j])) {
                    i = j
                    continue
                }
            }
            i++
        }
        return sb.toString()
    }

    private val whitespace = Regex("\\s+")
    private val hyphenBreak = Regex("(\\w)- (?=[a-z])")

    fun cleanText(lines: List<String>, script: OcrScript): String {
        val parts = lines.map { it.replace(whitespace, " ").trim() }.filter { it.isNotEmpty() }
        var t = if (script == OcrScript.CHINESE || script == OcrScript.JAPANESE) {
            parts.joinToString("")
        } else {
            parts.joinToString(" ").replace(hyphenBreak, "$1")
        }
        if (script != OcrScript.KOREAN) t = removeCjkGaps(t)
        t = removeCjkGaps(t)
        return t.replace(whitespace, " ").trim()
    }

    /** How well does this text fit a given script (0..1)? */
    fun fit(script: OcrScript, text: String): Float {
        val s = text.filterNot { it.isWhitespace() }
        if (s.isEmpty()) return 0f
        val han = s.count(::isHan)
        val kana = s.count(::isKana)
        val hg = s.count(::isHangul)
        val la = s.count(::isLatin)
        val m = when (script) {
            OcrScript.LATIN -> la.toFloat()
            OcrScript.KOREAN -> hg.toFloat()
            OcrScript.JAPANESE -> kana + han * 0.7f + (if (kana > 0) han * 0.3f else 0f)
            OcrScript.CHINESE -> han.toFloat()
        }
        return min(1f, m / s.length)
    }

    private const val NOISE_CHARS = "一ー—―─丨ノ乙-_=~|/\\.,:;'\"`·•*+<>()（）[]{}。、…!！?？・"

    /** Text made only of stroke-like characters or punctuation is artwork noise, not dialogue. */
    fun plausible(text: String, script: OcrScript, strict: Boolean = false): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val core = t.filter { !it.isWhitespace() && NOISE_CHARS.indexOf(it) < 0 }
        if (core.length < (if (strict) 2 else 1)) return false
        if (strict && core.length > 1 && core.all { it == core[0] }) return false
        val latin = t.count(::isLatin)
        if (script == OcrScript.LATIN) return latin >= 2 || t.any { it.isDigit() }
        val letters = t.count(::isHan) + t.count(::isKana) + t.count(::isHangul) + latin
        if (letters == 0) return false
        if (letters == 1) {
            val one = t.filter { !it.isWhitespace() && ".。、…!?！？".indexOf(it) < 0 }
            if (one.length == 1 && "一ー丨|lI1iト亅".indexOf(one[0]) >= 0) return false
        }
        return true
    }

    private fun isArabic(c: Char) = c in '؀'..'ۿ'

    /** Google sometimes keeps Latin punctuation inside Arabic sentences; use the Arabic marks. */
    fun arabize(text: String): String {
        val t = text.replace(whitespace, " ").trim()
        if (t.none(::isArabic)) return t
        return t.replace('?', '؟').replace(',', '،').replace(';', '؛')
    }

    /** Splits long text at sentence ends so each translate request stays small. */
    fun chunk(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        val parts = text.split(Regex("(?<=[.!?。！？…])\\s*"))
        for (part in parts) {
            if (sb.length + part.length > max && sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            sb.append(part)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out.flatMap { c -> if (c.length > max) c.chunked(max) else listOf(c) }
    }
}
