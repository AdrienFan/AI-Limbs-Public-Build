package com.ai.limbs.plugins.artstudio

import android.graphics.Path
import kotlin.math.*

/** SVG path tokenizer and independent elliptical-arc to cubic conversion. */
internal object ArtSvgPath {
    data class Command(val name: Char, val values: List<Double>)
    private val token = Regex("[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")
    fun commands(source: String): List<Command> {
        require(source.length in 1..65536) { "SVG 路径长度无效" }
        val tokens = token.findAll(source).toList()
        var end = 0
        tokens.forEach { require(source.substring(end, it.range.first).all { c -> c.isWhitespace() || c == ',' }) { "SVG 路径包含无效字符" }; end = it.range.last + 1 }
        require(source.substring(end).all { it.isWhitespace() || it == ',' })
        require(tokens.isNotEmpty() && tokens.first().value in setOf("M", "m")) { "路径需要从 M 开始" }
        val result = mutableListOf<Command>(); var i = 0; var name = 'M'
        while (i < tokens.size) {
            if (tokens[i].value.singleOrNull()?.isLetter() == true) name = tokens[i++].value[0]
            val count = when (name.uppercaseChar()) { 'M', 'L', 'T' -> 2; 'H', 'V' -> 1; 'C' -> 6; 'S', 'Q' -> 4; 'A' -> 7; 'Z' -> 0; else -> error("无效路径命令") }
            require(i + count <= tokens.size){"SVG 路径命令 $name 需要$count个数字，剩余不足"}
            val values = (0 until count).map {
                val text=tokens[i++].value
                val number=text.toDoubleOrNull()
                require(number!=null&&number.isFinite()&&abs(number)<=1000000){"SVG 路径命令 $name 需要$count个有限数字，遇到 '$text'"}
                number
            }
            if (name.uppercaseChar() == 'A') require(values[0] >= 0 && values[1] >= 0 && values[3] in listOf(0.0, 1.0) && values[4] in listOf(0.0, 1.0))
            result.add(Command(name, values)); require(result.size <= 2048) { "SVG 路径最多 2048 个命令" }
            if (name == 'M') name = 'L' else if (name == 'm') name = 'l'
            if (count == 0) require(i == tokens.size || tokens[i].value.singleOrNull()?.isLetter() == true)
        }
        return result
    }
    fun path(source: String): Path = Path().apply {
        var x = 0.0; var y = 0.0; var sx = 0.0; var sy = 0.0; var cx = 0.0; var cy = 0.0; var previous = 'M'
        for ((name, v) in commands(source)) {
            val relative = name.isLowerCase(); val op = name.uppercaseChar()
            fun xx(i: Int) = v[i] + if (relative) x else 0.0
            fun yy(i: Int) = v[i] + if (relative) y else 0.0
            when (op) {
                'M' -> { x = xx(0); y = yy(1); sx = x; sy = y; moveTo(x.toFloat(), y.toFloat()) }
                'L' -> { x = xx(0); y = yy(1); lineTo(x.toFloat(), y.toFloat()) }
                'H' -> { x = xx(0); lineTo(x.toFloat(), y.toFloat()) }
                'V' -> { y = yy(0); lineTo(x.toFloat(), y.toFloat()) }
                'C' -> { val a = xx(0); val b = yy(1); cx = xx(2); cy = yy(3); val u = xx(4); val w = yy(5)
                    cubicTo(a.toFloat(), b.toFloat(), cx.toFloat(), cy.toFloat(), u.toFloat(), w.toFloat()); x = u; y = w }
                'S' -> { val a = if (previous in setOf('C', 'S')) 2 * x - cx else x; val b = if (previous in setOf('C', 'S')) 2 * y - cy else y
                    cx = xx(0); cy = yy(1); val u = xx(2); val w = yy(3)
                    cubicTo(a.toFloat(), b.toFloat(), cx.toFloat(), cy.toFloat(), u.toFloat(), w.toFloat()); x = u; y = w }
                'Q', 'T' -> { val a = if (op == 'Q') xx(0) else if (previous in setOf('Q', 'T')) 2 * x - cx else x
                    val b = if (op == 'Q') yy(1) else if (previous in setOf('Q', 'T')) 2 * y - cy else y
                    val u = xx(if (op == 'Q') 2 else 0); val w = yy(if (op == 'Q') 3 else 1)
                    quadTo(a.toFloat(), b.toFloat(), u.toFloat(), w.toFloat()); cx = a; cy = b; x = u; y = w }
                'A' -> { val u = xx(5); val w = yy(6); arc(this, x, y, u, w, v[0], v[1], v[2], v[3] == 1.0, v[4] == 1.0); x = u; y = w }
                'Z' -> { close(); x = sx; y = sy }
            }
            previous = op
        }
    }
    private fun arc(p: Path, x: Double, y: Double, u: Double, v: Double, rx0: Double, ry0: Double, rotation: Double, large: Boolean, sweep: Boolean) {
        if (x == u && y == v) return
        if (rx0 == 0.0 || ry0 == 0.0) { p.lineTo(u.toFloat(), v.toFloat()); return }
        val phi = Math.toRadians(rotation % 360); val c = cos(phi); val s = sin(phi)
        val a = c * (x - u) / 2 + s * (y - v) / 2; val b = -s * (x - u) / 2 + c * (y - v) / 2
        val scale = maxOf(1.0, sqrt(a * a / (rx0 * rx0) + b * b / (ry0 * ry0)))
        val rx = rx0 * scale; val ry = ry0 * scale
        val denominator = rx * rx * b * b + ry * ry * a * a
        val q = (if (large == sweep) -1 else 1) * sqrt(maxOf(0.0, (rx * rx * ry * ry - denominator) / denominator))
        val ax = q * rx * b / ry; val by = -q * ry * a / rx
        val centerX = c * ax - s * by + (x + u) / 2; val centerY = s * ax + c * by + (y + v) / 2
        val first = atan2((b - by) / ry, (a - ax) / rx)
        var delta = atan2((-b - by) / ry, (-a - ax) / rx) - first
        if (sweep && delta < 0) delta += 2 * PI
        if (!sweep && delta > 0) delta -= 2 * PI
        val count = ceil(abs(delta) / (PI / 2)).toInt().coerceAtLeast(1)
        fun point(t: Double) = (centerX + c * rx * cos(t) - s * ry * sin(t)) to (centerY + s * rx * cos(t) + c * ry * sin(t))
        fun derivative(t: Double) = (-c * rx * sin(t) - s * ry * cos(t)) to (-s * rx * sin(t) + c * ry * cos(t))
        for (i in 0 until count) {
            val t0 = first + delta * i / count; val t1 = first + delta * (i + 1) / count
            val k = 4.0 / 3 * tan((t1 - t0) / 4); val p0 = point(t0); val p1 = point(t1); val d0 = derivative(t0); val d1 = derivative(t1)
            p.cubicTo((p0.first + k * d0.first).toFloat(), (p0.second + k * d0.second).toFloat(),
                (p1.first - k * d1.first).toFloat(), (p1.second - k * d1.second).toFloat(), p1.first.toFloat(), p1.second.toFloat())
        }
    }
}
