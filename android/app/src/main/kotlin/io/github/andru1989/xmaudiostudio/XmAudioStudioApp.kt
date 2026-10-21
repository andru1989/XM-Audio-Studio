package io.github.andru1989.xmaudiostudio

import android.app.Application
import io.github.andru1989.xmaudiostudio.bluetooth.RfcommControlChannel
import io.github.andru1989.xmaudiostudio.core.presets.ProfileLibrary
import io.github.andru1989.xmaudiostudio.core.presets.ProfileStore
import io.github.andru1989.xmaudiostudio.core.session.HeadphoneSession
import io.github.andru1989.xmaudiostudio.core.session.SessionConfig
import io.github.andru1989.xmaudiostudio.core.session.SimulatedXm6Channel
import io.github.andru1989.xmaudiostudio.data.AppSettings
import io.github.andru1989.xmaudiostudio.data.ProtocolLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
 * flight (see ADR-0003), so no foreground service is needed.
 */
class AppContainer(private val app: Application) {
    val settings = AppSettings(app)
    val protocolLog = ProtocolLog(app.filesDir.resolve("protocol.log"))
    val profiles = ProfileLibrary(ProfileStore(app.filesDir.resolve("presets/profiles.json")))

    private var sessionScope = newScope()
    var session: HeadphoneSession = createSession(settings.demoMode)
        private set

    /** Demo mode swaps the RFCOMM channel for a byte-level XM6 simulator; everything above it is real. */
    var simulator: SimulatedXm6Channel? = null
        private set

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
        return session
    }
}
