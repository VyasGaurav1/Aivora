package com.chronoit.aivora.voice

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded 16 kHz mono PCM input supplied by an Android recognition client. */
class DescriptorAudioSource(private val descriptor: ParcelFileDescriptor) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    fun frames(): Flow<AudioFrame> = flow {
        val bytes = ByteArray(3200)
        var carry = -1
        var count = 0
        val poll = StructPollfd().apply { fd = descriptor.fileDescriptor; events = OsConstants.POLLIN.toShort() }
        val deadline = SystemClock.elapsedRealtime() + 20_000
        try {
            while (!stopped.get() && count < 240000 && SystemClock.elapsedRealtime() < deadline) {
                currentCoroutineContext().ensureActive()
                if (Os.poll(arrayOf(poll), 100) == 0) continue
                if (poll.revents.toInt() and OsConstants.POLLNVAL != 0) break
                val read = Os.read(descriptor.fileDescriptor, bytes, 0, bytes.size)
                if (read == 0) break
                val samples = ShortArray((read + if (carry >= 0) 1 else 0) / 2)
                var index = 0; var output = 0
                if (carry >= 0 && read > 0) { samples[output++] = (carry or (bytes[index++].toInt() shl 8)).toShort(); carry = -1 }
                while (index + 1 < read) { samples[output++] = ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort(); index += 2 }
                if (index < read) carry = bytes[index].toInt() and 255
                if (samples.isNotEmpty()) {
                    val remaining = 240000 - count
                    val bounded = if (samples.size > remaining) samples.copyOf(remaining) else samples
                    count += bounded.size
                    emit(AudioFrame(bounded, 16000))
                }
            }
            require(carry < 0) { "PCM source ended inside a sample" }
        } finally { close() }
    }.flowOn(Dispatchers.IO).buffer(8)
    fun stop() { stopped.set(true) }
    override fun close() { stopped.set(true); runCatching { descriptor.close() } }
}
