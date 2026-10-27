package io.github.andru1989.xmaudiostudio.platform

import io.github.andru1989.xmaudiostudio.core.device.HeadphoneDevice
import io.github.andru1989.xmaudiostudio.core.session.ConnectionPhase
import io.github.andru1989.xmaudiostudio.core.session.SessionSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The live snapshot to decide on. When the state is not live, a tap is user activity: it opens
 * the channel (or takes it back after YIELDED/FAILED) and waits for the real state to be read.
 * Null when it never became live (failed, yielded again or timed out).
 */
internal suspend fun awaitLiveSnapshot(session: HeadphoneDevice, timeoutMs: Long): SessionSnapshot? {
    val now = session.snapshot.value
    if (now.stateIsLive) return now
    session.connect()
    return withTimeoutOrNull(timeoutMs) {
        session.snapshot.first { it.stateIsLive || it.phase == ConnectionPhase.FAILED || it.phase == ConnectionPhase.YIELDED }
    }?.takeIf { it.stateIsLive }
}

/**
 * Waits until queued intents have been encoded and written, plus a short grace for the ACK, so a
 * broadcast receiver keeps the process alive for that long and no longer.
 */
internal suspend fun awaitDelivery(session: HeadphoneDevice, timeoutMs: Long, ackGraceMs: Long) {
    withTimeoutOrNull(timeoutMs) { session.snapshot.first { it.pendingCommands == 0 } }
    delay(ackGraceMs)
}
