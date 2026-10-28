package com.vauth.rxfoxyvpn.vpn

import android.content.Context

object BatteryOptimization {
    fun isIgnoring(context: Context): Boolean = true
    fun requestIfNeeded(context: Context) {}
    fun openRequest(context: Context) {}
}
