package com.gdahome.os

import android.app.Application

class GdaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GdaService.start(this)
    }
}
