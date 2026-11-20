package com.btgamepad.pro

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var gamepadView: GamepadView
    private var hidController: BluetoothHidController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF10131A.toInt()
        window.navigationBarColor = 0xFF10131A.toInt()
        gamepadView = GamepadView(
            this,
            onReport = { report -> hidController?.sendReport(report) },
            onConnect = { connectAndPair() },
            onSettings = { showSettings() }
        )
        setContentView(gamepadView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        requestBluetoothPermissions()
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
            val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS)
                return
            }
        }
        initializeHid()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initializeHid()
            } else {
                updateStatus("Izin Bluetooth ditolak. Izinkan Bluetooth dari pengaturan aplikasi.")
            }
        }
    }

    private fun initializeHid() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            updateStatus("Bluetooth HID Device membutuhkan Android 9 atau lebih baru.")
            return
        }
        hidController = BluetoothHidController(this) { message -> runOnUiThread { updateStatus(message) } }
        updateStatus("Siap. Tekan HUBUNGKAN untuk menyiapkan gamepad Bluetooth.")
    }

    private fun connectAndPair() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            updateStatus("HP ini tidak memiliki Bluetooth.")
            return
        }
        if (!adapter.isEnabled) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT)
            return
        }
        hidController?.start()
        try {
            val discoverable = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            }
            startActivity(discoverable)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    @Deprecated("Uses the Android system Bluetooth enable prompt")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_OK) {
            connectAndPair()
        } else if (requestCode == REQUEST_ENABLE_BT) {
            updateStatus("Bluetooth belum diaktifkan.")
        }
    }

    private fun showSettings() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        val deadzoneLabel = TextView(this).apply { text = "Deadzone: ${(gamepadView.deadzone * 100).toInt()}%" }
        val deadzoneBar = SeekBar(this).apply { max = 30; progress = (gamepadView.deadzone * 100).toInt() }
        val sensitivityLabel = TextView(this).apply { text = "Sensitivitas: ${(gamepadView.sensitivity * 100).toInt()}%" }
        val sensitivityBar = SeekBar(this).apply { max = 200; progress = (gamepadView.sensitivity * 100).toInt() }
        val sizeLabel = TextView(this).apply { text = "Ukuran analog: ${(gamepadView.stickScale * 100).toInt()}%" }
        val sizeBar = SeekBar(this).apply { max = 150; progress = (gamepadView.stickScale * 100).toInt() }

        deadzoneBar.setOnSeekBarChangeListener(simpleListener { value ->
            gamepadView.deadzone = value / 100f
            deadzoneLabel.text = "Deadzone: ${value}%"
            gamepadView.invalidate()
        })
        sensitivityBar.setOnSeekBarChangeListener(simpleListener { value ->
            gamepadView.sensitivity = value / 100f
            sensitivityLabel.text = "Sensitivitas: ${value}%"
            gamepadView.invalidate()
        })
        sizeBar.setOnSeekBarChangeListener(simpleListener { value ->
            gamepadView.stickScale = value.coerceAtLeast(50) / 100f
            sizeLabel.text = "Ukuran analog: ${value.coerceAtLeast(50)}%"
            gamepadView.invalidate()
        })

        layout.addView(deadzoneLabel); layout.addView(deadzoneBar)
        layout.addView(sensitivityLabel); layout.addView(sensitivityBar)
        layout.addView(sizeLabel); layout.addView(sizeBar)
        AlertDialog.Builder(this)
            .setTitle("Pengaturan Controller")
            .setView(layout)
            .setPositiveButton("Selesai", null)
            .show()
    }

    private fun simpleListener(onValue: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onValue(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun updateStatus(message: String) {
        if (::gamepadView.isInitialized) gamepadView.statusText = message
    }

    override fun onDestroy() {
        hidController?.close()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_ENABLE_BT = 501
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 502
    }
}
