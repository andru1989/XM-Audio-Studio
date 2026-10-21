package io.github.andru1989.xmaudiostudio

import android.app.Application
import io.github.andru1989.xmaudiostudio.bluetooth.RfcommControlChannel
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneModel
import io.github.andru1989.xmaudiostudio.core.presets.ProfileLibrary
import io.github.andru1989.xmaudiostudio.core.presets.ProfileStore
import io.github.andru1989.xmaudiostudio.core.session.DeviceTarget
import io.github.andru1989.xmaudiostudio.core.session.HeadphoneSession
import io.github.andru1989.xmaudiostudio.core.session.SessionConfig
import io.github.andru1989.xmaudiostudio.core.session.SimulatedXm6Channel
import io.github.andru1989.xmaudiostudio.data.AppSettings
import io.github.andru1989.xmaudiostudio.data.ProtocolLog
import io.github.andru1989.xmaudiostudio.platform.Availability
import io.github.andru1989.xmaudiostudio.platform.LastKnownStore
import io.github.andru1989.xmaudiostudio.platform.NoiseControl
import io.github.andru1989.xmaudiostudio.platform.NoiseControlLogic
import io.github.andru1989.xmaudiostudio.platform.PlatformSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

class XmAudioStudioApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Process-wide objects. The session lives on the main thread for the lifetime of the
 * process; it holds the control channel only while a screen is visible or a command is in
 * flight (see ADR-0003), so no foreground service is needed. The Quick Settings tile and the
 * home-screen widget use the same session (docs/android/BACKGROUND.md).
 */
class AppContainer(private val app: Application) {
    val settings = AppSettings(app)
    val protocolLog = ProtocolLog(app.filesDir.resolve("protocol.log"))
    val profiles = ProfileLibrary(ProfileStore(app.filesDir.resolve("presets/profiles.json")))

    /** Lives as long as the process (tile, widget and their receivers); never cancelled. */
    val appScope = newScope()

    private var sessionScope = newScope()
    var session: HeadphoneSession = createSession(settings.demoMode)
        private set
    private val sessions = MutableStateFlow(session)

    /** Demo mode swaps the RFCOMM channel for a byte-level XM6 simulator; everything above it is real. */
    var simulator: SimulatedXm6Channel? = null
        private set

    val lastKnown = LastKnownStore(app)

    /** Pushes session changes to the widget and the tile; persists the last known state. */
    val platform = PlatformSync(
        context = app,
        sessions = sessions,
        store = lastKnown,
        demoMode = { settings.demoMode },
        hasBluetoothPermission = { RfcommControlChannel.hasConnectPermission(app) },
        scope = appScope,
    )

    val noiseControl = NoiseControl(device = { session }, prepare = ::prepareForPlatformCommand)

    init {
        // The tile or the widget may start the process without any screen: select the saved
        // headphones here so they can act (no channel is opened until there is user activity).
        restoreSavedTarget()
        platform.start()
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun createSession(demo: Boolean): HeadphoneSession {
        val channel = if (demo) SimulatedXm6Channel(sessionScope).also { simulator = it } else RfcommControlChannel(app).also { simulator = null }
        val config = SessionConfig(releaseWhenIdle = settings.releaseWhenIdle)
        return HeadphoneSession(channel, sessionScope, config) { direction, bytes -> protocolLog.log(direction, bytes) }
    }

    /** Rebuilds the session (demo toggle, idle policy change). The old channel is released first. */
    fun rebuildSession(): HeadphoneSession {
        session.disconnect()
        sessionScope.cancel()
        sessionScope = newScope()
        session = createSession(settings.demoMode)
        restoreSavedTarget()
        sessions.value = session
        return session
    }

    /** The headphones chosen in the app (or the simulated XM6 in demo mode), if any. */
    fun savedTarget(): DeviceTarget? {
        if (settings.demoMode) return DeviceTarget(DEMO_ADDRESS, DEMO_NAME, HeadphoneModel.WH_1000XM6)
        val address = settings.selectedAddress ?: return null
        val name = settings.selectedName ?: address
        val model = settings.modelOverride?.let(HeadphoneModel::fromId) ?: HeadphoneModel.fromBluetoothName(name) ?: return null
        return DeviceTarget(address, name, model)
    }

    /** Selects [savedTarget] on the session; a no-op when it is already selected. */
    fun restoreSavedTarget() {
        savedTarget()?.let(session::selectTarget)
    }

    private fun prepareForPlatformCommand(): Availability {
        restoreSavedTarget()
        return NoiseControlLogic.availability(
            target = session.snapshot.value.target,
            demoMode = settings.demoMode,
            hasBluetoothPermission = RfcommControlChannel.hasConnectPermission(app),
        )
    }

    companion object {
        const val DEMO_ADDRESS = "00:00:00:00:DE:40"
        const val DEMO_NAME = "WH-1000XM6 (demo)"
    }
}
