package com.sdl.hellosdlandroid

import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.smartdevicelink.transport.SdlBroadcastReceiver
import com.crashlytics.android.Crashlytics
import io.fabric.sdk.android.Fabric


class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fabric.with(this, Crashlytics())
        setContentView(R.layout.activity_main)
        //If we are connected to a module we want to start our SdlService
        if (BuildConfig.TRANSPORT == "MBT") {
            SdlBroadcastReceiver.queryForConnectedService(this)
        } else if (BuildConfig.TRANSPORT == "TCP" || BuildConfig.TRANSPORT == "LBT") {
            val proxyIntent = Intent(this, SdlService::class.java)
            startService(proxyIntent)
        }
    }

    fun forceCrash(view: View) {
        throw RuntimeException("This is a crash")
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        val id = item.itemId
        return if (id == R.id.action_settings) {
            true
        } else super.onOptionsItemSelected(item)
    }

    companion object {
        private val TAG = "MainActivity"
    }
}
