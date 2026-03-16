package com.artmondo.algomodo.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

object AudioAnalyzer {

    /**
     * Decode audio from [uri] and compute frequency band analysis.
     * Returns null if decoding fails.
     */
    suspend fun analyze(context: Context, uri: Uri): AudioAnalysis? = withContext(Dispatchers.IO) {
        val pcm = decodeToMono(context, uri) ?: return@withContext null
        val sampleRate = pcm.sampleRate
        val samples = pcm.samples

        // FFT window: power-of-2 size closest to 1/60s
        val fftSize = 1024 // ~23ms at 44.1kHz
        val hopSize = (sampleRate / 60f).roundToInt().coerceAtLeast(1) // ~60 windows/sec
        val windowCount = ((samples.size - fftSize) / hopSize).coerceAtLeast(0) + 1
        val windowSec = hopSize.toFloat() / sampleRate
        val durationSec = samples.size.toFloat() / sampleRate

        // Frequency bin boundaries
        val binHz = sampleRate.toFloat() / fftSize
        val bassEnd = (250f / binHz).roundToInt().coerceAtMost(fftSize / 2)
        val midEnd = (4000f / binHz).roundToInt().coerceAtMost(fftSize / 2)
        val highEnd = (fftSize / 2)

        // Hann window
        val hann = FloatArray(fftSize) { i ->
            (0.5f * (1f - kotlin.math.cos(2.0 * Math.PI * i / (fftSize - 1)))).toFloat()
        }

        val bass = FloatArray(windowCount)
        val mid = FloatArray(windowCount)
        val high = FloatArray(windowCount)
        val spectra = Array(windowCount) { FloatArray(0) }

        // Track max for normalization
        var maxBass = 0f; var maxMid = 0f; var maxHigh = 0f

        val windowBuf = FloatArray(fftSize)

        for (w in 0 until windowCount) {
            if (!isActive) return@withContext null
            val offset = w * hopSize

            // Fill and window
            for (i in 0 until fftSize) {
                val idx = offset + i
                windowBuf[i] = if (idx < samples.size) samples[idx] * hann[i] else 0f
            }

            val mag = FFT.magnitudeSpectrum(windowBuf)
            spectra[w] = mag

            // Band energies (mean magnitude)
            var bSum = 0f; var mSum = 0f; var hSum = 0f
            for (i in 1 until bassEnd) bSum += mag[i]
            for (i in bassEnd until midEnd) mSum += mag[i]
            for (i in midEnd until highEnd) hSum += mag[i]

            val bCount = (bassEnd - 1).coerceAtLeast(1)
            val mCount = (midEnd - bassEnd).coerceAtLeast(1)
            val hCount = (highEnd - midEnd).coerceAtLeast(1)

            bass[w] = bSum / bCount
            mid[w] = mSum / mCount
            high[w] = hSum / hCount

            if (bass[w] > maxBass) maxBass = bass[w]
            if (mid[w] > maxMid) maxMid = mid[w]
            if (high[w] > maxHigh) maxHigh = high[w]
        }

        // Normalize to [0, 1]
        if (maxBass > 0f) for (i in bass.indices) bass[i] /= maxBass
        if (maxMid > 0f) for (i in mid.indices) mid[i] /= maxMid
        if (maxHigh > 0f) for (i in high.indices) high[i] /= maxHigh

        AudioAnalysis(durationSec, windowSec, bass, mid, high, spectra)
    }

    private data class PcmData(val samples: FloatArray, val sampleRate: Int)

    private fun decodeToMono(context: Context, uri: Uri): PcmData? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            return null
        }

        // Find audio track
        var audioTrackIdx = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIdx = i
                format = f
                break
            }
        }
        if (audioTrackIdx < 0 || format == null) {
            extractor.release()
            return null
        }

        extractor.selectTrack(audioTrackIdx)
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
            extractor.release()
            return null
        }

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            extractor.release()
            return null
        }

        codec.configure(format, null, null, 0)
        codec.start()

        val outputSamples = mutableListOf<Float>()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val timeoutUs = 10_000L

        while (!outputDone) {
            // Feed input
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(timeoutUs)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx) ?: continue
                    val read = extractor.readSampleData(buf, 0)
                    if (read < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // Drain output
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (outIdx >= 0) {
                val buf = codec.getOutputBuffer(outIdx)
                if (buf != null && bufferInfo.size > 0) {
                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    buf.position(bufferInfo.offset)
                    val shortCount = bufferInfo.size / 2
                    for (s in 0 until shortCount step channels) {
                        // Mix to mono
                        var sum = 0f
                        for (ch in 0 until min(channels, shortCount - s)) {
                            sum += buf.short.toFloat() / 32768f
                        }
                        outputSamples.add(sum / channels)
                    }
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        if (outputSamples.isEmpty()) return null
        return PcmData(outputSamples.toFloatArray(), sampleRate)
    }
}
