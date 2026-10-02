package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.sqrt

/** Fits geometry before the write. Replay always uses the recorded controls, never refits. */
internal object ArtFreehand {
    const val MAX_SAMPLES = 2048
    const val MAX_SEGMENTS = 2048
    const val MAX_GEOMETRY_POINTS = 6145
    val modes = listOf("raw", "curve", "straight")

    private data class Point(val x: Double, val y: Double) {
        operator fun plus(p: Point) = Point(x + p.x, y + p.y)
        operator fun minus(p: Point) = Point(x - p.x, y - p.y)
        operator fun times(n: Double) = Point(x * n, y * n)
        fun dot(p: Point) = x * p.x + y * p.y
        fun length() = sqrt(dot(this))
        fun unit(): Point {
            val size = length()
            check(size > 0.0 && size.isFinite())
            return this * (1.0 / size)
        }
        fun json() = JSONArray().put(x).put(y)
    }
    private data class Task(val first: Int, val last: Int, val start: Point, val end: Point)
    private data class Cubic(val p0: Point, val p1: Point, val p2: Point, val p3: Point) {
        fun at(t: Double): Point {
            val s = 1.0 - t
            return p0 * (s*s*s) + p1 * (3*s*s*t) + p2 * (3*s*t*t) + p3 * (t*t*t)
        }
    }

    fun create(p: JSONObject): JSONObject {
        val mode = p.optString("mode", "curve")
        val precision = p.optDouble("precision", 2.0)
        val closed = p.optBoolean("closed", false)
        val optimizeRaw=if(p.has("optimizeRaw"))p.getBoolean("optimizeRaw") else false
        val optimizeCurve=if(p.has("optimizeCurve"))p.getBoolean("optimizeCurve") else true
        val rawPrecision=if(p.has("rawPrecision"))p.getDouble("rawPrecision") else 1.0
        val curvePrecision=if(p.has("curvePrecision"))p.getDouble("curvePrecision") else precision
        val combineAngle=if(p.has("combineAngle"))p.getDouble("combineAngle") else 0.0
        require(rawPrecision.isFinite()&&rawPrecision in 0.25..32.0&&curvePrecision.isFinite()&&curvePrecision in 0.25..32.0)
        require(combineAngle.isFinite()&&combineAngle in 0.0..90.0) {"直线合并转角须为0–90度"}
        require(mode in modes) { "路径模式需要 raw、curve 或 straight" }
        require(precision.isFinite() && precision in 0.25..32.0) { "路径精度需要在0.25至32个图层局部像素之间" }
        val samples = p.getJSONArray("points")
        require(samples.length() in 2..MAX_SAMPLES) { "徒手路径需要2至2048个采样点，请将长轨迹分段" }
        val points = ArrayList<Point>(samples.length())
        for (i in 0 until samples.length()) {
            val a = samples.getJSONArray(i)
            require(a.length() == 2) { "每个路径采样点需要x、y两项" }
            val point = Point(a.getDouble(0), a.getDouble(1))
            require(point.x.isFinite() && point.y.isFinite() &&
                kotlin.math.abs(point.x) <= 1000000.0 && kotlin.math.abs(point.y) <= 1000000.0)
            if (points.isEmpty() || (point - points.last()).length() > 0.000001) points.add(point)
        }
        if (closed && points.size > 1 && (points.first() - points.last()).length() <= 0.000001)
            points.removeAt(points.lastIndex)
        require(points.size >= (if (closed) 3 else 2)) { "请画出非零轨迹；闭合路径至少需要三个不同位置" }
        if (closed) require(points.toSet().size >= 3) { "闭合路径至少需要三个不同位置" }
        val source = if (closed) points + points.first() else points
        val geometry = JSONArray().put(source.first().json())
        val commands = JSONArray()
        when (mode) {
            "raw" -> (if(optimizeRaw)simplify(source,rawPrecision) else source).drop(1).forEach { commands.put("L"); geometry.put(it.json()) }
            "straight" -> combineLines(simplify(source,precision),source,precision,combineAngle).drop(1).forEach { commands.put("L"); geometry.put(it.json()) }
            "curve" -> for (segment in if(optimizeCurve)fit(source,curvePrecision) else interpolate(source)) {
                commands.put("C")
                geometry.put(segment.p1.json()).put(segment.p2.json()).put(segment.p3.json())
            }
        }
        val first = geometry.getJSONArray(0)
        require((1 until geometry.length()).any {
            val point = geometry.getJSONArray(it)
            point.getDouble(0) != first.getDouble(0) || point.getDouble(1) != first.getDouble(1)
        }) { "当前精度使路径消失，请减小精度或重新绘制" }
        val shape = JSONObject().put("id", UUID.randomUUID().toString()).put("kind", "path")
            .put("points", geometry).put("commands", commands).put("closed", closed)
            .put("freehand", JSONObject().put("mode", mode).put("precision", precision)
                .put("sampleCount", samples.length()).put("optimizeRaw",optimizeRaw).put("optimizeCurve",optimizeCurve)
                .put("rawPrecision",rawPrecision).put("curvePrecision",curvePrecision).put("combineAngle",combineAngle))
        val style = p.optJSONObject("style")
        if (style != null) {
            require(style.keys().asSequence().all { it in setOf("fill", "stroke", "strokeWidth", "opacity","objectStyle") }) {
                "徒手路径样式仅支持fill、stroke、strokeWidth、opacity、objectStyle"
            }
            style.keys().forEach { key -> shape.put(key, style.get(key)) }
        }
        return ArtShapes.normalize(shape)
    }

