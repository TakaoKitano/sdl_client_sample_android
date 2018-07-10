package com.sdl.hellosdlandroid

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView

import com.smartdevicelink.proxy.rpc.enums.LockScreenStatus

class LockScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock_screen)

        LOCKSCREEN_INSTANCE = this

        // redo the checkup
        updateLockScreenStatus(LOCKSCREEN_STATUS)
    }

    override fun onDestroy() {
        LOCKSCREEN_INSTANCE = null

        super.onDestroy()
    }

    override fun onBackPressed() {

    }

    companion object {
        // This will be set to true if there is any activity running
        // onResume will set this variable to true
        // onPause will set this variable to false
        // As a fallback to old API levels this will be set to true forever
        private var ACTIVITY_RUNNING: Boolean = false
        // This will hold the activity instance of the lock screen if created
        // onCreate will set this variable to the current lock screen instance
        // onDestroy will set this variable to null
        private var LOCKSCREEN_INSTANCE: Activity? = null
        // This will hold the current lock screen status
        private var LOCKSCREEN_STATUS: LockScreenStatus? = null
        // This will ensure that the lifecycle is registered only once
        private var ACTIVITY_LIFECYCLE_REGISTERED: Boolean = false
        // This will hold the lifecycle callback object
        private var ACTIVITY_LIFECYCLE_CALLBACK: ActivityLifecycleCallbacks? = null
        // This will hold the instance of the application object
        private var APPLICATION: Application? = null
        // This will hold the bitmap to update the lockscreen image
        internal var lockscreenIcon: Bitmap? = null


        init {
            ACTIVITY_RUNNING = false
            LOCKSCREEN_INSTANCE = null
            LOCKSCREEN_STATUS = LockScreenStatus.OFF

            ACTIVITY_LIFECYCLE_REGISTERED = false
        }

        fun registerActivityLifecycle(application: Application) {
            // register only once
            if (ACTIVITY_LIFECYCLE_REGISTERED == false) {
                ACTIVITY_LIFECYCLE_REGISTERED = true

                // check if API level is >= 14
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                    // create the callback
                    ACTIVITY_LIFECYCLE_CALLBACK = object : ActivityLifecycleCallbacks {
                        override fun onActivityResumed(activity: Activity) {
                            ACTIVITY_RUNNING = true
                            // recall this method so the lock screen comes up when necessary
                            updateLockScreenStatus(LOCKSCREEN_STATUS)

                            val lockscreenIV : ImageView? = activity.findViewById<View>(R.id.lockscreen) as ImageView?
                            if (lockscreenIcon != null && lockscreenIV != null) {
                                lockscreenIV.setImageBitmap(lockscreenIcon)
                                lockscreenIcon = null
                            }
                        }

                        override fun onActivityPaused(activity: Activity) {
                            ACTIVITY_RUNNING = false
                        }

                        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {

                        }

                        override fun onActivityStarted(activity: Activity) {}

                        override fun onActivityStopped(activity: Activity) {}

                        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle?) {}

                        override fun onActivityDestroyed(activity: Activity) {}
                    }

                    APPLICATION = application

                    // do the activity registration
                    application.registerActivityLifecycleCallbacks(ACTIVITY_LIFECYCLE_CALLBACK)
                } else {
                    // fallback and assume we always have an activity
                    ACTIVITY_RUNNING = true
                }
            }
        }

        fun updateLockScreenStatus(status: LockScreenStatus?) {
            LOCKSCREEN_STATUS = status

            if (status == LockScreenStatus.OFF) {
                // do we have a lock screen? if so we need to remove it
                if (LOCKSCREEN_INSTANCE != null) {
                    LOCKSCREEN_INSTANCE!!.finish()
                }
            } else {
                // do we miss a lock screen and app is in foreground somehow? if so we need to lock it
                if (LOCKSCREEN_INSTANCE == null && ACTIVITY_RUNNING == true) {
                    val intent = Intent(APPLICATION, LockScreenActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)

                    APPLICATION!!.startActivity(intent)
                }
            }
        }

        fun updateLockScreenImage(icon: Bitmap) {
            lockscreenIcon = icon
        }
    }
}
