package com.artmondo.algomodo.generators.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.max
import kotlin.math.sin

/**
 * Procedurally generated pseudo-code as a visual texture.
 *
 * A probabilistic grammar generates lines (assignments, calls, conditionals,
 * loops, comments) with tokens drawn from word banks. Indentation follows a
 * random walk bounded by max depth. Each token is assigned a syntax role and
 * colored from the palette. Glyph mode draws real monospace characters;
 * abstract mode replaces every token with a colored rectangle of matching
 * width. Animation scrolls the buffer upward like Matrix rain, but with
 * structured syntax instead of random characters.
 */
class TextCodeGenerator : Generator {

    override val id = "text-code"
    override val family = "text"
    override val styleName = "Code"
    override val definition =
        "Procedurally generated pseudo-code as a visual texture — indentation, brackets, syntax color."
    override val algorithmNotes =
        "A probabilistic grammar generates lines (assignments, calls, conditionals, loops, comments) with " +
        "tokens drawn from word banks. Indentation follows a random walk bounded by max depth, with explicit " +
        "block-open/close tracking. Each token is assigned a syntax role (keyword, identifier, string, number, " +
        "comment) and colored from the palette. Glyph mode draws real monospace characters; abstract mode " +
        "replaces every token with a colored rectangle of matching width. Animation scrolls the buffer upward " +
        "like Matrix rain, but with structured syntax instead of random characters."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Font Size", "fontSize", ParamGroup.GEOMETRY, "Glyph/block height in pixels — smaller = denser, more lines visible", 6f, 144f, 1f, 12f),
        Parameter.NumberParam("Max Indent", "maxIndent", ParamGroup.COMPOSITION, "Deepest nesting level for the random walk", 1f, 10f, 1f, 5f),
        Parameter.NumberParam("Comment Density", "commentDensity", ParamGroup.COMPOSITION, "Probability of comment lines", 0f, 1f, 0.05f, 0.18f),
        Parameter.SelectParam("Language", "flavor", ParamGroup.COMPOSITION, "Grammar flavor — braces, indentation, or parens", listOf("c-like", "python", "lisp"), "c-like"),
        Parameter.SelectParam("Render Mode", "renderMode", ParamGroup.COMPOSITION, "glyph: real characters | abstract: colored token blocks", listOf("glyph", "abstract"), "glyph"),
        Parameter.NumberParam("Corruption", "corruption", ParamGroup.TEXTURE, "Glitch character substitutions, increasing toward right edge", 0f, 1f, 0.05f, 0f),
        Parameter.NumberParam("Glow", "glow", ParamGroup.COLOR, "Bloom halo around bright tokens", 0f, 1f, 0.05f, 0.35f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Vertical scroll speed (Matrix-style upward drift)", 0f, 3f, 0.1f, 0.6f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "fontSize" to 12f,
        "maxIndent" to 5f,
        "commentDensity" to 0.18f,
        "flavor" to "c-like",
        "renderMode" to "glyph",
        "corruption" to 0f,
        "glow" to 0.35f,
        "speed" to 0.6f
    )

    // --- Grammar ------------------------------------------------------------

    private enum class Role { KEYWORD, IDENT, OP, STRING, NUMBER, COMMENT, PUNCT, TYPE }
    private enum class Flavor { C, PY, LISP }

    private data class Token(val text: String, val role: Role)
    private data class Line(val indent: Int, val tokens: List<Token>)

    private val IDENTS = listOf(
        "data", "result", "item", "value", "count", "index", "node", "edge", "next", "curr",
        "temp", "buf", "src", "dst", "cfg", "opts", "state", "ctx", "env", "req", "res",
        "msg", "key", "val", "arr", "obj", "fn", "cb", "err", "foo", "bar", "baz",
        "init", "parse", "render", "update", "handle", "process", "compute", "load", "save",
        "queue", "cache", "pool", "graph", "tree", "hash", "iter", "token", "frame"
    )

    private val TYPES_C = listOf(
        "int", "string", "bool", "float", "void", "auto",
        "Array", "Map", "Set", "Buffer", "Stream", "Result"
    )

