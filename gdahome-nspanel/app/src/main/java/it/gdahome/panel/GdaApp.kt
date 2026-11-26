package it.gdahome.panel

import android.app.Application

class GdaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GdaService.start(this)
    }
}