    private fun interpolate(points:List<Point>):List<Cubic> = (0 until points.lastIndex).map {i->
        val a=points[i];val b=points[i+1]
        val before=points[maxOf(0,i-1)];val after=points[minOf(points.lastIndex,i+2)]
        Cubic(a,a+(b-before)*(1.0/6.0),b-(after-a)*(1.0/6.0),b)
    }
    private fun combineLines(points:List<Point>,source:List<Point>,precision:Double,angle:Double):List<Point> {
        if(angle==0.0||points.size<3)return points
        val out=mutableListOf(points.first(),points[1]);var runStart=0;var sourceIndex=(1 until source.size).first {source[it]==points[1]}
        for(p in points.drop(2)) {
            var end=sourceIndex+1;while(end<source.size&&source[end]!=p)end++
            require(end<source.size)
            val a=out[out.lastIndex-1];val b=out.last();val first=b-a;val next=p-b;val chord=p-a
            val lengths=first.length()*next.length();val squared=chord.dot(chord)
            val turn=if(lengths>0)Math.toDegrees(kotlin.math.acos((first.dot(next)/lengths).coerceIn(-1.0,1.0))) else 180.0
            val valid=turn<=angle&&first.dot(next)>0&&squared>0&&(runStart..end).all {i->
                val relative=source[i]-a;val t=relative.dot(chord)/squared
                t in 0.0..1.0&&(relative-chord*t).length()<=precision
            }
            if(valid)out[out.lastIndex]=p else {out.add(p);runStart=sourceIndex}
            sourceIndex=end
        };return out
    }
    fun info()=JSONObject().put("modes",JSONArray(modes)).put("defaults",JSONObject().put("mode","curve").put("precision",2).put("optimizeRaw",false).put("rawPrecision",1).put("optimizeCurve",true).put("combineAngle",0))
        .put("optimization","Raw preserves samples or independently reduces by rawPrecision; Curve fits with curvePrecision (defaults to precision), or interpolates every sample when optimizeCurve=false; straight combines forward segments within combineAngle and original-sample deviation precision")
        .put("connection","startEndpoint/endEndpoint={id,subpath,node}, zero-based open endpoints from path.nodes.subpaths; points stay layer-local, target transforms mapped internally, target id/style preserved; same-path opposite endpoints close a loop")
        .put("style","New paths accept style.objectStyle; connected paths keep destination style; modify after connection with shape.style")
        .put("limits","2–2048 samples; precisions 0.25–32 local pixels; combineAngle 0–90 degrees, 0 disables extra angular merge; geometry <=2048 segments/6145 points")

