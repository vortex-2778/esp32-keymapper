package com.esp32.keymapper.bt

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.esp32.keymapper.core.BtState
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

/**
 * Bluetooth Classic SPP client for the ESP32 (BluetoothSerial).
 *
 * The ESP32 sketch prints "BTN_PRESS_n" / "BTN_RELEASE_n" lines, so this class
 * simply turns the byte stream into lines and forwards them.
 */
class BtSppClient(private val context: Context, private val callback: Callback) {

    interface Callback {
        fun onStateChanged(state: BtState, deviceName: String?)
        fun onLine(line: String)
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    @Volatile
    private var generation = 0

    private var socket: BluetoothSocket? = null
    private var worker: Thread? = null

    var deviceName: String? = null
        private set

    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun connect(mac: String) {
        generation++
        val gen = generation
        running = false
        closeSocketAsync()

        if (!hasPermission()) {
            postState(BtState.ERROR, null)
            return
        }
        val adapter = adapter()
        if (adapter == null) {
            postState(BtState.ERROR, null)
            return
        }
        if (!adapter.isEnabled) {
            postState(BtState.ERROR, null)
            return
        }

        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            postState(BtState.ERROR, null)
            return
        }

        deviceName = safeName(device)
        running = true
        postState(BtState.CONNECTING, deviceName)

        worker = Thread({ doConnect(device, gen) }, "esp32-spp").also { it.start() }
    }

    fun disconnect() {
        generation++
        running = false
        closeSocketAsync()
        main.post { callback.onStateChanged(BtState.DISCONNECTED, deviceName) }
    }

    private fun doConnect(device: BluetoothDevice, gen: Int) {
        var localSocket: BluetoothSocket? = null
        try {
            adapter()?.let { adapter ->
                try {
                    adapter.cancelDiscovery()
                } catch (_: SecurityException) {
                }
            }

            localSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket = localSocket
            localSocket.connect()

            if (!running || gen != generation) return

            main.post { callback.onStateChanged(BtState.CONNECTED, deviceName) }

            val reader = BufferedReader(InputStreamReader(localSocket.inputStream, Charsets.US_ASCII))
            while (running && gen == generation) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) {
                    main.post { callback.onLine(line) }
                }
            }
        } catch (e: Exception) {
            if (gen == generation) {
                main.post { callback.onStateChanged(BtState.ERROR, deviceName) }
            }
        } finally {
            try {
                localSocket?.close()
            } catch (_: Exception) {
            }
            if (socket === localSocket) socket = null
            if (gen == generation) {
                running = false
                main.post { callback.onStateChanged(BtState.DISCONNECTED, deviceName) }
            }
        }
    }

    private fun closeSocketAsync() {
        val current = socket
        socket = null
        if (current != null) {
            Thread {
                try {
                    current.close()
                } catch (_: Exception) {
                }
            }.start()
        }
    }

    private fun safeName(device: BluetoothDevice): String {
        return try {
            device.name ?: device.address
        } catch (e: SecurityException) {
            device.address
        }
    }

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun postState(state: BtState, name: String?) {
        main.post { callback.onStateChanged(state, name) }
    }
}
