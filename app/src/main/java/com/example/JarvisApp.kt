package com.example

import android.app.Application
import android.os.Build
import android.webkit.WebView
import java.io.File

class JarvisApp : Application() {

    companion object {
        init {
            try {
                // Prevent Mesa/DRI from probing missing /dev/dri render node in virtualized environments
                android.system.Os.setenv("LIBGL_DRI3_DISABLE", "1", true)
                android.system.Os.setenv("LIBGL_ALWAYS_SOFTWARE", "1", true)
                android.system.Os.setenv("MESA_LOADER_DRIVER_OVERRIDE", "swrast", true)
                android.system.Os.setenv("GALLIUM_DRIVER", "softpipe", true)
                android.system.Os.setenv("EGL_LOG_LEVEL", "fatal", true)
                android.system.Os.setenv("MESA_DEBUG", "0", true)
            } catch (_: Throwable) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val processName = getProcessName()
            if (packageName != processName) {
                WebView.setDataDirectorySuffix(processName)
            }
        }
        ensureWebViewCacheDirectories()
    }

    private fun ensureWebViewCacheDirectories() {
        try {
            // Chromium tries to enumerate or create code cache dirs; ensure they exist beforehand
            val jsCache = File(cacheDir, "WebView/Default/HTTP Cache/Code Cache/js")
            if (!jsCache.exists()) {
                jsCache.mkdirs()
            }
            val wasmCache = File(cacheDir, "WebView/Default/HTTP Cache/Code Cache/wasm")
            if (!wasmCache.exists()) {
                wasmCache.mkdirs()
            }
        } catch (_: Throwable) {}
    }
}

