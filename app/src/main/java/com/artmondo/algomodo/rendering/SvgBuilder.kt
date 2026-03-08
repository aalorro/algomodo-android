package com.artmondo.algomodo.rendering

data class SvgPath(
    val d: String,
    val stroke: String? = null,
    val fill: String? = null,
    val strokeWidth: Float = 1f,
    val opacity: Float = 1f
)

object SvgBuilder {
    fun build(
        paths: List<SvgPath>,
        width: Int,
        height: Int,
        background: String = "#000000"
    ): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height">""")
        sb.appendLine("""  <rect width="$width" height="$height" fill="$background"/>""")

        for (path in paths) {
            sb.append("  <path d=\"${path.d}\"")
            path.fill?.let { sb.append(" fill=\"$it\"") } ?: sb.append(" fill=\"none\"")
            path.stroke?.let { sb.append(" stroke=\"$it\" stroke-width=\"${path.strokeWidth}\"") }
            if (path.opacity < 1f) sb.append(" opacity=\"${path.opacity}\"")
            sb.appendLine("/>")
        }

        sb.appendLine("</svg>")
        return sb.toString()
    }

    fun moveTo(x: Float, y: Float) = "M${"%.2f".format(x)} ${"%.2f".format(y)}"
    fun lineTo(x: Float, y: Float) = "L${"%.2f".format(x)} ${"%.2f".format(y)}"
    fun curveTo(cx1: Float, cy1: Float, cx2: Float, cy2: Float, x: Float, y: Float) =
        "C${"%.2f".format(cx1)} ${"%.2f".format(cy1)} ${"%.2f".format(cx2)} ${"%.2f".format(cy2)} ${"%.2f".format(x)} ${"%.2f".format(y)}"
    fun closePath() = "Z"

    fun circle(cx: Float, cy: Float, r: Float): String {
        return "M${cx - r},$cy a$r,$r 0 1,0 ${r * 2},0 a$r,$r 0 1,0 ${-r * 2},0"
    }

    fun rect(x: Float, y: Float, w: Float, h: Float): String {
        return "M$x,$y h$w v$h h${-w} Z"
    }

    fun polygon(points: List<Pair<Float, Float>>): String {
        if (points.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append(moveTo(points[0].first, points[0].second))
        for (i in 1 until points.size) {
            sb.append(" ").append(lineTo(points[i].first, points[i].second))
        }
        sb.append(" Z")
        return sb.toString()
    }
}