    /** Distance-based polyline simplification; endpoints and significant corners are retained. */
    private fun simplify(points: List<Point>, precision: Double): List<Point> {
        val keep = BooleanArray(points.size)
        keep[0] = true; keep[points.lastIndex] = true
        val work = ArrayDeque<Pair<Int, Int>>()
        work.addLast(0 to points.lastIndex)
        val limit = precision * precision
        while (!work.isEmpty()) {
            val (first, last) = work.removeLast()
            if (last - first <= 1) continue
            val start = points[first]; val chord = points[last] - start
            val lengthSquared = chord.dot(chord)
            var error = -1.0; var split = first + 1
            for (i in first + 1 until last) {
                val relative = points[i] - start
                // A closed contour has coincident endpoints: measure from that endpoint.
                val t = if (lengthSquared == 0.0) 0.0 else (relative.dot(chord) / lengthSquared).coerceIn(0.0, 1.0)
                val offset = relative - chord * t
                val distance = offset.dot(offset)
                if (distance > error) { error = distance; split = i }
            }
            if (error > limit) {
                keep[split] = true
                work.addLast(split to last); work.addLast(first to split)
            }
        }
        return points.filterIndexed { index, _ -> keep[index] }
    }

    /** Iterative piecewise cubic fitting with chord parameters and bounded fitting work. */
    private fun fit(points: List<Point>, precision: Double): List<Cubic> {
        val result = ArrayList<Cubic>()
        val work = ArrayDeque<Task>()
        work.addLast(Task(0, points.lastIndex, (points[1] - points[0]).unit(),
            (points[points.lastIndex - 1] - points.last()).unit()))
        val limit = precision * precision
        while (!work.isEmpty()) {
            val task = work.removeLast()
            val count = task.last - task.first + 1
            val parameters = DoubleArray(count)
            for (i in 1 until count)
                parameters[i] = parameters[i - 1] + (points[task.first + i] - points[task.first + i - 1]).length()
            val arcLength = parameters.last()
            check(arcLength > 0.0)
            for (i in parameters.indices) parameters[i] /= arcLength
            val segment = cubic(points, task, parameters, arcLength)
            var error = -1.0; var split = task.first + count / 2
            for (i in 1 until count - 1) {
                val distance = segment.at(parameters[i]) - points[task.first + i]
                val squared = distance.dot(distance)
                if (squared > error) { error = squared; split = task.first + i }
            }
            if (count == 2 || error <= limit) {
                result.add(segment)
                require(result.size <= MAX_SEGMENTS) { "路径段数超过2048" }
            } else {
                val before = (points[split] - points[split - 1]).unit()
                val after = (points[split + 1] - points[split]).unit()
                val sum = before + after
                // At a reversal preserve the cusp; elsewhere both segments share a tangent.
                val right = if (sum.length() < 0.1) after else sum.unit()
                val left = if (sum.length() < 0.1) before * -1.0 else right * -1.0
                work.addLast(Task(split, task.last, right, task.end))
                work.addLast(Task(task.first, split, task.start, left))
            }
        }
        return result
    }

    private fun cubic(points: List<Point>, task: Task, u: DoubleArray, arcLength: Double): Cubic {
        val p0 = points[task.first]; val p3 = points[task.last]
        if (task.last - task.first == 1) {
            val distance = (p3 - p0).length() / 3.0
            return Cubic(p0, p0 + task.start * distance, p3 + task.end * distance, p3)
        }
        // A small ridge gives the least-squares fit a defined solution even for collinear samples.
        val ridge = 0.000001
        val prior = arcLength / 3.0
        var c00 = ridge; var c01 = 0.0; var c11 = ridge
        var x0 = ridge * prior; var x1 = ridge * prior
        for (i in u.indices) {
            val t = u[i]; val s = 1.0 - t
            val b0 = s*s*s; val b1 = 3*s*s*t; val b2 = 3*s*t*t; val b3 = t*t*t
            val a0 = task.start * b1; val a1 = task.end * b2
            val residual = points[task.first + i] - p0 * (b0 + b1) - p3 * (b2 + b3)
            c00 += a0.dot(a0); c01 += a0.dot(a1); c11 += a1.dot(a1)
            x0 += a0.dot(residual); x1 += a1.dot(residual)
        }
        val determinant = c00*c11 - c01*c01
        check(determinant > 0.0 && determinant.isFinite())
        val alpha0 = ((x0*c11 - x1*c01) / determinant).coerceIn(0.0, arcLength)
        val alpha1 = ((c00*x1 - c01*x0) / determinant).coerceIn(0.0, arcLength)
        return Cubic(p0, p0 + task.start * alpha0, p3 + task.end * alpha1, p3)
    }
}
