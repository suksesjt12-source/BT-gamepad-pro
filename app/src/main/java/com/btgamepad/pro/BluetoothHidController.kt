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
 * Makes this Android phone act as a Bluetooth Classic HID gamepad.
 * Android firmware must include the optional HID Device profile.
 */
class BluetoothHidController(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onReady: () -> Unit
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    @Volatile private var hidDevice: BluetoothHidDevice? = null
    @Volatile private var connectedDevice: BluetoothDevice? = null
    @Volatile private var isRegistered = false

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            isRegistered = registered
            if (registered) {
                onStatus("HID AKTIF. Pair HP penerima lewat Bluetooth, lalu tekan HUBUNGKAN lagi.")
                refreshConnectedDevice()
                onReady()
            } else {
                connectedDevice = null
                onStatus("Pendaftaran HID dibatalkan sistem. Pastikan aplikasi tetap terbuka di layar.")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    onStatus("TERHUBUNG: ${safeDeviceName(device)}")
                }
                BluetoothProfile.STATE_CONNECTING ->
                    onStatus("MENGHUBUNGKAN: ${safeDeviceName(device)}")
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevice = null
                    onStatus("Koneksi terputus. Pilih HP yang sudah dipasangkan.")
                }
                BluetoothProfile.STATE_DISCONNECTING ->
                    onStatus("Memutuskan koneksi…")
            }
        }
    }

    fun isRegistered(): Boolean = isRegistered

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onStatus("Bluetooth HID Device memerlukan Android 9 atau lebih baru.")
            return
        }
        if (adapter == null) {
            onStatus("Bluetooth tidak tersedia di perangkat ini.")
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

        onStatus("Memeriksa profil Bluetooth HID Device…")
        val requested = try {
            adapter.getProfileProxy(
                appContext,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile != BluetoothProfile.HID_DEVICE) return
                        hidDevice = proxy as? BluetoothHidDevice
                        if (hidDevice == null) {
                            onStatus("GAGAL: layanan HID Device tidak tersedia pada firmware HP ini.")
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
        } catch (error: SecurityException) {
            onStatus("Izin Bluetooth ditolak: ${error.message ?: "akses tidak diizinkan"}")
            false
        } catch (error: Exception) {
            onStatus("Tidak dapat membuka layanan HID: ${error.message ?: "kesalahan sistem"}")
            false
        }

        if (!requested) {
            onStatus("GAGAL: Android tidak menyediakan profil Bluetooth HID Device pada HP ini.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerApp() {
        val service = hidDevice ?: return
        if (isRegistered) {
            onReady()
            return
        }

        val settings = BluetoothHidDeviceAppSdpSettings(
            "BT Gamepad Pro",
            "Android Bluetooth Gamepad",
            "BT Gamepad Pro",
            BluetoothHidDevice.SUBCLASS2_GAMEPAD,
            REPORT_DESCRIPTOR
        )

        val accepted = try {
            service.registerApp(settings, null, null, executor, callback)
        } catch (error: SecurityException) {
            onStatus("Tidak mendapat izin mendaftarkan HID: ${error.message ?: "izin Bluetooth"}")
            false
        } catch (error: Exception) {
            onStatus("Pendaftaran HID gagal: ${error.message ?: "kesalahan sistem"}")
            false
        }

        if (accepted) {
            onStatus("Permintaan HID dikirim. Menunggu konfirmasi Android…")
        } else {
            onStatus("Android menolak pendaftaran HID. Profil mungkin tidak didukung.")
        }
    }

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BluetoothDevice> {
        return try {
            adapter?.bondedDevices?.toList().orEmpty()
        } catch (error: SecurityException) {
            onStatus("Izin Bluetooth CONNECT diperlukan untuk membaca perangkat yang dipasangkan.")
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val service = hidDevice
        if (service == null || !isRegistered) {
            onStatus("HID belum aktif. Tekan HUBUNGKAN untuk mendaftarkan gamepad.")
            return
        }
        try {
            val queued = service.connect(device)
            onStatus(
                if (queued) "Permintaan koneksi dikirim ke ${safeDeviceName(device)}…"
                else "Android menolak permintaan koneksi. Pastikan HP sudah dipasangkan dan coba lagi."
            )
        } catch (error: SecurityException) {
            onStatus("Izin Bluetooth CONNECT tidak tersedia.")
        } catch (error: Exception) {
            onStatus("Koneksi gagal: ${error.message ?: "kesalahan Bluetooth"}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshConnectedDevice() {
        val device = try {
            hidDevice?.connectedDevices?.firstOrNull()
        } catch (_: Exception) {
            null
        }
        connectedDevice = device
        if (device != null) onStatus("TERHUBUNG: ${safeDeviceName(device)}")
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String {
        return try {
            device.name?.takeIf { it.isNotBlank() } ?: device.address
        } catch (_: SecurityException) {
            "HP penerima"
        }
    }

    @SuppressLint("MissingPermission")
    fun sendReport(report: ByteArray) {
        val service = hidDevice ?: return
        if (!isRegistered) return
        val device = connectedDevice ?: try {
            service.connectedDevices.firstOrNull()
        } catch (_: Exception) {
            null
        }
        if (device == null) return

        connectedDevice = device
        try {
            val sent = service.sendReport(device, 0, report)
            if (!sent) onStatus("Input belum terkirim. Periksa koneksi Bluetooth.")
        } catch (_: SecurityException) {
            onStatus("Izin Bluetooth CONNECT tidak tersedia.")
        } catch (_: Exception) {
            onStatus("Terjadi kesalahan saat mengirim input gamepad.")
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        try {
            hidDevice?.unregisterApp()
            hidDevice?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        } catch (_: Exception) {
            // Android may already have closed the profile service.
        }
        hidDevice = null
        connectedDevice = null
        isRegistered = false
        executor.shutdown()
    }

    companion object {
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
