package com.bettergi.pocket.root

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.bettergi.pocket.log.AppLog
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * root 注入桥：探测 su、释放并启动 bgroot helper、经 LocalSocket 通信。
 *
 * 设计要点：
 * - 不绑定任何 root 方案（Magisk / KernelSU / APatch / 原生 su 均走通用 su 探测）
 * - 不做授权引导，失败由上层如实报告状态
 * - helper 与 app 同生命周期：stop() 时发 QUIT 并销毁进程
 */
object RootBridge {

    private const val TAG = "BetterGI.Root"
    private const val HELPER_ASSET = "root/arm64-v8a/bgroot"
    private const val CONNECT_TIMEOUT_MS = 4000L
    private const val PING_TIMEOUT_MS = 3000L

    @Volatile
    private var running = false
    @Volatile
    private var socket: LocalSocket? = null
    @Volatile
    private var output: OutputStream? = null
    @Volatile
    private var reader: BufferedReader? = null
    @Volatile
    private var helperProcess: Process? = null

    private var appContext: Context? = null
    @Volatile
    private var keepAliveApplied = false
    @Volatile
    private var suPath: String? = null
    @Volatile
    private var uinputAvailable: Boolean? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun isRunning(): Boolean = running

    /** uinput 注入是否可用；null = 尚未探测到。 */
    fun uinputReady(): Boolean? = uinputAvailable

    /** uinput 注入中途失效时降级到 input 命令模式（只允许 true -> false）。 */
    fun downgradeToInputMode() {
        if (uinputAvailable == true) {
            uinputAvailable = false
            AppLog.w(TAG, "uinput tap failed, downgraded to input-cmd mode")
        }
    }

