package com.bettergi.pocket

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log
import com.bettergi.pocket.root.RootBridge
import com.bettergi.pocket.recognition.ocr.OcrFactory
import com.bettergi.pocket.recognition.opencv.OpenCvRuntime
import java.io.File

class PocketApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RootBridge.attach(this)
        if (currentProcessName() != packageName) return
        if (!OpenCvRuntime.ensureLoaded()) {
            Log.e(TAG, "OpenCV initialization failed")
        }
        OcrFactory.init(this)
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName()
        }
        val pid = Process.myPid()
        val processes = (getSystemService(ACTIVITY_SERVICE) as? ActivityManager)?.runningAppProcesses
        processes?.firstOrNull { it.pid == pid }?.processName?.let { return it }
        return try {
            File("/proc/self/cmdline").readBytes()
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
                .ifBlank { packageName }
        } catch (_: Exception) {
            packageName
        }
    }

    private companion object {
        private const val TAG = "BetterGI.App"
    }
}