    private val STRINGS = listOf(
        "hello", "world", "error", "ok", "tag", "data",
        "/api/v1", "GET", "POST", "utf-8", "json", "null"
    )

    private val COMMENT_TEXTS = listOf(
        "TODO: refactor this", "FIXME: race condition", "NOTE: see #42", "cleanup later",
        "optimize me", "works for now", "temporary hack", "edge case", "do not touch",
        "magic constant", "legacy", "refactor target", "verify input", "cache this",
        "invariant holds", "safe to remove", "check overflow", "patch from upstream"
    )

    private val GLITCH_CHARS = "░▒▓█▀▄■□◆◇○●▪▫⌐¬§¶†‡∆∑∏√∞≠≈±".toCharArray()

    /** Deterministic per-char hash for stable corruption (independent of rng state). */
    private fun charHash(seed: Int, lineIdx: Int, tokenIdx: Int, ci: Int): Int {
        var x = seed + lineIdx * 7919 + tokenIdx * 131 + ci * 17
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        return x
    }

    private fun tok(text: String, role: Role) = Token(text, role)

    private fun buildAssign(rng: SeededRNG, flavor: Flavor): List<Token> {
        val t = mutableListOf<Token>()
        when (flavor) {
            Flavor.C -> {
                t.add(tok(rng.pick(listOf("const", "let", "var")), Role.KEYWORD))
                t.add(tok(rng.pick(IDENTS), Role.IDENT))
                t.add(tok("=", Role.OP))
            }
            Flavor.PY -> {
                t.add(tok(rng.pick(IDENTS), Role.IDENT))
                t.add(tok("=", Role.OP))
            }
            Flavor.LISP -> {
                t.add(tok("(", Role.PUNCT))
                t.add(tok("set!", Role.KEYWORD))
                t.add(tok(rng.pick(IDENTS), Role.IDENT))
            }
        }
        val r = rng.random()
        when {
            r < 0.3f -> t.add(tok((rng.random() * 999).toInt().toString(), Role.NUMBER))
            r < 0.55f -> t.add(tok("\"${rng.pick(STRINGS)}\"", Role.STRING))
            r < 0.8f -> {
                t.add(tok(rng.pick(IDENTS), Role.IDENT))
                t.add(tok("(", Role.PUNCT))
                if (rng.random() < 0.6f) t.add(tok(rng.pick(IDENTS), Role.IDENT))
                t.add(tok(")", Role.PUNCT))
            }
            else -> {
                t.add(tok(rng.pick(IDENTS), Role.IDENT))
                t.add(tok(".", Role.PUNCT))
                t.add(tok(rng.pick(IDENTS), Role.IDENT))
            }
        }
        if (flavor == Flavor.C) t.add(tok(";", Role.PUNCT))
        if (flavor == Flavor.LISP) t.add(tok(")", Role.PUNCT))
        return t
    }

    private fun buildCall(rng: SeededRNG, flavor: Flavor): List<Token> {
        val t = mutableListOf<Token>()
        if (flavor == Flavor.LISP) {
            t.add(tok("(", Role.PUNCT))
            t.add(tok(rng.pick(IDENTS), Role.IDENT))
            val n = 1 + (rng.random() * 3).toInt()
            for (i in 0 until n) {
                if (rng.random() < 0.3f) t.add(tok((rng.random() * 99).toInt().toString(), Role.NUMBER))
                else t.add(tok(rng.pick(IDENTS), Role.IDENT))
            }
            t.add(tok(")", Role.PUNCT))
            return t
        }
        if (rng.random() < 0.5f) {
            t.add(tok(rng.pick(IDENTS), Role.IDENT))
            t.add(tok(".", Role.PUNCT))
        }
        t.add(tok(rng.pick(IDENTS), Role.IDENT))
        t.add(tok("(", Role.PUNCT))
        val n = (rng.random() * 3).toInt()
        for (i in 0 until n) {
            if (i > 0) t.add(tok(",", Role.PUNCT))
            val r = rng.random()
            when {
                r < 0.4f -> t.add(tok("\"${rng.pick(STRINGS)}\"", Role.STRING))
                r < 0.7f -> t.add(tok((rng.random() * 99).toInt().toString(), Role.NUMBER))
                else -> t.add(tok(rng.pick(IDENTS), Role.IDENT))
            }
        }
        t.add(tok(")", Role.PUNCT))
        if (flavor == Flavor.C) t.add(tok(";", Role.PUNCT))
        return t
    }

