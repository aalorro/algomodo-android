package com.artmondo.algomodo.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

object AudioAnalyzer {

    // Cap at 5 minutes to prevent OOM
    private const val MAX_SAMPLES = 44100 * 5 * 60

    // Match web version: FFT_SIZE=256, 128 bins
    private const val FFT_SIZE = 256

    suspend fun analyze(context: Context, uri: Uri): AudioAnalysis? = withContext(Dispatchers.IO) {
        try {
            val pcm = decodeToMono(context, uri) ?: return@withContext null
            val sampleRate = pcm.sampleRate
            val samples = pcm.samples

            if (samples.isEmpty()) return@withContext null

            val hopSize = (sampleRate / 60f).roundToInt().coerceAtLeast(1)
            val windowCount = ((samples.size - FFT_SIZE) / hopSize).coerceAtLeast(0) + 1
            val windowSec = hopSize.toFloat() / sampleRate
            val durationSec = samples.size.toFloat() / sampleRate

            if (durationSec <= 0f || windowCount <= 0) return@withContext null

            // Match web: bands are percentage of bins (128 bins total)
            val bins = FFT_SIZE / 2
            val bassEnd = (bins * 0.1f).toInt().coerceAtLeast(1)   // 0–10%
            val midEnd = (bins * 0.5f).toInt()                      // 10–50%

            // Hann window (matches web: 0.5 - 0.5 * cos(2*PI*i / (N-1)))
            val hann = FloatArray(FFT_SIZE) { i ->
                (0.5f * (1f - kotlin.math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)))).toFloat()
            }

            val bass = FloatArray(windowCount)
            val mid = FloatArray(windowCount)
            val high = FloatArray(windowCount)
            val spectra = Array(windowCount) { FloatArray(0) }

            var maxBass = 0f; var maxMid = 0f; var maxHigh = 0f
            val windowBuf = FloatArray(FFT_SIZE)

            for (w in 0 until windowCount) {
                if (!isActive) return@withContext null
                val offset = w * hopSize

                for (i in 0 until FFT_SIZE) {
                    val idx = offset + i
                    windowBuf[i] = if (idx < samples.size) samples[idx] * hann[i] else 0f
                }

                val mag = FFT.magnitudeSpectrum(windowBuf)
                spectra[w] = mag

                // Band energies (matching web percentage splits)
                var bSum = 0f; var mSum = 0f; var hSum = 0f
                for (i in 0 until bassEnd) bSum += mag[i]
                for (i in bassEnd until midEnd) mSum += mag[i]
                for (i in midEnd until bins) hSum += mag[i]

                bass[w] = bSum / bassEnd
                mid[w] = mSum / (midEnd - bassEnd).coerceAtLeast(1)
                high[w] = hSum / (bins - midEnd).coerceAtLeast(1)

                if (bass[w] > maxBass) maxBass = bass[w]
                if (mid[w] > maxMid) maxMid = mid[w]
                if (high[w] > maxHigh) maxHigh = high[w]
            }

            // Normalize to [0, 1]
            if (maxBass > 0f) for (i in bass.indices) bass[i] /= maxBass
            if (maxMid > 0f) for (i in mid.indices) mid[i] /= maxMid
            if (maxHigh > 0f) for (i in high.indices) high[i] /= maxHigh

            AudioAnalysis(durationSec, windowSec, bass, mid, high, spectra)
        } catch (_: Throwable) {
            null
        }
    }

    private class PcmResult(val samples: FloatArray, val sampleRate: Int)

    private fun decodeToMono(context: Context, uri: Uri): PcmResult? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (_: Exception) {
            try { extractor.release() } catch (_: Exception) {}
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
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
            extractor.release()
            return null
        }

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (_: Exception) {
            extractor.release()
            return null
        }

        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (_: Exception) {
            try { codec.release() } catch (_: Exception) {}
            extractor.release()
            return null
        }

        // Detect output PCM encoding — default to 16-bit
        var isFloatPcm = false
        try {
            val outFmt = codec.outputFormat
            val enc = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            isFloatPcm = (enc == AudioFormat.ENCODING_PCM_FLOAT)
            channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)
        } catch (_: Exception) {}

        // Pre-allocate output buffer
        val durationUs = try { format.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { 0L }
        val estimatedSamples = if (durationUs > 0) {
            ((durationUs / 1_000_000.0) * sampleRate).toInt().coerceIn(1, MAX_SAMPLES)
        } else {
            sampleRate * 30
        }
        var outputSamples = FloatArray(min(estimatedSamples + sampleRate, MAX_SAMPLES))
        var writeIdx = 0

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val timeoutUs = 10_000L

        try {
            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val read = extractor.readSampleData(inBuf, 0)
                            if (read < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // Drain output
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Output format changed — update encoding & channel info
                        try {
                            val newFmt = codec.outputFormat
                            val enc = newFmt.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                            isFloatPcm = (enc == AudioFormat.ENCODING_PCM_FLOAT)
                            channels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                        } catch (_: Exception) {}
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null && bufferInfo.size > 0) {
                            buf.order(ByteOrder.LITTLE_ENDIAN)
                            buf.position(bufferInfo.offset)

                            val bytesPerSample = if (isFloatPcm) 4 else 2
                            val totalSamples = bufferInfo.size / bytesPerSample
                            val bytesPerFrame = bytesPerSample * channels

                            var s = 0
                            while (s + channels <= totalSamples && writeIdx < MAX_SAMPLES) {
                                if (buf.remaining() < bytesPerFrame) break

                                var sum = 0f
                                for (ch in 0 until channels) {
                                    sum += if (isFloatPcm) {
                                        buf.float.coerceIn(-1f, 1f)
                                    } else {
                                        buf.short.toFloat() / 32768f
                                    }
                                }

                                // Grow output array if needed
                                if (writeIdx >= outputSamples.size) {
                                    val newSize = min(outputSamples.size * 2, MAX_SAMPLES)
                                    if (newSize <= outputSamples.size) break
                                    outputSamples = outputSamples.copyOf(newSize)
                                }

                                outputSamples[writeIdx++] = sum / channels
                                s += channels
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    // INFO_TRY_AGAIN_LATER or other negative values — just loop
                }

                if (writeIdx >= MAX_SAMPLES) outputDone = true
            }
        } catch (_: Exception) {
            // Decode error — use whatever we decoded so far
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }

        if (writeIdx == 0) return null
        val trimmed = if (writeIdx < outputSamples.size) outputSamples.copyOf(writeIdx) else outputSamples
        return PcmResult(trimmed, sampleRate)
    }
}
