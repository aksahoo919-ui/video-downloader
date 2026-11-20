package com.easyvideodownloader

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Unpack the download engine in the background as soon as the app starts.
        Engine.init(this)
    }
}