    private fun buildIf(rng: SeededRNG, flavor: Flavor): List<Token> {
        val t = mutableListOf<Token>()
        if (flavor == Flavor.LISP) {
            t.add(tok("(", Role.PUNCT))
            t.add(tok("if", Role.KEYWORD))
            t.add(tok("(", Role.PUNCT))
            t.add(tok(rng.pick(listOf("<", ">", "=", "<=", ">=")), Role.OP))
            t.add(tok(rng.pick(IDENTS), Role.IDENT))
            t.add(tok((rng.random() * 99).toInt().toString(), Role.NUMBER))
            t.add(tok(")", Role.PUNCT))
            return t
        }
        t.add(tok("if", Role.KEYWORD))
        t.add(tok("(", Role.PUNCT))
        t.add(tok(rng.pick(IDENTS), Role.IDENT))
        t.add(tok(rng.pick(listOf("==", "!=", "<", ">", "<=", ">=")), Role.OP))
        if (rng.random() < 0.5f) t.add(tok((rng.random() * 99).toInt().toString(), Role.NUMBER))
        else t.add(tok(rng.pick(IDENTS), Role.IDENT))
        t.add(tok(")", Role.PUNCT))
        if (flavor == Flavor.C) t.add(tok("{", Role.PUNCT)) else t.add(tok(":", Role.PUNCT))
        return t
    }

    private fun buildFor(rng: SeededRNG, flavor: Flavor): List<Token> {
        val t = mutableListOf<Token>()
        if (flavor == Flavor.PY) {
            t.add(tok("for", Role.KEYWORD))
            t.add(tok(rng.pick(IDENTS), Role.IDENT))
            t.add(tok("in", Role.KEYWORD))
            t.add(tok(rng.pick(IDENTS), Role.IDENT))
            t.add(tok(":", Role.PUNCT))
            return t
        }
        if (flavor == Flavor.LISP) {
            t.add(tok("(", Role.PUNCT))
            t.add(tok("map", Role.KEYWORD))
            t.add(tok("lambda", Role.KEYWORD))
            t.add(tok(rng.pick(IDENTS), Role.IDENT))
            return t
        }
        t.add(tok("for", Role.KEYWORD))
        t.add(tok("(", Role.PUNCT))
        t.add(tok("let", Role.KEYWORD))
        val i = rng.pick(listOf("i", "j", "k", "n"))
        t.add(tok(i, Role.IDENT))
        t.add(tok("=", Role.OP))
        t.add(tok("0", Role.NUMBER))
        t.add(tok(";", Role.PUNCT))
        t.add(tok(i, Role.IDENT))
        t.add(tok("<", Role.OP))
        t.add(tok(((rng.random() * 90) + 10).toInt().toString(), Role.NUMBER))
        t.add(tok(";", Role.PUNCT))
        t.add(tok(i, Role.IDENT))
        t.add(tok("++", Role.OP))
        t.add(tok(")", Role.PUNCT))
        t.add(tok("{", Role.PUNCT))
        return t
    }