    /** 启动 helper 并完成握手；成功返回 true */
    @Synchronized
    fun start(): Boolean {
        val ctx = appContext ?: return false
        stop()

        val dir = File(ctx.filesDir, "root")
        dir.mkdirs()
        val helper = File(dir, "bgroot")
        if (!releaseHelper(helper)) return false
        helper.setExecutable(true, false)

        val sockPath = File(dir, "sock").absolutePath
        File(sockPath).delete()

        val su = resolveSu()
        // 清理可能残留的旧 helper：避免文件被占用（ETXTBSY）与多实例争用 socket/uinput
        runCommand(ctx, "$su -c \"pkill -f bgroot 2>/dev/null; sleep 0.2\"", 3000L)

        val cmd = "$su -c \"$helper --server --sock $sockPath --uid ${android.os.Process.myUid()} --app-pid ${android.os.Process.myPid()}\""
        val process = try {
            ProcessBuilder("sh", "-c", cmd).redirectErrorStream(false).start()
        } catch (e: Exception) {
            AppLog.e(TAG, "start helper failed", e)
            return false
        }
        helperProcess = process
        drainHelperOutput(process, "helper")

        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        var firstFail = true
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(60)
            if (!process.isAlive) {
                AppLog.e(TAG, "helper exited early, code=${process.exitValue()}")
                return false
            }
            val s = tryConnect(sockPath, logFailure = firstFail).also { if (it == null) firstFail = false } ?: continue
            socket = s
            output = s.outputStream
            reader = BufferedReader(InputStreamReader(s.inputStream), 1024)
            val pong = request("PING", PING_TIMEOUT_MS)?.trim()
            if (pong == "OK pong" || pong == "pong") {
                running = true
                AppLog.i(TAG, "root backend connected")
                detectUinputMode()
                applyKeepAlive()
                return true
            }
            AppLog.w(TAG, "PING handshake failed: $pong")
            try {
                s.close()
            } catch (_: Exception) {
            }
            return false
        }
        AppLog.e(TAG, "connect to helper timed out")
        return false
    }

    /** 解析可用的 su 绝对路径（多路径探测，不依赖 app 的 PATH） */
    private fun resolveSu(): String {
        suPath?.let { return it }
        val ctx = appContext ?: return "su"
        val candidates = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/debug_ramdisk/su",
        )
        for (p in candidates) {
            if (runCommand(ctx, "test -x $p && echo yes", 1500L)?.trim() == "yes") {
                suPath = p
                return p
            }
        }
        val found = runCommand(ctx, "command -v su", 1500L)?.trim()
        return if (!found.isNullOrEmpty()) found else "su"
    }

    /** 每次启动都从 assets 覆盖释放，保证与当前安装包一致 */
    /** 释放 helper：先写临时文件再 rename 替换（旧进程持有的 inode 换新，避免 ETXTBSY） */
    private fun releaseHelper(dest: File): Boolean {
        val ctx = appContext ?: return false
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        return try {
            ctx.assets.open(HELPER_ASSET).use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            tmp.setExecutable(true, false)
            if (!tmp.renameTo(dest)) {
                dest.delete()
                if (!tmp.renameTo(dest)) {
                    AppLog.e(TAG, "rename helper failed")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "release helper failed", e)
            try {
                tmp.delete()
            } catch (_: Throwable) {
            }
            false
        }
    }

    private fun tryConnect(path: String, logFailure: Boolean = false): LocalSocket? {
        return try {
            val s = LocalSocket()
            s.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            s
        } catch (e: Exception) {
            if (logFailure) AppLog.w(TAG, "connect $path failed: ${e.message}")
            null
        }
    }

    /** 发送一行指令并读取一行回复；失败返回 null 并标记断线 */
    @Synchronized
    fun request(cmd: String, timeoutMs: Long = 5000L): String? {
        val sock = socket ?: return null
        val out = output ?: return null
        val rd = reader ?: return null
        return try {
            sock.soTimeout = timeoutMs.toInt()
            out.write((cmd + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            val line = rd.readLine() ?: run {
                running = false
                null
            }
            line
        } catch (e: Exception) {
            AppLog.w(TAG, "request failed: $cmd -> ${e.message}")
            running = false
            null
        }
    }

    fun tap(x: Int, y: Int, durationMs: Long): Boolean {
        val resp = request("TAP $x $y $durationMs", 2000L)
        if (resp == null || !resp.startsWith("OK")) {
            AppLog.w(TAG, "tap failed at $x,$y (resp=$resp)")
            return false
        }
        return true
    }

    fun back(): Boolean {
        val resp = request("BACK", 2000L)
        if (resp == null || !resp.startsWith("OK")) {
            AppLog.w(TAG, "back failed (resp=$resp)")
            return false
        }
        return true
    }

    fun foreground(): String? {
        val resp = request("FG", 3000L) ?: return null
        if (resp.startsWith("OK ")) {
            val fg = resp.removePrefix("OK ").trim()
            return if (fg == "unknown") null else fg
        }
        return null
    }

    fun screenSize(): Pair<Int, Int>? {
        val resp = request("PROBE", 2000L) ?: return null
        if (resp.startsWith("OK ")) {
            val parts = resp.removePrefix("OK ").split(" ")
            val w = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val h = parts.getOrNull(1)?.toIntOrNull() ?: return null
            return w to h
        }
        return null
    }

    /** 探测 uinput 可用性并记录注入模式（uinput / input-cmd 兜底）。 */
    private fun detectUinputMode() {
        val probe = request("PROBE", 2000L)
        uinputAvailable = if (probe != null && probe.startsWith("OK ")) {
            probe.removePrefix("OK ").split(" ").getOrNull(2) == "uinput_ok"
        } else {
            null
        }
        AppLog.i(
            TAG,
            "injection mode: " + when (uinputAvailable) {
                true -> "uinput (fast)"
                false -> "input-cmd (uinput unavailable, fallback)"
                null -> "unknown (PROBE failed)"
            },
        )
    }

    /** input 命令模式点击（屏幕坐标，经 su 执行系统命令注入，全 root 方案兼容）。 */
    fun inputTap(x: Int, y: Int): Boolean {
        val ctx = appContext ?: return false
        val cmd = "${resolveSu()} -c \"input tap $x $y\""
        if (runCommand(ctx, cmd, 2500L) == null) {
            AppLog.w(TAG, "input tap $x,$y failed")
            return false
        }
        return true
    }

    /** input 命令模式返回键。 */
    fun inputBack(): Boolean {
        val ctx = appContext ?: return false
        val cmd = "${resolveSu()} -c \"input keyevent 4\""
        if (runCommand(ctx, cmd, 2500L) == null) {
            AppLog.w(TAG, "input back failed")
            return false
        }
        return true
    }

    /** 加入电池白名单 + 提升为 active 待机桶；每进程只应用一次 */
    private fun applyKeepAlive() {
        if (keepAliveApplied) return
        val pkg = appContext?.packageName ?: return
        if (request("KEEPALIVE $pkg", 3000L)?.startsWith("OK") == true) {
            keepAliveApplied = true
            AppLog.i(TAG, "keep-alive applied for $pkg")
        }
    }

    @Synchronized
    fun stop() {
        if (running) AppLog.i(TAG, "root backend stopping")
        running = false
        try {
            output?.write("QUIT\n".toByteArray(Charsets.UTF_8))
            output?.flush()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        try {
            helperProcess?.destroy()
        } catch (_: Exception) {
        }
        socket = null
        output = null
        reader = null
        helperProcess = null
    }

    /** 逐行消费 helper 的 stdout/stderr：写满管道会阻塞 helper 主循环，必须读 */
    private fun drainHelperOutput(process: Process, prefix: String) {
        for (stream in listOf(process.inputStream, process.errorStream)) {
            val thread = Thread {
                try {
                    stream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line.isNotBlank()) AppLog.i(TAG, "[$prefix] $line")
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            thread.isDaemon = true
            thread.start()
        }
    }

    private fun runCommand(ctx: Context, command: String, timeoutMs: Long): String? {
        val proc = try {
            ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return null
        }
        return try {
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                proc.destroy()
            }
            out
        } catch (e: Exception) {
            null
        }
    }
}
