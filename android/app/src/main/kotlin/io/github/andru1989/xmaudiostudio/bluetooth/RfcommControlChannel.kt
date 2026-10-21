package io.github.andru1989.xmaudiostudio.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import io.github.andru1989.xmaudiostudio.core.session.ChannelError
import io.github.andru1989.xmaudiostudio.core.session.ControlChannel
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Android RFCOMM implementation of the Sony MDR control channel.
 *
 * Only talks to devices the user has already paired; never scans, pairs or unpairs.
 * Service selection follows the documented protocol: prefer the v2 UUID when the
 * device advertises it (XM5/XM6), else v1 (XM2-XM4). One executor serialises connect
 * and writes; a dedicated thread blocks on reads.
 */
class RfcommControlChannel(private val context: Context) : ControlChannel {

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "mdr-writer") }
    private val socket = AtomicReference<BluetoothSocket?>(null)
    @Volatile private var listener: ControlChannel.Listener? = null
    /** Incremented on every open/close so callbacks from an abandoned socket are ignored. */
    @Volatile private var generation = 0

    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    override fun open(address: String, listener: ControlChannel.Listener) {
        val gen = ++generation
        this.listener = listener
        writer.execute { connect(address, gen) }
    }

    @SuppressLint("MissingPermission") // checked by hasConnectPermission()
    private fun connect(address: String, gen: Int) {
        val l = listener ?: return
        if (!hasConnectPermission(context)) {
            return l.onFailed(ChannelError.PermissionDenied("Bluetooth permission (Nearby devices) not granted"))
        }
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            return l.onFailed(ChannelError.BluetoothOff("Bluetooth is off"))
        }
        val device: BluetoothDevice = adapter.bondedDevices.firstOrNull { it.address.equals(address, true) }
            ?: return l.onFailed(ChannelError.NotPaired("Headphones are not paired with this phone"))

        val candidates = serviceUuidCandidates(device.uuids)
        runCatching { adapter.cancelDiscovery() }
        // Connecting immediately after discovery/ACL changes is unreliable on several
        // stacks; a short settle delay is the documented workaround.
        Thread.sleep(CONNECT_SETTLE_MS)
        if (gen != generation) return

        var s: BluetoothSocket? = null
        var lastError: IOException? = null
        for (uuid in candidates) {
            if (gen != generation) return
            try {
                s = device.createRfcommSocketToServiceRecord(uuid).also { it.connect() }
                break
            } catch (e: IOException) {
                lastError = e
            } catch (e: SecurityException) {
                return l.onFailed(ChannelError.PermissionDenied(e.message ?: "Permission denied"))
            }
        }
        if (s == null) {
            if (gen == generation) {
                l.onFailed(ChannelError.ConnectFailed("Could not open the control channel: ${lastError?.message ?: "unknown error"}"))
            }
            return
        }

        if (gen != generation) {
            runCatching { s.close() }
            return
        }
        socket.set(s)
        Thread({ readLoop(s, gen) }, "mdr-reader").apply { isDaemon = true }.start()
        l.onOpened()
    }

    private fun readLoop(s: BluetoothSocket, gen: Int) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            val input = s.inputStream
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (gen != generation) return
                if (n > 0) listener?.onData(buffer.copyOf(n))
            }
        } catch (_: IOException) {
            // Socket closed: either we closed it (generation changed) or the link dropped.
        }
        if (gen == generation && socket.compareAndSet(s, null)) {
            runCatching { s.close() }
            listener?.onClosed()
        }
    }

    override fun write(bytes: ByteArray) {
        val gen = generation
        writer.execute {
            val s = socket.get()
            if (s == null || gen != generation) return@execute
            try {
                s.outputStream.write(bytes)
                s.outputStream.flush()
            } catch (e: IOException) {
                if (gen == generation) listener?.onFailed(ChannelError.Io("Write failed: ${e.message}"))
            }
        }
    }

    override fun close() {
        generation++
        socket.getAndSet(null)?.let { runCatching { it.close() } }
    }

    companion object {
        val SERVICE_UUID_V1: UUID = UUID.fromString("96CC203E-5068-46AD-B32D-E316F5E069BA")
        val SERVICE_UUID_V2: UUID = UUID.fromString("956C7B26-D49A-4BA8-B03F-B17D393CB6E2")
        private const val CONNECT_SETTLE_MS = 500L
        private const val READ_BUFFER_SIZE = 2048

        /**
         * The advertised Sony service (v2 preferred: XM5/XM6; v1: XM2-XM4). When the phone's
         * SDP cache has no Sony entry yet, both are tried, v2 first.
         */
        fun serviceUuidCandidates(uuids: Array<ParcelUuid>?): List<UUID> {
            val advertised = uuids?.map { it.uuid }.orEmpty()
            return when {
                SERVICE_UUID_V2 in advertised -> listOf(SERVICE_UUID_V2)
                SERVICE_UUID_V1 in advertised -> listOf(SERVICE_UUID_V1)
                else -> listOf(SERVICE_UUID_V2, SERVICE_UUID_V1)
            }
        }

        fun hasConnectPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
}
