/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight PCM pass-through processor that applies a left/right stereo pan.
 * Used during crossfades so the outgoing track can pan out through the left
 * channel while the incoming track pans in from the right, instead of a plain
 * volume dim/rise - see MusicService.performCrossfadeSwap().
 *
 * [pan] ranges from -1f (fully left, right channel silent) to +1f (fully
 * right, left channel silent); 0f is centered/unaffected. Only stereo
 * (2-channel) PCM is affected by a non-zero pan; other channel counts, or a
 * centered pan, pass straight through unchanged.
 */
@UnstableApi
@Suppress("DEPRECATION")
class StereoPanAudioProcessor : AudioProcessor {

    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID

    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    /** -1f = fully left, 0f = centered, +1f = fully right. */
    @Volatile
    var pan: Float = 0f

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding

        if (encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        return inputAudioFormat
    }

    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            outputBuffer = EMPTY_BUFFER
            return
        }

        val currentPan = pan
        val size = inputBuffer.remaining()

        if (channelCount != 2 || currentPan == 0f) {
            // Nothing to do - pass the audio through unchanged.
            val out = replaceOutputBuffer(size)
            out.put(inputBuffer)
            out.flip()
            return
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val leftGain = 1f - max(currentPan, 0f)
        val rightGain = 1f + min(currentPan, 0f)

        val out = replaceOutputBuffer(size)
        val frameCount = size / 4 // 2 channels * 2 bytes per sample
        val basePosition = inputBuffer.position()

        repeat(frameCount) { frameIndex ->
            val frameOffset = basePosition + frameIndex * 4
            val leftSample = inputBuffer.getShort(frameOffset)
            val rightSample = inputBuffer.getShort(frameOffset + 2)

            out.putShort((leftSample * leftGain).toInt().toShort())
            out.putShort((rightSample * rightGain).toInt().toShort())
        }
        // Mark the whole input as consumed, matching the AudioProcessor contract,
        // even if a few trailing bytes didn't form a full frame.
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val output = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return output
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY_BUFFER

    @Deprecated("Deprecated in AudioProcessor")
    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
    }

    @Deprecated("Deprecated in AudioProcessor")
    override fun reset() {
        flush()
        channelCount = 0
        encoding = C.ENCODING_INVALID
        pan = 0f
    }

    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        return outputBuffer
    }

    companion object {
        private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
