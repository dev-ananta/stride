package com.stride.domain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Compact binary format for GPS tracks (~21 bytes/point, so a 3-hour ride at 1 Hz is ~230 KB).
 * Also used for the crash-safe "recording in progress" file: points are appended one at a time
 * and a half-written last record is simply ignored on read.
 */
object TrackCodec {
    private const val MAGIC = 0x50425452 // "PBTR"
    private const val VERSION = 1
    private const val FLAG_RESUME = 1
    private const val FLAG_NO_ELE = 2

    fun writeHeader(out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeInt(MAGIC); d.writeByte(VERSION); d.flush()
    }

    fun writePoint(out: OutputStream, p: TrackPoint) {
        val d = DataOutputStream(out)
        d.writeLong(p.time)
        d.writeInt(Math.round(p.lat * 1e7).toInt())
        d.writeInt(Math.round(p.lon * 1e7).toInt())
        d.writeFloat(if (p.ele.isNaN()) 0f else p.ele.toFloat())
        d.writeByte(Math.min(255, Math.round(p.accuracy)).coerceAtLeast(0))
        d.writeByte((if (p.resume) FLAG_RESUME else 0) or (if (p.ele.isNaN()) FLAG_NO_ELE else 0))
        d.flush()
    }

    fun encode(points: List<TrackPoint>): ByteArray {
        val bos = ByteArrayOutputStream(5 + points.size * 22)
        writeHeader(bos)
        for (p in points) writePoint(bos, p)
        return bos.toByteArray()
    }

    fun decode(bytes: ByteArray): List<TrackPoint> = read(ByteArrayInputStream(bytes))

    fun read(input: InputStream): List<TrackPoint> {
        val d = DataInputStream(input.buffered())
        val out = ArrayList<TrackPoint>()
        try {
            if (d.readInt() != MAGIC) return emptyList()
            d.readByte()
            while (true) {
                val t = d.readLong()
                val lat = d.readInt() / 1e7
                val lon = d.readInt() / 1e7
                val ele = d.readFloat()
                val acc = d.readUnsignedByte()
                val flags = d.readUnsignedByte()
                out += TrackPoint(t, lat, lon,
                    if (flags and FLAG_NO_ELE != 0) Double.NaN else ele.toDouble(),
                    acc.toFloat(), flags and FLAG_RESUME != 0)
            }
        } catch (_: EOFException) {
            // End of file, or a torn last record after a crash — keep everything complete before it.
        }
        return out
    }
}

/**
 * Tiny JSON reader/writer for the app's small metadata files (activities index, routes, segments,
 * goals, settings). Self-contained so the storage layer is fully unit-testable off-device.
 * Values: null, Boolean, Double, String, List<Any?>, Map<String, Any?>.
 */
object Json {
    fun write(v: Any?): String = StringBuilder().also { w(it, v) }.toString()

    private fun w(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null")
                else if (d == Math.rint(d) && Math.abs(d) < 9e15) sb.append(d.toLong()) else sb.append(d)
            }
            is String -> str(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(','); first = false
                    str(sb, k.toString()); sb.append(':'); w(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) { if (!first) sb.append(','); first = false; w(sb, x) }
                sb.append(']')
            }
            else -> str(sb, v.toString())
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
            else -> sb.append(ch)
        }
        sb.append('"')
    }

    fun parse(s: String): Any? {
        val p = Parser(s)
        p.ws()
        val v = p.value()
        p.ws()
        require(p.i == s.length) { "Trailing characters at ${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "Unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("Bad char '$c' at $i")
            }
        }
        fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "Expected $word at $i" }
            i += word.length; return v
        }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
        fun string(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "Unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out += value(); ws()
                when (s[i++]) { ',' -> continue; ']' -> return out; else -> throw IllegalArgumentException("Expected , or ] at ${i - 1}") }
            }
        }
        fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws(); val k = string(); ws()
                require(s[i++] == ':') { "Expected : at ${i - 1}" }
                out[k] = value(); ws()
                when (s[i++]) { ',' -> continue; '}' -> return out; else -> throw IllegalArgumentException("Expected , or } at ${i - 1}") }
            }
        }
    }
}

/** Mapping between domain objects and JSON-friendly maps. Unknown/missing fields fall back to defaults. */
object Mappers {
    private fun Map<*, *>.s(k: String, d: String = "") = this[k] as? String ?: d
    private fun Map<*, *>.d(k: String, d: Double = 0.0) = (this[k] as? Number)?.toDouble() ?: d
    private fun Map<*, *>.l(k: String, d: Long = 0) = (this[k] as? Number)?.toLong() ?: d
    private fun Map<*, *>.b(k: String, d: Boolean) = this[k] as? Boolean ?: d
    private inline fun <reified E : Enum<E>> Map<*, *>.e(k: String, d: E): E =
        (this[k] as? String)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: d

