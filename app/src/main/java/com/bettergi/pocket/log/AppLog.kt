package com.bettergi.pocket.log

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 统一日志门面：logcat + 文件（按天切分，保留最近 [KEEP_DAYS] 天）+ 悬浮球面板。
 *
 * 设计：
 * - 调用线程只做格式化与入队，文件写入在单线程 executor 上，不阻塞主线程/注入线程
 * - 面板 sink 由悬浮球注册，未打开面板时其回调自然丢弃
 * - 文件位置：<外部私有目录>/files/logs/bettergi-YYYY-MM-DD.log，root 用户可直接取出
 */
object AppLog {

    interface Sink {
        fun onLog(line: String)
    }

    private const val KEEP_DAYS = 3
    private val sinks = CopyOnWriteArrayList<Sink>()
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bg-log-writer").apply { isDaemon = true }
    }
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    @Volatile
    private var logDir: File? = null

    fun init(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "logs")
        if (!dir.exists() && !dir.mkdirs()) return
        logDir = dir
        writer.execute { cleanupOldLogs(dir) }
    }

    fun addSink(sink: Sink) {
        if (!sinks.contains(sink)) sinks.add(sink)
    }

    fun removeSink(sink: Sink) {
        sinks.remove(sink)
    }

    fun i(tag: String, message: String) = write(Log.INFO, tag, message)

    fun d(tag: String, message: String) = write(Log.DEBUG, tag, message)

    fun w(tag: String, message: String) = write(Log.WARN, tag, message)

    fun e(tag: String, message: String) = write(Log.ERROR, tag, message)

    fun e(tag: String, message: String, error: Throwable) =
        write(Log.ERROR, tag, "$message: ${error::class.java.simpleName}: ${error.message}")

    private fun write(priority: Int, tag: String, message: String) {
        Log.println(priority, tag, message)
        val line = "${timeFormat.format(Date())} ${levelTag(priority)} [$tag] $message"
        if (priority != Log.DEBUG) {
            for (sink in sinks) {
                try {
                    sink.onLog(line)
                } catch (_: Throwable) {
                }
            }
        }
        val dir = logDir ?: return
        writer.execute {
            try {
                File(dir, "bettergi-${dayFormat.format(Date())}.log").appendText(line + "\n")
            } catch (_: Throwable) {
            }
        }
    }

    private fun levelTag(priority: Int): String = when (priority) {
        Log.ERROR -> "E"
        Log.WARN -> "W"
        Log.INFO -> "I"
        Log.DEBUG -> "D"
        else -> "V"
    }

    private fun cleanupOldLogs(dir: File) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600L * 1000L
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (!f.name.startsWith("bettergi-") || !f.name.endsWith(".log")) continue
            if (f.lastModified() < cutoff) {
                try {
                    f.delete()
                } catch (_: Throwable) {
                }
            }
        }
    }
}
