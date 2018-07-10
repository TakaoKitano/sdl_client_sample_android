package com.sdl.hellosdlandroid

import android.app.Application

class SdlApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        LockScreenActivity.registerActivityLifecycle(this)
    }

    companion object {

        private val TAG = SdlApplication::class.java.simpleName
    }

}