    private fun buildFunction(rng: SeededRNG, flavor: Flavor): List<Token> {
        val t = mutableListOf<Token>()
        if (flavor == Flavor.PY) {
            t.add(tok("def", Role.KEYWORD))
            t.add(tok(rng.pick(IDENTS), Role.IDENT))
            t.add(tok("(", Role.PUNCT))
            val n = (rng.random() * 3).toInt()
            for (i in 0 until n) {
                if (i > 0) t.add(tok(",", Role.PUNCT))
                t.add(tok(rng.pick(IDENTS), Role.IDENT))
            }
            t.add(tok(")", Role.PUNCT))
            t.add(tok(":", Role.PUNCT))
            return t
        }
        if (flavor == Flavor.LISP) {
            t.add(tok("(", Role.PUNCT))
            t.add(tok("define", Role.KEYWORD))
            t.add(tok("(", Role.PUNCT))
            t.add(tok(rng.pick(IDENTS), Role.IDENT))
            val n = (rng.random() * 3).toInt()
            for (i in 0 until n) t.add(tok(rng.pick(IDENTS), Role.IDENT))
            t.add(tok(")", Role.PUNCT))
            return t
        }
        t.add(tok("function", Role.KEYWORD))
        t.add(tok(rng.pick(IDENTS), Role.IDENT))
        t.add(tok("(", Role.PUNCT))
        val n = (rng.random() * 3).toInt()
        for (i in 0 until n) {
            if (i > 0) t.add(tok(",", Role.PUNCT))
            t.add(tok(rng.pick(IDENTS), Role.IDENT))
            if (rng.random() < 0.3f) {
                t.add(tok(":", Role.PUNCT))
                t.add(tok(rng.pick(TYPES_C), Role.TYPE))
            }
        }
        t.add(tok(")", Role.PUNCT))
        t.add(tok("{", Role.PUNCT))
        return t
    }

    private fun buildReturn(rng: SeededRNG, flavor: Flavor): List<Token> {
        val t = mutableListOf<Token>()
        t.add(tok("return", Role.KEYWORD))
        val r = rng.random()
        when {
            r < 0.4f -> t.add(tok(rng.pick(IDENTS), Role.IDENT))
            r < 0.6f -> t.add(tok((rng.random() * 99).toInt().toString(), Role.NUMBER))
            else -> {
                t.add(tok(rng.pick(IDENTS), Role.IDENT))
                t.add(tok(".", Role.PUNCT))
                t.add(tok(rng.pick(IDENTS), Role.IDENT))
            }
        }
        if (flavor == Flavor.C) t.add(tok(";", Role.PUNCT))
        if (flavor == Flavor.LISP) t.add(tok(")", Role.PUNCT))
        return t
    }

    private fun buildComment(rng: SeededRNG, flavor: Flavor): List<Token> {
        val prefix = when (flavor) {
            Flavor.PY -> "#"
            Flavor.LISP -> ";;"
            Flavor.C -> "//"
        }
        return listOf(tok("$prefix ${rng.pick(COMMENT_TEXTS)}", Role.COMMENT))
    }

    private fun generateLines(
        rng: SeededRNG,
        count: Int,
        maxDepth: Int,
        commentDensity: Float,
        flavor: Flavor
    ): List<Line> {
        val lines = mutableListOf<Line>()
        var indent = 0
        var i = 0
        while (i < count) {
            val r = rng.random()
            var lineIndent = indent
            val tokens: List<Token>

            if (r < commentDensity) {
                tokens = buildComment(rng, flavor)
            } else if (r < commentDensity + 0.04f) {
                tokens = emptyList() // blank line
            } else if (indent > 0 && r < commentDensity + 0.16f) {
                // Close block — only emits a visible token in C-flavor
                indent = max(0, indent - 1)
                lineIndent = indent
                tokens = if (flavor == Flavor.C) listOf(tok("}", Role.PUNCT)) else emptyList()
            } else if (indent < maxDepth && rng.random() < 0.22f) {
                // Block opener
                val which = rng.random()
                val openerTokens = when {
                    which < 0.4f -> buildIf(rng, flavor)
                    which < 0.75f -> buildFor(rng, flavor)
                    else -> buildFunction(rng, flavor)
                }
                lines.add(Line(lineIndent, openerTokens))
                indent = (indent + 1).coerceAtMost(maxDepth)
                i++
                continue
            } else {
                val which = rng.random()
                tokens = when {
                    which < 0.5f -> buildAssign(rng, flavor)
                    which < 0.85f -> buildCall(rng, flavor)
                    else -> buildReturn(rng, flavor)
                }
            }

            lines.add(Line(lineIndent, tokens))
            i++
        }
        return lines
    }

    // --- Cache --------------------------------------------------------------

    @Volatile private var cachedLines: List<Line>? = null
    @Volatile private var cachedKey: String = ""

