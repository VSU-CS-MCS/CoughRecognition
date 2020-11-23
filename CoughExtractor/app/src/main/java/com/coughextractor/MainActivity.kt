package com.coughextractor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.databinding.DataBindingUtil
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

import com.coughextractor.databinding.ActivityMainBinding
import com.coughextractor.device.CoughDeviceError

import dagger.hilt.android.AndroidEntryPoint

enum class MainActivityRequestCodes(val code: Int) {
    EnableBluetooth(1)
}

private const val TAG = "MainActivity"

private const val REQUEST_PERMISSION_CODE = 200

@AndroidEntryPoint
class MainActivity() : ComponentActivity(), CoroutineScope {

    private lateinit var job: Job
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main

    val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.baseDir = externalCacheDir?.absolutePath ?: ""

        requestPermissions(permissions, REQUEST_PERMISSION_CODE)
        val binding: ActivityMainBinding = DataBindingUtil.setContentView(
            this, R.layout.activity_main)
        binding.viewModel = viewModel
        job = Job()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
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

    private var permissionToRecordAccepted = false
    private var permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionToRecordAccepted = if (requestCode == REQUEST_PERMISSION_CODE) {
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        if (!permissionToRecordAccepted) finish()
    }

    private fun askToEnableBluetooth() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(enableBtIntent, MainActivityRequestCodes.EnableBluetooth.code)
    }

    private fun connectToDevice() {
        when (viewModel.coughDevice.connect()) {
            CoughDeviceError.BluetoothAdapterNotFound -> Log.wtf(TAG, "Bluetooth adapter not found")
            CoughDeviceError.BluetoothDisabled -> askToEnableBluetooth()
            CoughDeviceError.CoughDeviceNotFound -> Log.e(TAG, "Cough device not found")
        }
    }
}