    fun summary(s: Summary) = mapOf("dist" to s.distanceM, "moving" to s.movingMs, "elapsed" to s.elapsedMs, "gain" to s.elevGainM, "max" to s.maxSpeed)
    fun summary(m: Map<*, *>) = Summary(m.d("dist"), m.l("moving"), m.l("elapsed"), m.d("gain"), m.d("max"))

    fun activity(a: Activity) = mapOf(
        "id" to a.id, "name" to a.name, "sport" to a.sport.name, "start" to a.startTime,
        "summary" to summary(a.summary), "notes" to a.notes, "hideEnds" to a.hideEnds,
    )
    fun activity(m: Map<*, *>) = Activity(
        m.s("id"), m.s("name"), m.e("sport", Sport.RUN), m.l("start"),
        (m["summary"] as? Map<*, *>)?.let(::summary) ?: Summary.EMPTY, m.s("notes"), m.b("hideEnds", true),
    )

    /** Routes/segments are small (simplified), so their points live inline as [lat, lon, ele] triples. */
    fun points(p: List<TrackPoint>) = p.map { listOf(it.lat, it.lon, if (it.ele.isNaN()) null else it.ele) }
    fun points(l: Any?): List<TrackPoint> = (l as? List<*>).orEmpty().mapNotNull { x ->
        val t = x as? List<*> ?: return@mapNotNull null
        val lat = (t.getOrNull(0) as? Number)?.toDouble() ?: return@mapNotNull null
        val lon = (t.getOrNull(1) as? Number)?.toDouble() ?: return@mapNotNull null
        TrackPoint(0, lat, lon, (t.getOrNull(2) as? Number)?.toDouble() ?: Double.NaN)
    }

    fun route(r: Route) = mapOf("id" to r.id, "name" to r.name, "sport" to r.sport.name, "created" to r.createdAt, "pts" to points(r.points))
    fun route(m: Map<*, *>) = Route(m.s("id"), m.s("name"), m.e("sport", Sport.RUN), points(m["pts"]), m.l("created"))

    fun segment(s: Segment) = mapOf("id" to s.id, "name" to s.name, "sport" to s.sport.name, "created" to s.createdAt, "pts" to points(s.points))
    fun segment(m: Map<*, *>) = Segment(m.s("id"), m.s("name"), m.e("sport", Sport.RUN), points(m["pts"]), m.l("created"))

    fun effort(e: SegmentEffort) = mapOf("seg" to e.segmentId, "act" to e.activityId, "start" to e.startTime, "ms" to e.elapsedMs, "i" to e.startIndex, "j" to e.endIndex)
    fun effort(m: Map<*, *>) = SegmentEffort(m.s("seg"), m.s("act"), m.l("start"), m.l("ms"), m.l("i").toInt(), m.l("j").toInt())

    fun goal(g: Goal) = mapOf("id" to g.id, "sport" to g.sport?.name, "metric" to g.metric.name, "period" to g.period.name, "target" to g.target)
    fun goal(m: Map<*, *>) = Goal(
        m.s("id"), (m["sport"] as? String)?.let { n -> Sport.entries.firstOrNull { it.name == n } },
        m.e("metric", GoalMetric.DISTANCE), m.e("period", GoalPeriod.WEEK), m.d("target"),
    )

    fun record(r: BestEffortRecord) = mapOf("label" to r.label, "dist" to r.distanceM, "ms" to r.timeMs, "act" to r.activityId, "date" to r.date)
    fun record(m: Map<*, *>) = BestEffortRecord(m.s("label"), m.d("dist"), m.l("ms"), m.s("act"), m.l("date"))

    fun settings(s: Settings) = mapOf(
        "units" to s.units.name, "weight" to s.weightKg, "name" to s.athleteName, "autoPause" to s.autoPause,
        "safety" to s.safetyEnabled, "interval" to s.safetyIntervalMin, "hideEnds" to s.hideEndsMeters,
        "contacts" to s.safetyContacts.map { mapOf("name" to it.name, "phone" to it.phone) },
        "voice" to s.voiceCues, "still" to s.stillAlertMin,
    )
    fun settings(m: Map<*, *>) = Settings(
        m.e("units", Units.METRIC), m.d("weight", 70.0), m.s("name"), m.b("autoPause", true), m.b("safety", false),
        (m["contacts"] as? List<*>).orEmpty().mapNotNull { c -> (c as? Map<*, *>)?.let { SafetyContact(it.s("name"), it.s("phone")) } },
        m.l("interval", 15).toInt(), m.l("hideEnds", 200).toInt(), m.b("voice", true), m.l("still", 10).toInt(),
    )
}