    // --- Render -------------------------------------------------------------

    override fun renderCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float
    ) {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        val fontSize = (params["fontSize"] as? Number)?.toFloat() ?: 12f
        val maxIndent = (params["maxIndent"] as? Number)?.toInt() ?: 5
        val commentDensity = (params["commentDensity"] as? Number)?.toFloat() ?: 0.18f
        val flavorParam = (params["flavor"] as? String) ?: "c-like"
        val flavor = when (flavorParam) {
            "python" -> Flavor.PY
            "lisp" -> Flavor.LISP
            else -> Flavor.C
        }
        val renderMode = (params["renderMode"] as? String) ?: "glyph"
        val corruption = (params["corruption"] as? Number)?.toFloat() ?: 0f
        val glow = (params["glow"] as? Number)?.toFloat() ?: 0.35f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.6f

        val isHighQuality = quality != Quality.DRAFT
        val paletteInts = palette.colorInts().toIntArray()
        val n = paletteInts.size

        // Atmospheric vignette tinted by accent palette color
        val accent = paletteInts[0]
        val aR = ((Color.red(accent) * 0.07f)).toInt()
        val aG = ((Color.green(accent) * 0.07f)).toInt()
        val aB = ((Color.blue(accent) * 0.07f)).toInt()
        val tinted = Color.rgb(aR, aG, aB)
        val bgEdge = Color.rgb(3, 3, 10)
        val bgPaint = Paint().apply {
            isAntiAlias = isHighQuality
            shader = RadialGradient(
                w / 2f, h / 2f, max(w, h) * 0.75f,
                tinted, bgEdge, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val lineH = (fontSize * 1.4f).toInt().coerceAtLeast(1).toFloat()
        val visibleLines = (h / lineH).toInt() + 4
        val lineCount = visibleLines + 200

        // Cache the line buffer — only regenerate when seed/params/dims change.
        val cacheKey = "$seed|$maxIndent|$commentDensity|${flavor.name}|$lineCount"
        val lines: List<Line> = if (cacheKey != cachedKey) {
            val generated = generateLines(SeededRNG(seed), lineCount, maxIndent, commentDensity, flavor)
            cachedLines = generated
            cachedKey = cacheKey
            generated
        } else {
            cachedLines ?: generateLines(SeededRNG(seed), lineCount, maxIndent, commentDensity, flavor).also {
                cachedLines = it
                cachedKey = cacheKey
            }
        }

        // Pre-compute alpha variants for every palette color.
        val palMain = IntArray(n)
        val palHalo = IntArray(n)
        val palDim = IntArray(n)
        val palBlock = IntArray(n)
        val palBlockGlow = IntArray(n)
        for (i in 0 until n) {
            val c = paletteInts[i]
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            palMain[i] = Color.argb(242, r, g, b)                           // 0.95
            palHalo[i] = Color.argb((glow * 0.5f * 255f).toInt().coerceIn(0, 255), r, g, b)
            palDim[i] = Color.argb(140, r, g, b)                            // 0.55
            palBlock[i] = Color.argb(224, r, g, b)                          // 0.88
            palBlockGlow[i] = Color.argb((glow * 0.32f * 255f).toInt().coerceIn(0, 255), r, g, b)
        }

        // Role -> base palette index. Per-line rotation cycles these to create
        // traveling rainbow waves while keeping roles visually distinct.
        fun roleBase(role: Role): Int = when (role) {
            Role.KEYWORD -> 0
            Role.IDENT -> 1
            Role.STRING -> 2
            Role.NUMBER -> 3
            Role.TYPE -> 4
            Role.OP -> 5
            Role.PUNCT -> 6 % n
            Role.COMMENT -> 0
        }

        // Paint setup — monospace, and two variants for bold/italic.
        val monoNormal = Typeface.MONOSPACE
        val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val monoItalic = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)

        val paint = Paint().apply {
            isAntiAlias = isHighQuality
            typeface = monoNormal
            textSize = fontSize
            textAlign = Paint.Align.LEFT
        }
        val charW = paint.measureText("M").let { if (it > 0f) it else fontSize * 0.6f }
        val indentW = charW * 2f
        val margin = charW * 1.5f
        val blockH = lineH * 0.62f
        val blockHHalf = blockH / 2f
        val lineHHalf = lineH / 2f
        // Text baseline offset so that (y) behaves like the web's textBaseline='middle'.
        val fm = paint.fontMetrics
        val baselineOffset = -(fm.ascent + fm.descent) / 2f

        // Scroll: continuous upward drift in pixels
        val scrollOffset = if (time > 0f) time * speed * lineH * 1.2f else 0f
        val scrollLines = (scrollOffset / lineH).toInt()
        val subPixel = scrollOffset - scrollLines * lineH
        val corrActive = corruption > 0.001f
        val animating = time > 0f
        val colorPhase = time * 0.6f
        val swayAmp = if (animating) lineH * 0.04f else 0f

        // Scanning focus band — soft horizontal highlight that travels down.
        if (animating) {
            val scanCenterY = ((time * lineH * 0.9f) % (h + lineH * 4f)) - lineH * 2f
            val scanRadius = lineH * 2.5f
            val topY = scanCenterY - scanRadius
            val botY = scanCenterY + scanRadius
            val accR = Color.red(accent); val accG = Color.green(accent); val accB = Color.blue(accent)
            val scanPaint = Paint().apply {
                isAntiAlias = false
                xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                shader = LinearGradient(
                    0f, topY, 0f, botY,
                    intArrayOf(
                        Color.argb(0, accR, accG, accB),
                        Color.argb(46, accR, accG, accB), // 0.18 * 255
                        Color.argb(0, accR, accG, accB)
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, topY, w, botY, scanPaint)
        }

        if (renderMode == "glyph") {
            // Halo pass (additive) + main pass. Using larger textSize for the
            // halo pass on Android to create a visible bloom (Canvas2D's
            // 'lighter' additive trick doesn't translate directly).
            val haloPaint = Paint().apply {
                isAntiAlias = isHighQuality
                typeface = monoNormal
                textSize = fontSize * 1.18f
                textAlign = Paint.Align.LEFT
                xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            }
            val haloFm = haloPaint.fontMetrics
            val haloBaselineOffset = -(haloFm.ascent + haloFm.descent) / 2f

            // Pre-build corrupted text per token call to avoid per-row work.
            val buf = StringBuilder()
            val drawGlyphPass = { isHalo: Boolean ->
                val p = if (isHalo) haloPaint else paint
                val baseOffset = if (isHalo) haloBaselineOffset else baselineOffset
                // Re-apply typeface for main paint at the start of each pass
                if (!isHalo) p.typeface = monoNormal

                var currentTypeface = monoNormal
                p.typeface = currentTypeface

                var row = -2
                while (row < visibleLines) {
                    val modLen = lines.size
                    val rawIdx = (row + scrollLines) % modLen
                    val lineIdx = if (rawIdx < 0) rawIdx + modLen else rawIdx
                    val line = lines[lineIdx]
                    if (line.tokens.isNotEmpty()) {
                        val y = row * lineH - subPixel + lineHHalf
                        if (y >= -lineH && y <= h + lineH) {
                            val rotRaw = ((lineIdx * 0.31f + colorPhase).toInt()) % n
                            val lineRotate = if (rotRaw < 0) rotRaw + n else rotRaw
                            val ySway = if (animating) sin(time * 0.7f + lineIdx * 0.4f) * swayAmp else 0f
                            val yDraw = y + ySway + baseOffset

                            var x = margin + line.indent * indentW
                            for (ti in line.tokens.indices) {
                                val t = line.tokens[ti]
                                val role = t.role
                                val tokenW = t.text.length * charW

                                if (isHalo && role == Role.COMMENT) {
                                    x += tokenW + charW
                                    continue
                                }

                                var text = t.text
                                if (corrActive) {
                                    val corrProb = corruption * (x / w)
                                    if (corrProb > 0.01f) {
                                        buf.setLength(0)
                                        for (ci in text.indices) {
                                            val hashed = charHash(seed, lineIdx, ti, ci)
                                            // Match web: (hashed % 1000) / 1000 < corrProb
                                            val modProb = ((hashed and 0x7FFFFFFF) % 1000).toFloat() / 1000f
                                            if (modProb < corrProb) {
                                                buf.append(GLITCH_CHARS[((hashed ushr 8) and 0x7FFFFFFF) % GLITCH_CHARS.size])
                                            } else {
                                                buf.append(text[ci])
                                            }
                                        }
                                        text = buf.toString()
                                    }
                                }

                                val wantTypeface = when (role) {
                                    Role.COMMENT -> monoItalic
                                    Role.KEYWORD, Role.TYPE -> monoBold
                                    else -> monoNormal
                                }
                                if (wantTypeface !== currentTypeface) {
                                    p.typeface = wantTypeface
                                    currentTypeface = wantTypeface
                                }

                                val palIdx = (roleBase(role) + lineRotate) % n
                                p.color = if (isHalo) palHalo[palIdx]
                                else if (role == Role.COMMENT) palDim[palIdx] else palMain[palIdx]

                                if (!isHalo && animating && role == Role.PUNCT) {
                                    val pulse = 0.65f + 0.35f * sin(time * 4f + lineIdx * 0.4f + ti * 0.5f)
                                    val baseA = Color.alpha(p.color)
                                    p.alpha = (baseA * pulse).toInt().coerceIn(0, 255)
                                    canvas.drawText(text, x, yDraw, p)
                                    p.alpha = baseA
                                } else {
                                    canvas.drawText(text, x, yDraw, p)
                                }
                                x += tokenW + charW
                            }
                        }
                    }
                    row++
                }
            }

            if (glow > 0f && isHighQuality) {
                drawGlyphPass(true)
            }
            drawGlyphPass(false)
        } else {
            // Abstract mode: colored rectangles per token.
            val drawHalo = glow > 0f
            val blockPaint = Paint().apply { isAntiAlias = false }
            var row = -2
            while (row < visibleLines) {
                val modLen = lines.size
                val rawIdx = (row + scrollLines) % modLen
                val lineIdx = if (rawIdx < 0) rawIdx + modLen else rawIdx
                val line = lines[lineIdx]
                if (line.tokens.isNotEmpty()) {
                    val y = row * lineH - subPixel + lineHHalf
                    if (y >= -lineH && y <= h + lineH) {
                        val rotRaw = ((lineIdx * 0.31f + colorPhase).toInt()) % n
                        val lineRotate = if (rotRaw < 0) rotRaw + n else rotRaw
                        val ySway = if (animating) sin(time * 0.7f + lineIdx * 0.4f) * swayAmp else 0f
                        val yTop = y - blockHHalf + ySway

                        var x = margin + line.indent * indentW
                        for (ti in line.tokens.indices) {
                            val t = line.tokens[ti]
                            val role = t.role
                            val tokenW = t.text.length * charW
                            val palIdx = (roleBase(role) + lineRotate) % n

                            if (drawHalo && role != Role.COMMENT) {
                                blockPaint.color = palBlockGlow[palIdx]
                                canvas.drawRect(x - 1f, yTop - 1f, x - 1f + tokenW + 2f, yTop - 1f + blockH + 2f, blockPaint)
                            }
                            blockPaint.color = palBlock[palIdx]
                            canvas.drawRect(x, yTop, x + tokenW, yTop + blockH, blockPaint)

                            x += tokenW + charW
                        }
                    }
                }
                row++
            }
        }

        // Soft top/bottom edge fade so scrolling lines enter/exit gracefully.
        val edgeColor = Color.argb(140, 0, 0, 8) // 0.55 * 255
        val edgeTransparent = Color.argb(0, 0, 0, 8)
        val edgePaint = Paint().apply {
            isAntiAlias = false
            shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(edgeColor, edgeTransparent, edgeTransparent, edgeColor),
                floatArrayOf(0f, 0.12f, 0.88f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, edgePaint)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val fs = (params["fontSize"] as? Number)?.toFloat() ?: 12f
        return ((1080f / (fs * 1.4f)) * 80f / 5000f).coerceIn(0.2f, 0.8f)
    }
}
