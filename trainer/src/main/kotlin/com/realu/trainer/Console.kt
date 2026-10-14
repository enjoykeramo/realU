package com.realu.trainer

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.nio.charset.Charset
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Windows 中文控制台乱码处理。
 *
 * 乱码的根源是"程序输出的字节编码"与"控制台代码页"不一致:
 * 中文 Windows 控制台默认代码页 936(GBK),而 JVM/IDE 常按 UTF-8 输出。
 * 这里按优先级决定输出编码:配置 console.encoding > JVM 的 stdout.encoding > 控制台自身编码 > Windows 默认 GBK。
 * 配套的 train.bat 会先执行 `chcp 65001` 切到 UTF-8 并传入 -Dstdout.encoding=UTF-8,两者一致即不乱码。
 */
object Console {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var logWriter: PrintWriter? = null
    var charset: Charset = Charsets.UTF_8
        private set

    fun init(preference: String = "auto") {
        // 选出的编码若连中文都表示不了(如 Linux 下 LANG=C 得到的 US-ASCII),中文会变成 "?",改用 UTF-8
        charset = resolve(preference).takeIf { it.newEncoder().canEncode("中文") } ?: Charsets.UTF_8
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, charset))
        System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, charset))
    }

    private fun resolve(pref: String): Charset {
        if (!pref.equals("auto", ignoreCase = true)) {
            return runCatching { Charset.forName(pref) }.getOrElse { Charsets.UTF_8 }
        }
        System.getProperty("stdout.encoding")?.let { name -> runCatching { return Charset.forName(name) } }
        System.console()?.let { c -> runCatching { return c.charset() } }
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        return if (isWindows) Charset.forName("GBK") else Charsets.UTF_8
    }

    /** 日志同时写入输出目录下的 train.log(始终 UTF-8,不受控制台编码影响)。 */
    fun openLog(file: File) {
        file.parentFile?.mkdirs()
        logWriter = PrintWriter(file.bufferedWriter(Charsets.UTF_8), true)
    }

    fun closeLog() {
        logWriter?.close()
        logWriter = null
    }

    @Synchronized
    fun info(msg: String) = emit("INFO", msg)

    @Synchronized
    fun warn(msg: String) = emit("警告", msg)

    @Synchronized
    fun error(msg: String) = emit("错误", msg)

    private fun emit(level: String, msg: String) {
        val line = "${LocalTime.now().format(timeFmt)} [$level] $msg"
        if (level == "错误") System.err.println(line) else println(line)
        logWriter?.println(line)
    }

    /** 不带时间戳的原样输出(报告摘要等)。 */
    @Synchronized
    fun raw(msg: String) {
        println(msg)
        logWriter?.println(msg)
    }
}
