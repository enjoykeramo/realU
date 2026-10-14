package com.realu.core.util

/**
 * 极简 JSON 读写。core 模块刻意不引入第三方依赖,方便原样放进 Android 工程。
 * 解析结果:Map<String, Any?> / List<Any?> / String / Double / Long / Boolean / null。
 */
object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (p.i != text.length) p.fail("多余的字符")
        return v
    }

    fun write(value: Any?, pretty: Boolean = true): String {
        val sb = StringBuilder()
        writeValue(sb, value, if (pretty) 0 else -1)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, v: Any?, indent: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> writeDouble(sb, v)
            is Float -> writeDouble(sb, v.toDouble())
            is Number -> writeDouble(sb, v.toDouble())
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, indent + 1)
                    writeString(sb, k.toString())
                    sb.append(if (indent >= 0) ": " else ":")
                    writeValue(sb, value, if (indent >= 0) indent + 1 else -1)
                }
                newline(sb, indent)
                sb.append('}')
            }
            is Iterable<*> -> writeArray(sb, v.toList(), indent)
            is IntArray -> writeFlatArray(sb, v.toList())
            is DoubleArray -> writeFlatArray(sb, v.toList())
            is FloatArray -> writeFlatArray(sb, v.toList())
            is BooleanArray -> writeFlatArray(sb, v.toList())
            is Array<*> -> writeArray(sb, v.toList(), indent)
            else -> writeString(sb, v.toString())
        }
    }

    /** 纯数字数组写在一行里,模型文件(大量树节点)体积能小很多。 */
    private fun writeFlatArray(sb: StringBuilder, list: List<Any?>) {
        sb.append('[')
        list.forEachIndexed { i, x ->
            if (i > 0) sb.append(',')
            writeValue(sb, x, -1)
        }
        sb.append(']')
    }

    private fun writeArray(sb: StringBuilder, list: List<Any?>, indent: Int) {
        if (list.isEmpty()) { sb.append("[]"); return }
        if (list.all { it is Number || it is Boolean }) {
            writeFlatArray(sb, list)
            return
        }
        sb.append('[')
        list.forEachIndexed { i, x ->
            if (i > 0) sb.append(',')
            newline(sb, indent + 1)
            writeValue(sb, x, if (indent >= 0) indent + 1 else -1)
        }
        newline(sb, indent)
        sb.append(']')
    }

    private fun newline(sb: StringBuilder, indent: Int) {
        if (indent < 0) return
        sb.append('\n')
        repeat(indent) { sb.append("  ") }
    }

    private fun writeDouble(sb: StringBuilder, d: Double) {
        when {
            d.isNaN() || d.isInfinite() -> sb.append("null")
            d == Math.rint(d) && Math.abs(d) < 1e15 -> sb.append(d.toLong())
            else -> sb.append(d.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(msg: String): Nothing = throw IllegalArgumentException("JSON 解析失败(位置 $i):$msg")

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            if (i >= s.length) fail("意外结束")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("非法字符 '$c'")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("期望 $word")
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') fail("期望键名")
                val k = str()
                skipWs()
                if (i >= s.length || s[i] != ':') fail("期望 ':'")
                i++
                m[k] = value()
                skipWs()
                if (i >= s.length) fail("对象未闭合")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> fail("期望 ',' 或 '}'")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                skipWs()
                if (i >= s.length) fail("数组未闭合")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> fail("期望 ',' 或 ']'")
                }
            }
        }

        private fun str(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) fail("字符串未闭合")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("转义未完成")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("\\u 转义不完整")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("未知转义 \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }
    }
}

// ---- 读取辅助:模型文件解析时用 ----

@Suppress("UNCHECKED_CAST")
fun Any?.asJsonMap(): Map<String, Any?> = this as? Map<String, Any?> ?: error("期望 JSON 对象")

@Suppress("UNCHECKED_CAST")
fun Any?.asJsonList(): List<Any?> = this as? List<Any?> ?: error("期望 JSON 数组")

fun Any?.asDouble(): Double = (this as? Number)?.toDouble() ?: Double.NaN

fun Any?.asInt(): Int = (this as? Number)?.toInt() ?: error("期望整数")

fun Map<String, Any?>.str(key: String): String = this[key] as? String ?: error("缺少字段 $key")

fun Map<String, Any?>.dbl(key: String): Double = (this[key] as? Number)?.toDouble() ?: error("缺少字段 $key")

fun Map<String, Any?>.int(key: String): Int = (this[key] as? Number)?.toInt() ?: error("缺少字段 $key")

fun Map<String, Any?>.obj(key: String): Map<String, Any?> = this[key].asJsonMap()

fun Map<String, Any?>.list(key: String): List<Any?> = this[key].asJsonList()

fun List<Any?>.toIntArray(): IntArray = IntArray(size) { this[it].asInt() }

fun List<Any?>.toDoubleArray(): DoubleArray = DoubleArray(size) { this[it].asDouble() }
