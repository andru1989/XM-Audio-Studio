package io.github.andru1989.xmaudiostudio.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneModel

data class PairedDevice(val address: String, val name: String, val model: HeadphoneModel?, val isAudio: Boolean)

/** Lists devices already paired in Android settings. Never scans, pairs or unpairs. */
object PairedDevices {
    sealed interface Result {
        data class Devices(val devices: List<PairedDevice>) : Result
        data object PermissionMissing : Result
        data object BluetoothOff : Result
        data object NoAdapter : Result
    }

    @SuppressLint("MissingPermission") // checked via hasConnectPermission
    fun list(context: Context): Result {
        if (!RfcommControlChannel.hasConnectPermission(context)) return Result.PermissionMissing
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return Result.NoAdapter
        if (!adapter.isEnabled) return Result.BluetoothOff
        val devices = try {
            adapter.bondedDevices.orEmpty().map { d ->
                val name = d.name ?: d.address
                val audio = d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
                PairedDevice(d.address, name, HeadphoneModel.fromBluetoothName(name), audio)
            }
        } catch (_: SecurityException) {
            return Result.PermissionMissing
        }
        // Known Sony models first, then other audio devices, then the rest.
        return Result.Devices(devices.sortedWith(compareBy({ it.model == null }, { !it.isAudio }, { it.name.lowercase() })))
    }
}
