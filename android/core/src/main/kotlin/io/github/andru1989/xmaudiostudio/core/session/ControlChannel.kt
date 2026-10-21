package io.github.andru1989.xmaudiostudio.core.session

/**
 * The Sony MDR control channel (RFCOMM) as seen by the session layer.
 *
 * This is deliberately *not* the audio link: the headphones can be playing audio over
 * A2DP while the control channel is closed, or owned by another app/device.
 * Implementations: `RFCOMMConnection` (macOS, IOBluetooth) and `RfcommControlChannel`
 * (Android, BluetoothSocket). Callbacks may arrive on any thread.
 */
interface ControlChannel {
    fun open(address: String, listener: Listener)
    fun write(bytes: ByteArray)
    fun close()

    interface Listener {
        fun onOpened()
        fun onData(bytes: ByteArray)
        /** The channel closed without us asking (headphones off, out of range, taken over). */
        fun onClosed()
        fun onFailed(error: ChannelError)
    }
}

sealed interface ChannelError {
    val message: String

    /** Android: BLUETOOTH_CONNECT not granted. macOS: Bluetooth privacy not granted. */
    data class PermissionDenied(override val message: String) : ChannelError

    data class BluetoothOff(override val message: String) : ChannelError

    /** The device is not paired with this phone/Mac. Pairing is never changed by the app. */
    data class NotPaired(override val message: String) : ChannelError

    /** No Sony control service in the SDP record. */
    data class ServiceNotFound(override val message: String) : ChannelError

    /**
     * Socket refused/timed out. Often the headphones are off, out of range, or another
     * device currently holds the single MDR control session.
     */
    data class ConnectFailed(override val message: String) : ChannelError

    data class Io(override val message: String) : ChannelError
}
