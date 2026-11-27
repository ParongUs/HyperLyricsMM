package com.hyperlyrics.mm.lyrics.local

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.hyperlyrics.mm.common.ModuleConfig
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.lyrics.cache.LyricsCacheDatabase
import com.hyperlyrics.mm.lyrics.model.LyricLine
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

data class SpeechExtractorStatus(
    val running: Boolean = false,
    val lastLine: String = "",
    val capturedCount: Int = 0,
    val trackTitle: String = "",
    val trackArtist: String = ""
)

/**
 * Opt-in on-device speech-to-lyrics foreground service using Android 13+ (API 33+)
 * offline `SpeechRecognizer.createOnDeviceSpeechRecognizer` combined with optional
 * `AudioPlaybackCaptureConfiguration` internal audio piping (`EXTRA_AUDIO_SOURCE`).
 *
 * Recognized lines are timestamped relative to playback start, persisted & pinned in
 * [LyricsCacheDatabase], and broadcast to SystemUI so Hyper Island updates live.
 */
class LocalSpeechLyricsService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val runningFlag = AtomicBoolean(false)
    private val capturedLines = CopyOnWriteArrayList<LyricLine>()

    private lateinit var cacheDb: LyricsCacheDatabase
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var pipeReadFd: ParcelFileDescriptor? = null
    private var pipeWriteFd: ParcelFileDescriptor? = null
    private var recognizer: SpeechRecognizer? = null

    private var trackTitle: String = ""
    private var trackArtist: String = ""
    private var trackVideoId: String? = null
    private var sessionOriginRealtimeMs: Long = 0L
    private var baseTrackPositionMs: Long = 0L
    private var currentUtteranceStartMs: Long = 0L
    private var useInternalCapture: Boolean = false

    override fun onCreate() {
        super.onCreate()
        cacheDb = LyricsCacheDatabase(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopExtraction()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                trackTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().trim()
                trackArtist = intent.getStringExtra(EXTRA_ARTIST).orEmpty().trim()
                trackVideoId = intent.getStringExtra(EXTRA_VIDEO_ID)?.trim()?.ifBlank { null }
                baseTrackPositionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L).coerceAtLeast(0L)
                sessionOriginRealtimeMs = SystemClock.elapsedRealtime()
                capturedLines.clear()

                val projCode = intent.getIntExtra(EXTRA_PROJECTION_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val projData = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
                useInternalCapture = projCode == Activity.RESULT_OK && projData != null

                promoteToForeground(useInternalCapture)

                if (useInternalCapture && projData != null) {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                    mediaProjection = runCatching { mpm?.getMediaProjection(projCode, projData) }.getOrNull()
                    if (mediaProjection == null) useInternalCapture = false
                }

                startExtractionLoop()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopExtraction()
        super.onDestroy()
    }

    private fun promoteToForeground(hasProjection: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Local Speech Lyrics Extractor",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("HyperLyricsMM")
            .setContentText("Extracting on-device lyrics locally...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        val fgsType = if (hasProjection) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(NOTIFICATION_ID, notification, fgsType)
    }

    private fun startExtractionLoop() {
        if (!runningFlag.compareAndSet(false, true)) return
        _status.value = SpeechExtractorStatus(
            running = true,
            lastLine = "",
            capturedCount = 0,
            trackTitle = trackTitle,
            trackArtist = trackArtist
        )
        mainHandler.post { launchRecognitionCycle() }
    }

    @SuppressLint("MissingPermission")
    private fun launchRecognitionCycle() {
        if (!runningFlag.get()) return
        tearDownCycle()

        val onDeviceReady = SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        val sr = if (onDeviceReady) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        recognizer = sr
        currentUtteranceStartMs = currentTrackOffsetMs()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        if (useInternalCapture && mediaProjection != null) {
            val piped = setupInternalAudioPipe()
            if (piped != null) {
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, piped)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            }
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                currentUtteranceStartMs = currentTrackOffsetMs()
            }

            override fun onBeginningOfSpeech() {
                currentUtteranceStartMs = currentTrackOffsetMs()
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    _status.value = _status.value.copy(lastLine = text)
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    commitRecognizedLine(text)
                }
                scheduleNextCycle(180L)
            }

            override fun onError(error: Int) {
                ModuleLogger.d(TAG, "On-device ASR cycle ended with code=$error; restarting")
                scheduleNextCycle(if (error == SpeechRecognizer.ERROR_NO_MATCH) 220L else 600L)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        runCatching {
            sr.startListening(intent)
        }.onFailure { err ->
            ModuleLogger.w(TAG, "startListening failed", err)
            scheduleNextCycle(1000L)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupInternalAudioPipe(): ParcelFileDescriptor? {
        val projection = mediaProjection ?: return null
        return runCatching {
            val pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]
            pipeReadFd = readEnd
            pipeWriteFd = writeEnd

            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            audioRecord = record
            record.startRecording()

            val outStream = ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)
            val thread = Thread({
                val buf = ByteArray(2048)
                try {
                    while (runningFlag.get() && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val read = record.read(buf, 0, buf.size)
                        if (read > 0) {
                            outStream.write(buf, 0, read)
                        }
                    }
                } catch (_: IOException) {
                    // Pipe closed by recognizer end
                } finally {
                    runCatching { outStream.close() }
                }
            }, "HyperLyricsMM-AudioPipe").apply { isDaemon = true }
            captureThread = thread
            thread.start()

            readEnd
        }.onFailure {
            ModuleLogger.w(TAG, "AudioPlaybackCapture pipe unavailable, falling back to mic", it)
        }.getOrNull()
    }

    private fun commitRecognizedLine(text: String) {
        val nowOffset = currentTrackOffsetMs()
        val start = currentUtteranceStartMs.coerceAtMost(nowOffset)
        val end = maxOf(nowOffset, start + 1800L)
        val line = LyricLine(
            text = text,
            startTimeMs = start,
            endTimeMs = end,
            isInterlude = false
        )
        capturedLines.add(line)

        _status.value = _status.value.copy(
            lastLine = text,
            capturedCount = capturedLines.size
        )

        if (trackTitle.isNotBlank()) {
            val snapshot = TrackLyrics(
                videoId = trackVideoId,
                title = trackTitle,
                artist = trackArtist,
                durationMs = maxOf(end + 5_000L, 180_000L),
                lines = capturedLines.toList(),
                isSynced = true,
                sourceMessage = "Local On-Device ASR"
            )
            cacheDb.put(snapshot, pinned = true)
            runCatching {
                sendBroadcast(Intent(ModuleConfig.ACTION_LYRICS_UPDATED))
            }
        }
    }

    private fun currentTrackOffsetMs(): Long {
        val elapsed = (SystemClock.elapsedRealtime() - sessionOriginRealtimeMs).coerceAtLeast(0L)
        return baseTrackPositionMs + elapsed
    }

    private fun scheduleNextCycle(delayMs: Long) {
        if (!runningFlag.get()) return
        mainHandler.postDelayed({ launchRecognitionCycle() }, delayMs)
    }

    private fun tearDownCycle() {
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
        captureThread?.interrupt()
        captureThread = null
        runCatching { pipeWriteFd?.close() }
        runCatching { pipeReadFd?.close() }
        pipeWriteFd = null
        pipeReadFd = null
        runCatching {
            recognizer?.cancel()
            recognizer?.destroy()
        }
        recognizer = null
    }

    private fun stopExtraction() {
        runningFlag.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        tearDownCycle()
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        _status.value = _status.value.copy(running = false)
    }

    companion object {
        private const val TAG = "LocalSpeechLyrics"
        private const val CHANNEL_ID = "hyperlyrics_local_speech"
        private const val NOTIFICATION_ID = 4109
        private const val SAMPLE_RATE = 16_000

        const val ACTION_START = "io.github.parongus.hyperlyricsmm.action.START_LOCAL_SPEECH"
        const val ACTION_STOP = "io.github.parongus.hyperlyricsmm.action.STOP_LOCAL_SPEECH"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_VIDEO_ID = "extra_video_id"
        const val EXTRA_POSITION_MS = "extra_position_ms"
        const val EXTRA_PROJECTION_CODE = "extra_projection_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"

        private val _status = MutableStateFlow(SpeechExtractorStatus())
        val status: StateFlow<SpeechExtractorStatus> = _status.asStateFlow()

        fun isOnDeviceAsrSupported(context: Context): Boolean {
            return runCatching {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ||
                    SpeechRecognizer.isRecognitionAvailable(context)
            }.getOrDefault(false)
        }
    }
}
