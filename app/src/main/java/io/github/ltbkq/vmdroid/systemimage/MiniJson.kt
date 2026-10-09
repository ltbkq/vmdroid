/*
 * VMDroid - minimal JSON + atomic-file helpers for the pure-JVM image layer.
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * 纯 JVM Kotlin：禁止 import 任何 android.* 类型 —— 可脱离 Android SDK 用 kotlinc 独立编译
 * （systemimage-selftest/SelfTest.kt 覆盖本文件的解析/序列化路径）。
 *
 * 为什么不用 org.json：`active.json` / `*.meta.json` / `image.log` 的读写位于
 * **纯逻辑层**（SystemImageStore / LogDiagnostics），必须能在无 Android SDK 的机器上
 * 编译测试；org.json 只在 Android 平台上存在。因此自带一个够用的解析器/序列化器
 * （DESIGN §2.3/§6.4/§16.3 的 JSON 都是扁平或一层嵌套的简单结构）。
 *
 * 同时提供 temp + fsync + rename 的原子文本写 —— §16.3 写入协议要求
 * "*.meta.json 任何时刻磁盘上都是完整 JSON"。
 */
package io.github.ltbkq.vmdroid.systemimage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 极简 JSON 解析/序列化（仅 stdlib）。
 *
 * 解析产出：`Map<String, Any?>` / `List<Any?>` / `String` / `Long`（整数）/
 * `Double`（非整数）/ `Boolean` / `null`。非法输入抛 [IllegalArgumentException]。
 * 序列化接受同样的类型（外加 `Number`/`Iterable`/`Array` 的常规用法）。
 */
object MiniJson {

    /** 解析一段 JSON 文本；尾部残留内容视为错误。 */
    fun parse(text: String): Any? = Parser(text).parseDocument()

    /** 解析为对象（map）；顶层不是 `{...}` 时抛 [IllegalArgumentException]。 */
    fun parseObject(text: String): Map<String, Any?> {
        val raw = parse(text)
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("expected a JSON object at the top level")
        val out = LinkedHashMap<String, Any?>(map.size)
        for ((k, v) in map) out[k.toString()] = v
        return out
    }

    /** 紧凑序列化（无缩进；UTF-8 直出，不转义非 ASCII）。 */
    fun write(value: Any?): String {
        val sb = StringBuilder(64)
        writeValue(value, sb)
        return sb.toString()
    }

    /** `map["key"]` 的字符串视图（非 String 返回 null）。 */
    fun str(map: Map<*, *>?, key: String): String? = map?.get(key) as? String

    /** `map["key"]` 的整数视图（Long/Int/Double 整数值均接受）。 */
    fun long(map: Map<*, *>?, key: String): Long? = when (val v = map?.get(key)) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong() else null
        is String -> v.toLongOrNull()
        else -> null
    }

    /** `map["key"]` 的布尔视图。 */
    fun bool(map: Map<*, *>?, key: String): Boolean? = map?.get(key) as? Boolean

    // ---------------------------------------------------------------- 序列化

    private fun writeValue(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(v, sb)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Double -> if (v.isNaN() || v.isInfinite()) sb.append("null") else sb.append(v.toString())
            is Float -> if (v.isNaN() || v.isInfinite()) sb.append("null") else sb.append(v.toString())
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(k.toString(), sb)
                    sb.append(':')
                    writeValue(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(item, sb)
                }
                sb.append(']')
            }
            is Array<*> -> writeValue(v.toList(), sb)
            else -> writeString(v.toString(), sb)
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c.code < 0x20 -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    // ---------------------------------------------------------------- 解析

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): Any? {
            val v = value()
            ws()
            if (i != s.length) fail("trailing content")
            return v
        }

        private fun fail(msg: String): Nothing = throw IllegalArgumentException("$msg at offset $i")

        private fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun at(c: Char): Boolean = i < s.length && s[i] == c

        private fun value(): Any? {
            ws()
            if (i >= s.length) fail("unexpected end of input")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                '-', in '0'..'9' -> num()
                else -> fail("unexpected char '$c'")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("bad literal")
            i += word.length
            return v
        }

        private fun obj(): LinkedHashMap<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            ws()
            if (at('}')) {
                i++
                return m
            }
            while (true) {
                ws()
                if (!at('"')) fail("expected string key")
                val k = str()
                ws()
                if (!at(':')) fail("expected ':'")
                i++
                m[k] = value()
                ws()
                when {
                    at(',') -> i++
                    at('}') -> {
                        i++
                        return m
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(): ArrayList<Any?> {
            val a = ArrayList<Any?>()
            i++ // [
            ws()
            if (at(']')) {
                i++
                return a
            }
            while (true) {
                a.add(value())
                ws()
                when {
                    at(',') -> i++
                    at(']') -> {
                        i++
                        return a
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun str(): String {
            val sb = StringBuilder()
            i++ // opening quote
            while (true) {
                if (i >= s.length) fail("unterminated string")
                when (val c = s[i]) {
                    '"' -> {
                        i++
                        return sb.toString()
                    }
                    '\\' -> {
                        i++
                        if (i >= s.length) fail("bad escape")
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) fail("bad \\u escape")
                                val hex = s.substring(i + 1, i + 5)
                                val code = hex.toIntOrNull(16) ?: fail("bad \\u escape '$hex'")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                        i++
                    }
                    else -> {
                        if (c.code < 0x20) fail("control char in string")
                        sb.append(c)
                        i++
                    }
                }
            }
        }

        private fun num(): Any? {
            val start = i
            if (at('-')) i++
            while (i < s.length && s[i] in '0'..'9') i++
            var isDouble = false
            if (at('.')) {
                isDouble = true
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (at('e') || at('E')) {
                isDouble = true
                i++
                if (at('+') || at('-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val text = s.substring(start, i)
            if (text.isEmpty() || text == "-") fail("bad number")
            return if (isDouble) {
                text.toDoubleOrNull() ?: fail("bad number '$text'")
            } else {
                text.toLongOrNull() ?: text.toDoubleOrNull() ?: fail("bad number '$text'")
            }
        }
    }
}

/**
 * temp + fsync + rename 原子文本写（DESIGN §16.3 写入协议；§6.4 `.meta.json` 同样适用）。
 * 任一步失败都抛 [IOException]，绝不留下半截目标文件（半截只会出现在 `.tmp` 上）。
 */
object AtomicFiles {
    private const val TMP_SUFFIX = ".tmp"

    /** 原子写 [text] 到 [target]（父目录自动创建）。 */
    @JvmStatic
    fun write(target: File, text: String) {
        ensureParent(target)
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            moveOrReplace(tmp, target)
        } catch (e: IOException) {
            tmp.delete()
            throw IOException("cannot write $target: ${e.message}", e)
        } catch (e: SecurityException) {
            tmp.delete()
            throw IOException("cannot write $target: $e", e)
        }
    }

    /** 原子替换移动 [src] → [target]（同文件系统；目标存在则覆盖）。 */
    @JvmStatic
    fun move(src: File, target: File) {
        ensureParent(target)
        try {
            moveOrReplace(src, target)
        } catch (e: IOException) {
            throw IOException("cannot move $src -> $target: ${e.message}", e)
        }
    }

    private fun ensureParent(target: File) {
        val parent = target.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("cannot create directory $parent")
        }
    }

    private fun moveOrReplace(src: File, target: File) {
        try {
            java.nio.file.Files.move(
                src.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                src.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
