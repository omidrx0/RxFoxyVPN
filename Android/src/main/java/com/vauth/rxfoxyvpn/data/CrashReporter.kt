package com.vauth.rxfoxyvpn.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val CRASH_FILE_NAME = "last_crash.txt"
    private const val PREF_NAME = "rxfoxyvpn_crash_store"
    private const val KEY_LAST_CRASH = "last_crash_str"

    var lastCrashReport: String? = null
        private set

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashFile(appContext, thread, throwable) }

            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Runtime.getRuntime().exit(1)
            }
        }
    }

    fun replayLastCrashIfAny(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedCrash = prefs.getString(KEY_LAST_CRASH, null)

        val file = crashFile(context)
        val fileCrash = if (file.exists()) runCatching { file.readText() }.getOrNull() else null
        runCatching { file.delete() }

        val text = fileCrash ?: savedCrash
        if (!text.isNullOrBlank()) {
            lastCrashReport = text
            AppLogger.e(TAG, "app crashed on previous run; captured stack trace follows:")
            text.lineSequence().forEach { line ->
                if (line.isNotBlank()) AppLogger.e(TAG, line)
            }
        }
    }

    fun clearLastCrash(context: Context) {
        lastCrashReport = null
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().remove(KEY_LAST_CRASH).apply()
        runCatching { crashFile(context).delete() }
    }

    private fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable) {
        val writer = StringWriter()
        PrintWriter(writer).use { pw ->
            pw.println("Thread: ${thread.name}")
            throwable.printStackTrace(pw)
        }
        val fullTrace = writer.toString()
        Log.e(TAG, "UNCAUGHT CRASH: $fullTrace")
        System.err.println("UNCAUGHT CRASH: $fullTrace")

        runCatching {
            crashFile(context).writeText(fullTrace)
        }
        runCatching {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_CRASH, fullTrace)
                .commit()
        }
    }

    private fun crashFile(context: Context): File = File(context.filesDir, CRASH_FILE_NAME)
}
