package io.github.andru1989.xmaudiostudio.core.session

import io.github.andru1989.xmaudiostudio.core.device.DeviceCatalog
import io.github.andru1989.xmaudiostudio.core.device.DeviceCommand
import io.github.andru1989.xmaudiostudio.core.device.Feature
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneDevice
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneState
import io.github.andru1989.xmaudiostudio.core.device.Outgoing
import io.github.andru1989.xmaudiostudio.core.device.ProtocolAdapter
import io.github.andru1989.xmaudiostudio.core.model.ProtocolVersion
import io.github.andru1989.xmaudiostudio.core.protocol.FrameParser
import io.github.andru1989.xmaudiostudio.core.protocol.MdrEvent
import io.github.andru1989.xmaudiostudio.core.protocol.MdrCommands
import io.github.andru1989.xmaudiostudio.core.protocol.MdrMessage
import io.github.andru1989.xmaudiostudio.core.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One headphone's MDR control session: transport lifecycle, stop-and-wait ACK handling
 * and on-demand ownership of the single control channel the headphones allow.
 *
 * Lifecycle (docs/bluetooth/SESSION-LIFECYCLE.md, same rules as the macOS controller):
 * - The channel is acquired when a control surface becomes visible or a command is issued,
 *   and released [SessionConfig.idleGraceMs] after the last activity with nothing visible.
 *   Background traffic (state reads, notifications) never extends the grace period.
 * - After every (re)connect the real state is read *before* any queued intent is encoded,
 *   so nothing stale is ever written back.
 * - If the headphones close an idle channel, another device took control: the session
 *   becomes [ConnectionPhase.YIELDED] and does not reconnect until fresh user activity.
 * - Models without a [ProtocolAdapter] are never sent a byte.
 *
 * Threading: confine to [scope]'s dispatcher (main thread in the app). Public methods must be
 * called on it; channel callbacks are re-posted onto it.
 */
