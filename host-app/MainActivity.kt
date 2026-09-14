package com.example.dshhost

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 占位 Activity：拉起引擎前台服务。
 * Phase 2 在此接入 Kotlin UI（连 http://127.0.0.1:3080 的 /api RPC + SSE）。
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, DshHostService::class.java))
        finish()
    }
}
