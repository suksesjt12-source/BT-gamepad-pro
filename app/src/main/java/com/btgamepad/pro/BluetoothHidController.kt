package com.btgamepad.pro

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.concurrent.Executors

/**
 * Exposes this phone as a standard Bluetooth HID gamepad.
 * The optional HID Device system profile is not present on every Android phone.
 */
class BluetoothHidController(
    context: Context,
    private val onStatus: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var isRegistered = false

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            isRegistered = registered
            if (registered) {
                onStatus("HID siap. Pair dari HP penerima melalui pengaturan Bluetooth.")
                refreshConnectedDevice()
            } else {
                onStatus("Pendaftaran Bluetooth HID gagal atau dihentikan sistem.")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    onStatus("Terhubung: ${safeDeviceName(device)}")
                }
                BluetoothProfile.STATE_CONNECTING -> onStatus("Menghubungkan ke HP penerima…")
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevice = null
                    onStatus("Terputus. Pair HP penerima melalui pengaturan Bluetooth.")
                }
                BluetoothProfile.STATE_DISCONNECTING -> onStatus("Memutuskan koneksi…")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onStatus("Bluetooth HID Device membutuhkan Android 9 atau lebih baru.")
            return
        }
        if (adapter == null) {
            onStatus("Perangkat ini tidak memiliki Bluetooth.")
            return
        }
        if (!adapter.isEnabled) {
            onStatus("Aktifkan Bluetooth terlebih dahulu.")
            return
        }
        if (hidDevice != null) {
            registerApp()
            return
        }

        onStatus("Memeriksa dukungan Bluetooth HID…")
        val requested = adapter.getProfileProxy(
            appContext,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != BluetoothProfile.HID_DEVICE) return
                    hidDevice = proxy as? BluetoothHidDevice
                    if (hidDevice == null) {
                        onStatus("Bluetooth HID Device tidak tersedia pada HP ini.")
                    } else {
                        registerApp()
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = null
                        connectedDevice = null
                        isRegistered = false
                        onStatus("Layanan Bluetooth HID terputus.")
                    }
                }
            },
            BluetoothProfile.HID_DEVICE
        )
        if (!requested) {
            onStatus("HP ini tidak menyediakan profil Bluetooth HID Device.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerApp() {
        val service = hidDevice ?: return
        if (isRegistered) {
            refreshConnectedDevice()
            return
        }
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "BT Gamepad Pro",
            "Android Bluetooth Gamepad",
            "BT Gamepad Pro",
            0x00.toByte(),
            REPORT_DESCRIPTOR
        )
        val accepted = service.registerApp(sdp, null, null, executor, callback)
        if (accepted) onStatus("Mendaftarkan gamepad Bluetooth…")
        else onStatus("Sistem menolak pendaftaran HID. Coba mulai ulang Bluetooth.")
    }

    @SuppressLint("MissingPermission")
    private fun refreshConnectedDevice() {
        val device = hidDevice?.connectedDevices?.firstOrNull()
        connectedDevice = device
        if (device != null) onStatus("Terhubung: ${safeDeviceName(device)}")
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String {
        return try {
            device.name?.takeIf { it.isNotBlank() } ?: "HP penerima"
        } catch (_: SecurityException) {
            "HP penerima"
        }
    }

    @SuppressLint("MissingPermission")
    fun sendReport(report: ByteArray) {
        val service = hidDevice ?: return
        if (!isRegistered) return
        try {
            val device = connectedDevice ?: service.connectedDevices.firstOrNull()
            if (device == null) {
                onStatus("Belum terhubung. Pair gamepad ini dari HP penerima.")
                return
            }
            connectedDevice = device
            service.sendReport(device, 0, report)
        } catch (_: SecurityException) {
            onStatus("Izin Bluetooth tidak tersedia. Izinkan Bluetooth untuk aplikasi.")
        } catch (_: Exception) {
            onStatus("Gagal mengirim input gamepad.")
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        try {
            hidDevice?.unregisterApp()
            hidDevice?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        } catch (_: Exception) {
            // The Bluetooth service may already have stopped.
        }
        hidDevice = null
        connectedDevice = null
        isRegistered = false
        executor.shutdown()
    }

    companion object {
        // 16 buttons followed by X, Y, Rx and Ry (8-bit axes).
        private val REPORT_DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x05, 0xA1.toByte(), 0x01,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01,
            0x95.toByte(), 0x10, 0x75, 0x01, 0x81.toByte(), 0x02,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x33, 0x09, 0x34,
            0x15, 0x00, 0x26.toByte(), 0xFF.toByte(), 0x00,
            0x75, 0x08, 0x95.toByte(), 0x04, 0x81.toByte(), 0x02, 0xC0.toByte()
        )
    }
}