class HeadphoneSession(
    private val channel: ControlChannel,
    private val scope: CoroutineScope,
    private val config: SessionConfig = SessionConfig(),
    /** Optional protocol trace hook ("TX"/"RX", raw bytes) for diagnostics. */
    private val traffic: ((String, ByteArray) -> Unit)? = null,
) : HeadphoneDevice {

    private class QueuedMessage(val outgoing: Outgoing, val command: DeviceCommand?)

    private val _snapshot = MutableStateFlow(SessionSnapshot())
    override val snapshot: StateFlow<SessionSnapshot> = _snapshot.asStateFlow()

    private var target: DeviceTarget? = null
    private var adapter: ProtocolAdapter? = null
    private var phase = ConnectionPhase.IDLE
    private var error: SessionError? = null

    /** State as reported (or acknowledged) by the headphones. */
    private var confirmed = HeadphoneState()
    private var stateIsLive = false
    private var initialStateTimedOut = false

    private val pending = ArrayDeque<DeviceCommand>()
    private val outgoing = ArrayDeque<QueuedMessage>()
    private var awaiting: QueuedMessage? = null
    private var awaitingInit = false
    private var sequenceNumber = 0
    private val parser = FrameParser()

    private val visibleSurfaces = mutableSetOf<String>()
    private var idleDue = false
    private var generation = 0
    private var connectAttempts = 0
    private var initAttempts = 0
    private var reconnectedForPendingWork = false

    private var idleJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var initRetryJob: Job? = null
    private var ackTimeoutJob: Job? = null
    private var settleJob: Job? = null
    private var stateTimeoutJob: Job? = null

    // region Public API

    fun selectTarget(newTarget: DeviceTarget) {
        if (newTarget == target) return
        closeChannel()
        pending.clear()
        target = newTarget
        adapter = ProtocolAdapter.forModel(newTarget.model)
        confirmed = HeadphoneState()
        stateIsLive = false
        error = null
        phase = ConnectionPhase.IDLE
        publish()
        if (visibleSurfaces.isNotEmpty()) openChannel()
    }

    override fun execute(command: DeviceCommand) {
        if (target == null) return fail(SessionError.NoTarget)
        val feature = featureFor(command)
        val caps = target?.let { DeviceCatalog.capabilities(it.model) }
        if (feature != null && caps?.supports(feature) != true) return
        pending.removeAll { it.coalesceKey == command.coalesceKey }
        pending.addLast(command)
        recordActivity()
        publish()
        when (phase) {
            ConnectionPhase.CONNECTED -> sendNext()
            ConnectionPhase.IDLE, ConnectionPhase.FAILED, ConnectionPhase.YIELDED -> openChannel()
            else -> Unit // flushed once the state read finishes
        }
    }

    override fun refresh() {
        recordActivity()
        when (phase) {
            ConnectionPhase.CONNECTED -> {
                adapter?.stateQueries()?.forEach { outgoing.addLast(QueuedMessage(it, null)) }
                sendNext()
            }
            ConnectionPhase.IDLE, ConnectionPhase.FAILED, ConnectionPhase.YIELDED -> openChannel()
            else -> Unit
        }
    }

    override fun connect() {
        recordActivity()
        if (phase == ConnectionPhase.IDLE || phase == ConnectionPhase.FAILED || phase == ConnectionPhase.YIELDED) {
            openChannel()
        }
    }

    override fun disconnect() {
        pending.clear()
        closeChannel()
        phase = ConnectionPhase.IDLE
        error = null
        publish()
    }

    override fun setSurfaceVisible(id: String, visible: Boolean) {
        val changed = if (visible) visibleSurfaces.add(id) else visibleSurfaces.remove(id)
        if (!changed) return
        recordActivity()
        if (visible && target != null &&
            (phase == ConnectionPhase.IDLE || phase == ConnectionPhase.FAILED || phase == ConnectionPhase.YIELDED)
        ) {
            openChannel()
        }
    }

    // endregion
    // region Lifecycle

    /** [isRetry] keeps the attempt counter; any user-initiated open starts a fresh budget. */
    private fun openChannel(isRetry: Boolean = false) {
        val t = target ?: return fail(SessionError.NoTarget)
        if (adapter == null) {
            return fail(SessionError.UnsupportedModel("${t.model.displayName}: support is planned but not implemented yet"))
        }
        closeChannel()
        val gen = ++generation
        phase = ConnectionPhase.CONNECTING
        error = null
        if (!isRetry) connectAttempts = 0
        connectAttempts++
        publish()
        channel.open(t.address, listenerFor(gen))
        connectTimeoutJob = scope.launch {
            delay(config.connectTimeoutMs)
            onConnectTimeout()
        }
    }

    private fun onConnectTimeout() {
        if (phase != ConnectionPhase.CONNECTING && phase != ConnectionPhase.INITIALIZING) return
        if (connectAttempts <= config.connectRetries) {
            openChannel(isRetry = true)
        } else {
            fail(SessionError.Timeout("The headphones did not answer. Make sure they are on and connected for audio; if Sony Sound Connect is open on another device, close it and try again."))
        }
    }

    /**
     * Tears down the channel and per-connection state; keeps target, intents and last-known
     * state. The idle timer is left alone: it tracks user activity, not the connection.
     */
    private fun closeChannel() {
        generation++
        channel.close()
        listOf(connectTimeoutJob, initRetryJob, ackTimeoutJob, settleJob, stateTimeoutJob).forEach { it?.cancel() }
        outgoing.clear()
        awaiting = null
        awaitingInit = false
        sequenceNumber = 0
        parser.reset()
        stateIsLive = false
    }

    private fun fail(e: SessionError) {
        pending.clear()
        closeChannel()
        connectAttempts = 0
        phase = ConnectionPhase.FAILED
        error = e
        publish()
    }

    private fun listenerFor(gen: Int) = object : ControlChannel.Listener {
        override fun onOpened() = post(gen) { handleOpened() }
        override fun onData(bytes: ByteArray) = post(gen) { handleData(bytes) }
        override fun onClosed() = post(gen) { handleRemoteClosed() }
        override fun onFailed(error: ChannelError) = post(gen) { fail(SessionError.Channel(error)) }
    }

    private fun post(gen: Int, block: () -> Unit) {
        scope.launch { if (gen == generation) block() }
    }

    private fun handleOpened() {
        if (phase != ConnectionPhase.CONNECTING) return
        phase = ConnectionPhase.INITIALIZING
        initAttempts = 0
        publish()
        sendInit()
    }

    private fun sendInit() {
        awaitingInit = true
        write(MdrMessage(MessageType.COMMAND_1, sequenceNumber, MdrCommands.init()))
        initRetryJob?.cancel()
        initRetryJob = scope.launch {
            delay(config.initRetryMs)
            if (phase != ConnectionPhase.INITIALIZING) return@launch
            if (initAttempts >= config.initRetries) {
                fail(SessionError.HandshakeFailed("The headphones did not answer the handshake. Another device may be controlling them."))
            } else {
                initAttempts++
                sendInit()
            }
        }
    }

    private fun handleRemoteClosed() {
        val hadWork = pending.isNotEmpty() || awaiting?.command != null || outgoing.any { it.command != null }
        val wasOpen = phase == ConnectionPhase.CONNECTED || phase == ConnectionPhase.READING_STATE || phase == ConnectionPhase.INITIALIZING
        // Unsent and unacknowledged intents go back to the front of the queue.
        val unacked = (listOfNotNull(awaiting?.command) + outgoing.mapNotNull { it.command })
        unacked.asReversed().forEach { cmd -> if (pending.none { it.coalesceKey == cmd.coalesceKey }) pending.addFirst(cmd) }
        closeChannel()
        when {
            phase == ConnectionPhase.CONNECTING -> fail(SessionError.Channel(ChannelError.ConnectFailed("The control channel closed while connecting")))
            wasOpen && !hadWork -> {
                phase = ConnectionPhase.YIELDED
                error = SessionError.Yielded("Another device took control of the headphones (or they were switched off). Use any control here to take them back.")
                publish()
            }
            hadWork && !reconnectedForPendingWork -> {
                reconnectedForPendingWork = true
                openChannel()
            }
            else -> fail(SessionError.Channel(ChannelError.Io("The control channel closed before the command completed")))
        }
    }

    // endregion
    // region Activity and idle release

    private fun recordActivity() {
        idleJob?.cancel()
        idleDue = false
        if (!config.releaseWhenIdle || visibleSurfaces.isNotEmpty()) return
        idleJob = scope.launch {
            delay(config.idleGraceMs)
            idleDue = true
            releaseIfIdle()
        }
    }

    private fun hasCommandWork(): Boolean =
        pending.isNotEmpty() || awaiting?.command != null || outgoing.any { it.command != null }

    private fun releaseIfIdle() {
        if (!idleDue || visibleSurfaces.isNotEmpty() || hasCommandWork()) return
        if (phase == ConnectionPhase.IDLE || phase == ConnectionPhase.FAILED || phase == ConnectionPhase.YIELDED) return
        closeChannel()
        connectAttempts = 0
        phase = ConnectionPhase.IDLE
        publish()
    }

    // endregion
    // region Messages

    private fun write(message: MdrMessage) {
        val bytes = message.encode()
        traffic?.invoke("TX", bytes)
        channel.write(bytes)
    }

    private fun handleData(bytes: ByteArray) {
        traffic?.invoke("RX", bytes)
        for (message in parser.feed(bytes)) handle(message)
    }

    private fun handle(message: MdrMessage) {
        if (message.type == MessageType.ACK) {
            if (message.sequenceNumber == sequenceNumber) return // duplicate/unexpected
            sequenceNumber = message.sequenceNumber
            ackTimeoutJob?.cancel()
            if (awaitingInit) {
                awaitingInit = false
            } else {
                awaiting?.command?.let { confirmed = confirmed.applyOptimistic(it) }
                awaiting = null
            }
            afterQueueProgress()
            return
        }
        if (message.payload.isEmpty()) return
        // ACK the device's message with the complement of *its* sequence number.
        write(MdrMessage(MessageType.ACK, message.sequenceNumber xor 0x01, ByteArray(0)))
        val event = adapter?.decode(message.type, message.payload) ?: return
        apply(event)
    }

    private fun apply(event: MdrEvent) {
        if (event is MdrEvent.ProtocolInfo) {
            if (phase != ConnectionPhase.INITIALIZING) return
            initRetryJob?.cancel()
            connectTimeoutJob?.cancel()
            val expected = adapter?.expectedProtocol
            if (event.version != ProtocolVersion.UNKNOWN && expected != null && event.version != expected) {
                return fail(SessionError.ProtocolMismatch("These headphones speak MDR ${event.version}, expected $expected for ${target?.model?.displayName}. Nothing was written."))
            }
            confirmed = confirmed.apply(event)
            connectAttempts = 0
            reconnectedForPendingWork = false
            phase = ConnectionPhase.READING_STATE
            initialStateTimedOut = false
            adapter?.stateQueries()?.forEach { outgoing.addLast(QueuedMessage(it, null)) }
            stateTimeoutJob = scope.launch {
                delay(config.stateTimeoutMs)
                if (phase == ConnectionPhase.READING_STATE) finishStateRead(timedOut = true)
            }
            publish()
            sendNext()
            return
        }
        confirmed = confirmed.apply(event)
        publish()
    }

    private fun afterQueueProgress() {
        if (phase == ConnectionPhase.READING_STATE && outgoing.isEmpty() && awaiting == null && !awaitingInit) {
            settleJob?.cancel()
            settleJob = scope.launch {
                delay(config.stateSettleMs)
                if (phase == ConnectionPhase.READING_STATE) finishStateRead(timedOut = false)
            }
        }
        sendNext()
        releaseIfIdle()
        publish()
    }

    private fun finishStateRead(timedOut: Boolean) {
        stateTimeoutJob?.cancel()
        val s = confirmed
        initialStateTimedOut = timedOut && (s.ambientSound == null || s.battery == null || s.equalizer == null)
        stateIsLive = true
        phase = ConnectionPhase.CONNECTED
        publish()
        sendNext()
    }

    /** Stop-and-wait: one message in flight. Intents are encoded only once connected. */
    private fun sendNext() {
        if (awaiting != null || awaitingInit) return
        if (outgoing.isEmpty() && phase == ConnectionPhase.CONNECTED && pending.isNotEmpty()) {
            val command = pending.removeFirst()
            val encodeAgainst = inFlightCommands().fold(confirmed) { s, c -> s.applyOptimistic(c) }
            val messages = adapter?.encode(command, encodeAgainst).orEmpty()
            messages.forEachIndexed { i, out ->
                // The command counts as accepted when its last message is acknowledged.
                outgoing.addLast(QueuedMessage(out, if (i == messages.lastIndex) command else null))
            }
        }
        val next = outgoing.removeFirstOrNull() ?: return
        awaiting = next
        write(MdrMessage(next.outgoing.type, sequenceNumber, next.outgoing.payload))
        ackTimeoutJob?.cancel()
        ackTimeoutJob = scope.launch {
            delay(config.ackTimeoutMs)
            if (awaiting === next) {
                // Give up on this message; a late reply will not match the sequence number.
                awaiting = null
                afterQueueProgress()
            }
        }
        publish()
    }

    private fun inFlightCommands(): List<DeviceCommand> =
        listOfNotNull(awaiting?.command) + outgoing.mapNotNull { it.command }

    // endregion

    private fun publish() {
        val shown = (inFlightCommands() + pending).fold(confirmed) { s, c -> s.applyOptimistic(c) }
        _snapshot.value = SessionSnapshot(
            target = target,
            phase = phase,
            state = shown,
            stateIsLive = stateIsLive,
            error = error,
            initialStateTimedOut = initialStateTimedOut,
            pendingCommands = pending.size,
        )
    }

    companion object {
        fun featureFor(command: DeviceCommand): Feature? = when (command) {
            is DeviceCommand.SetAmbientSound -> Feature.NOISE_CANCELLING
            is DeviceCommand.SetEqualizerPreset -> Feature.EQ_PRESETS
            is DeviceCommand.SetEqualizerBands -> Feature.EQ_CUSTOM_BANDS
            is DeviceCommand.SetListeningMode, is DeviceCommand.SetBgmRoomSize -> Feature.LISTENING_MODE
            is DeviceCommand.SetSpeakToChat, is DeviceCommand.SetSpeakToChatConfig -> Feature.SPEAK_TO_CHAT
            is DeviceCommand.SetAutomaticPowerOff -> Feature.AUTO_POWER_OFF
            is DeviceCommand.SetPauseWhenTakenOff -> Feature.WEAR_DETECTION
            is DeviceCommand.SwitchPlayback -> Feature.PLAYBACK_SOURCE_SWITCH
            is DeviceCommand.Raw -> null
        }
    }
}
