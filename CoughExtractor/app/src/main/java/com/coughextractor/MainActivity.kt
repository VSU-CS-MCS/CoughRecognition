package com.coughextractor

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

import com.coughextractor.hcCough.CoughDevice
import com.coughextractor.hcCough.CoughDeviceError

enum class MainActivityRequestCodes(val code: Int) {
    EnableBluetooth(1)
}

private const val TAG = "MainActivity"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var hcCoughDevice: CoughDevice

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        connectToDevice()
    }

    override fun onDestroy() {
        super.onDestroy()
        hcCoughDevice.disconnect()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            MainActivityRequestCodes.EnableBluetooth.code -> {
                when (resultCode) {
                    RESULT_OK -> {
                        connectToDevice()
                    }
                    RESULT_CANCELED -> {
                        Log.w(TAG, "Bluetooth not enabled")
                    }
                }
            }
        }
    }

    private fun askToEnableBluetooth() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(enableBtIntent, MainActivityRequestCodes.EnableBluetooth.code)
    }

    private fun connectToDevice() {
        when (hcCoughDevice.connect()) {
            CoughDeviceError.BluetoothAdapterNotFound -> Log.wtf(TAG, "Bluetooth adapter not found")
            CoughDeviceError.BluetoothDisabled -> askToEnableBluetooth()
            CoughDeviceError.CoughDeviceNotFound -> Log.e(TAG, "Cough device not found")
        }
    }
}
