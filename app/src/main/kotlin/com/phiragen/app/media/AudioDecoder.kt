package com.phiragen.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioException(message: String) : Exception(message)

data class AudioInfo(
    val uri: Uri,
    val displayName: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val extension: String,
    val sizeBytes: Long,
    val cover: Bitmap?,
)

object AudioProbe {
    /** 最长支持的音频时长, 分析结果需要常驻内存 */
    const val MAX_DURATION_MS = 15 * 60 * 1000L

    fun probe(context: Context, uri: Uri): AudioInfo {
        var name = "audio"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                ?: name.substringBeforeLast('.')
            val artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: ""
            val cover = r.embeddedPicture?.let { runCatching { decodeScaled(it) }.getOrNull() }
            if (dur <= 0) throw AudioException("无法读取音频时长, 文件可能已损坏")
            if (dur > MAX_DURATION_MS) throw AudioException("音频超过 15 分钟, 请裁剪后再导入")
            return AudioInfo(uri, name, title, artist, dur, name.substringAfterLast('.', "").lowercase(), size, cover)
        } catch (e: AudioException) {
            throw e
        } catch (e: Exception) {
            throw AudioException("无法识别该音频文件")
        } finally {
            r.release()
        }
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / sample > 1600 || o.outHeight / sample > 1600) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
    }
}

/** 解码任意系统支持的音频为 16 位 PCM, 以块为单位回调, 内存占用恒定 */
object PcmDecoder {
    class Format(val sampleRate: Int, val channels: Int)

    fun decode(
        context: Context,
        uri: Uri,
        onFormat: (Format) -> Unit,
        onPcm: (ShortArray, Int) -> Unit,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                throw AudioException("无法打开音频文件")
            }
            var track = -1
            var inFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i; inFormat = f; break
                }
            }
            if (track < 0 || inFormat == null) throw AudioException("文件中没有音频轨道")
            extractor.selectTrack(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            codec = try {
                MediaCodec.createDecoderByType(mime).also { it.configure(inFormat, null, null, 0); it.start() }
            } catch (e: Exception) {
                throw AudioException("系统不支持该音频编码")
            }
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var channels = if (inFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var rate = if (inFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var floatPcm = false
            var announced = false
            var shorts = ShortArray(8192)
            var lastProgress = -1f
            while (!sawOutputEos) {
                if (isCancelled()) throw kotlinx.coroutines.CancellationException("已取消")
                if (!sawInputEos) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        if (!announced) { onFormat(Format(rate, channels)); announced = true }
                    }
                    out >= 0 -> {
                        if (!announced) { onFormat(Format(rate, channels)); announced = true }
                        if (info.size > 0) {
                            val bb = codec.getOutputBuffer(out)!!
                            bb.position(info.offset)
                            bb.limit(info.offset + info.size)
                            bb.order(ByteOrder.nativeOrder())
                            val count = if (floatPcm) info.size / 4 else info.size / 2
                            if (shorts.size < count) shorts = ShortArray(count)
                            if (floatPcm) {
                                val fb = bb.asFloatBuffer()
                                for (i in 0 until count) shorts[i] = (fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            } else {
                                bb.asShortBuffer().get(shorts, 0, count)
                            }
                            onPcm(shorts, count)
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                        if (durationUs > 0) {
                            val p = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                            if (p - lastProgress >= 0.01f) { lastProgress = p; onProgress(p) }
                        }
                    }
                }
            }
            onProgress(1f)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }
}

/** 流式写入 16 位 PCM WAV, 结束时回填头部长度 */
class WavWriter(private val file: File, private val sampleRate: Int, private val channels: Int) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    fun write(samples: ShortArray, count: Int) {
        val bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bb.putShort(samples[i])
        raf.write(bb.array())
        dataBytes += count * 2L
    }

    fun close() {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).toInt()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(channels.toShort())
        h.putInt(sampleRate); h.putInt(sampleRate * channels * 2); h.putShort((channels * 2).toShort()); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(h.array())
        raf.close()
    }
}
