package com.btgamepad.pro

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val layoutPreferences = getSharedPreferences("controller_layout", MODE_PRIVATE)
        gamepadView = GamepadView(
            this,
            preferences = layoutPreferences,
            onReport = { report -> hidController?.sendReport(report) },
            onConnect = { connectAndPair() },
            onSettings = { showSettings() },
            onMapping = { showMappingDialog() }
        )
        setContentView(
            gamepadView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        requestBluetoothPermissions()
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
            val missing = required.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS)
                return
            }
        }
        initializeHid()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all {
                    it == PackageManager.PERMISSION_GRANTED
                }) {
                initializeHid()
            } else {
                updateStatus("Izin Bluetooth ditolak. Izinkan Bluetooth CONNECT dan ADVERTISE.")
            }
        }
    }

    private fun initializeHid() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            updateStatus("Bluetooth HID Device memerlukan Android 9 atau lebih baru.")
            return
        }
        hidController?.close()
        hidController = BluetoothHidController(
            this,
            onStatus = { message -> runOnUiThread { updateStatus(message) } },
            onReady = {
                runOnUiThread {
                    updateStatus(
                        "HID AKTIF. Pair HP penerima lewat Bluetooth, lalu tekan PAIR lagi."
                    )
                }
            }
        )
        updateStatus("Siap. Tekan PAIR untuk memulai koneksi Bluetooth.")
    }

    private fun connectAndPair() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            updateStatus("Bluetooth tidak tersedia di HP ini.")
            return
        }
        if (!adapter.isEnabled) {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                REQUEST_ENABLE_BT
            )
            return
        }

        val controller = hidController
        if (controller == null) {
            requestBluetoothPermissions()
            return
        }

        if (controller.isRegistered()) {
            showPairedHosts(controller)
        } else {
            requestDiscoverableBeforeRegistering()
        }
    }

    private fun requestDiscoverableBeforeRegistering() {
        try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            }
            startActivityForResult(intent, REQUEST_DISCOVERABLE)
        } catch (error: Exception) {
            updateStatus("Tidak bisa membuka mode discoverable: ${error.message ?: "kesalahan Bluetooth"}")
        }
    }

    private fun showPairedHosts(controller: BluetoothHidController) {
        val devices = controller.pairedDevices()
        if (devices.isEmpty()) {
            updateStatus(
                "HID aktif. Pair HP ini dari menu Bluetooth HP penerima, lalu tekan PAIR lagi."
            )
            return
        }

        val labels = devices.map { deviceLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pilih HP penerima")
            .setItems(labels) { _, index ->
                controller.connect(devices[index])
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun deviceLabel(device: BluetoothDevice): String {
        return try {
            val name = device.name?.takeIf { it.isNotBlank() } ?: "Perangkat Bluetooth"
            "$name\n${device.address}"
        } catch (_: SecurityException) {
            "Perangkat Bluetooth"
        }
    }

    @Deprecated("Uses Android system Bluetooth prompts")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_ENABLE_BT -> {
                if (resultCode == Activity.RESULT_OK) {
                    requestDiscoverableBeforeRegistering()
                } else {
                    updateStatus("Bluetooth belum diaktifkan.")
                }
            }
            REQUEST_DISCOVERABLE -> {
                if (resultCode > 0) {
                    updateStatus("Mendaftarkan HID. Tunggu status HID AKTIF…")
                    hidController?.start()
                } else {
                    updateStatus("Permintaan discoverable dibatalkan. Tekan PAIR untuk mencoba lagi.")
                }
            }
        }
    }

    private fun showMappingDialog() {
        val profileIds = arrayOf("standard", "nintendo", "swap_ab", "swap_xy")
        val profileNames = arrayOf(
            "Android / Xbox — standar",
            "Nintendo — tukar A/B dan X/Y",
            "Tukar A/B",
            "Tukar X/Y"
        )
        val selected = profileIds.indexOf(gamepadView.currentMappingProfile()).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("AUTO MAPPING")
            .setSingleChoiceItems(profileNames, selected) { dialog, which ->
                gamepadView.applyAutoMapping(profileIds[which])
                updateStatus("Pemetaan aktif: ${profileNames[which]}")
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showSettings() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }

        val deadzoneLabel = TextView(this).apply {
            text = "Deadzone analog: ${(gamepadView.deadzone * 100).toInt()}%"
        }
        val deadzoneBar = SeekBar(this).apply {
            max = 30
            progress = (gamepadView.deadzone * 100).toInt()
        }
        val sensitivityLabel = TextView(this).apply {
            text = "Sensitivitas analog: ${(gamepadView.sensitivity * 100).toInt()}%"
        }
        val sensitivityBar = SeekBar(this).apply {
            max = 200
            progress = (gamepadView.sensitivity * 100).toInt()
        }
        val selectedLabel = TextView(this).apply {
            text = "Ukuran ${gamepadView.selectedControlName()}: ${(gamepadView.selectedControlScale() * 100).toInt()}%"
        }
        val selectedSizeBar = SeekBar(this).apply {
            max = 110
            progress = ((gamepadView.selectedControlScale() * 100).toInt() - 50).coerceIn(0, 110)
        }
        val mappingLabel = TextView(this).apply {
            text = "Pemetaan tombol aktif: ${gamepadView.mappingProfileName()}. Gunakan AUTO MAP untuk mengganti profil."
            setPadding(0, 10, 0, 4)
        }
        val hint = TextView(this).apply {
            text = "Untuk mengubah posisi: tutup menu ini, tekan EDIT, lalu geser tombol ke posisi yang diinginkan. Tekan DONE untuk menyimpan. AUTO MAP mengatur pemetaan tombol gamepad."
            setPadding(0, 12, 0, 8)
        }

        deadzoneBar.setOnSeekBarChangeListener(simpleListener { value ->
            gamepadView.deadzone = value / 100f
            deadzoneLabel.text = "Deadzone analog: $value%"
        })
        sensitivityBar.setOnSeekBarChangeListener(simpleListener { value ->
            gamepadView.sensitivity = value / 100f
            sensitivityLabel.text = "Sensitivitas analog: $value%"
        })
        selectedSizeBar.setOnSeekBarChangeListener(simpleListener { value ->
            val scale = (value + 50) / 100f
            gamepadView.setSelectedControlScale(scale)
            selectedLabel.text = "Ukuran ${gamepadView.selectedControlName()}: ${(scale * 100).toInt()}%"
        })

        layout.addView(deadzoneLabel)
        layout.addView(deadzoneBar)
        layout.addView(sensitivityLabel)
        layout.addView(sensitivityBar)
        layout.addView(selectedLabel)
        layout.addView(selectedSizeBar)
        layout.addView(mappingLabel)
        layout.addView(hint)

        AlertDialog.Builder(this)
            .setTitle("Pengaturan & Edit Layout")
            .setView(layout)
            .setNeutralButton("Reset Layout", null)
            .setPositiveButton("Selesai", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Reset layout?")
                            .setMessage("Semua posisi dan ukuran tombol akan dikembalikan ke tata letak awal.")
                            .setNegativeButton("Batal", null)
                            .setPositiveButton("Reset") { _, _ ->
                                gamepadView.resetLayout()
                                updateStatus("Layout controller dikembalikan ke pengaturan awal.")
                            }
                            .show()
                    }
                }
                show()
            }
    }

    private fun simpleListener(onValue: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                onValue(progress)
            }
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
        private const val REQUEST_DISCOVERABLE = 503
    }
}
