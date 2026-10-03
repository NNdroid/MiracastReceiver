package com.weekd.miracastreceiver.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.weekd.miracastreceiver.util.Logger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AudioPlayer — Decrypts and plays the AirPlay audio stream.
 *
 * WHY: AirPlay audio arrives as encrypted RTP packets over UDP.
 * Before the audio can be played, it must be:
 * 1. Received from the UDP socket
 * 2. Decrypted (AES-128-CBC cipher, IV reset per packet)
 * 3. Decoded (AAC-ELD or ALAC frames → PCM audio)
 * 4. Played through the TV's audio output (AudioTrack)
 *
 * This class handles steps 2-4. Step 1 (UDP receiving) is handled by RtspHandler
 * which calls [playAudioPacket] for each received packet.
 */
class AudioPlayer {

    private var audioTrack: AudioTrack? = null
    private val cbcCipher = Cipher.getInstance("AES/CBC/NoPadding")
    private var aesKeySpec: SecretKeySpec? = null
    private var aesIvSpec: IvParameterSpec? = null

    @Volatile
    private var isInitialized = false

    private var alac: com.weekd.miracastreceiver.airplay.handshake.AlacDecoder? = null
    private var decodeAttempts = 0
    private var decodeSuccesses = 0
    private var decodeHealthDecided = false
    @Volatile private var muted = false

    fun initialize(
        aesKey: ByteArray?, aesIv: ByteArray?, sampleRate: Int, channels: Int,
        codec: AudioCodec = AudioCodec.UNKNOWN, alacFramesPerPacket: Int = 352,
    ) {
        if (isInitialized) {
            Logger.w("AudioPlayer.initialize() called twice — ignoring")
            return
        }

        if (codec == AudioCodec.ALAC) {
            alac = runCatching {
                com.weekd.miracastreceiver.airplay.handshake.AlacDecoder(sampleRate, channels, alacFramesPerPacket)
            }.onFailure { Logger.e("AudioPlayer: ALAC decoder init failed", it) }.getOrNull()
        }

        if (aesKey != null || aesIv != null) {
            require(aesKey != null && aesKey.size == AES_KEY_LENGTH_BYTES) {
                "AES key must be exactly $AES_KEY_LENGTH_BYTES bytes, got ${aesKey?.size}"
            }
            require(aesIv != null && aesIv.size == AES_KEY_LENGTH_BYTES) {
                "AES IV must be exactly $AES_KEY_LENGTH_BYTES bytes, got ${aesIv?.size}"
            }
            initializeCipher(aesKey, aesIv)
            Logger.i("Initializing AudioPlayer (encrypted): ${sampleRate}Hz, $channels channels")
        } else {
            Logger.i("Initializing AudioPlayer (unencrypted): ${sampleRate}Hz, $channels channels")
        }

        initializeAudioTrack(sampleRate, channels)
        isInitialized = true
    }

    fun playAudioPacket(rtpPacket: ByteArray) {
        if (!isInitialized) {
            Logger.w("playAudioPacket() called but AudioPlayer not initialized")
            return
        }

        try {
            if (rtpPacket.size <= RTP_HEADER_MIN_BYTES) {
                Logger.w("RTP packet too small (${rtpPacket.size} bytes), skipping")
                return
            }
            if ((rtpPacket[1].toInt() and 0x7F) != AUDIO_PAYLOAD_TYPE) return
            val encryptedPayload = rtpPacket.copyOfRange(RTP_HEADER_MIN_BYTES, rtpPacket.size)
            val decryptedPayload = decrypt(encryptedPayload)

            val pcm = alac?.let { dec ->
                val out = dec.decode(decryptedPayload)
                if (!decodeHealthDecided) updateDecodeHealth(out != null)
                if (muted) return
                out ?: return
            } ?: decryptedPayload

            writePcm(pcm)
        } catch (e: Exception) {
            Logger.e("Error playing audio packet", e)
        }
    }

    fun release() {
        Logger.d("Releasing AudioPlayer")
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Logger.e("Error releasing AudioTrack (non-fatal)", e)
        } finally {
            runCatching { alac?.close() }
            alac = null
            audioTrack = null
            aesKeySpec = null
            aesIvSpec = null
            isInitialized = false
            decodeAttempts = 0
            decodeSuccesses = 0
            decodeHealthDecided = false
            muted = false
        }
    }

    private fun initializeCipher(key: ByteArray, iv: ByteArray) {
        aesKeySpec = SecretKeySpec(key, "AES")
        aesIvSpec = IvParameterSpec(iv)
        Logger.d("AES-128-CBC cipher initialized")
    }

    /** API 23 is the app baseline, so the modern non-blocking write overload is always available. */
    private fun writePcm(pcm: ByteArray) {
        audioTrack?.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
    }

    private fun initializeAudioTrack(sampleRate: Int, channels: Int) {
        val channelConfig = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> {
                Logger.w("Unsupported channel count: $channels — defaulting to stereo")
                AudioFormat.CHANNEL_OUT_STEREO
            }
        }

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBufferSize * 2

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack!!.play()
        Logger.d("AudioTrack initialized: ${sampleRate}Hz, $channels ch, buffer=$bufferSize bytes")
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val key = aesKeySpec ?: return data
        val iv = aesIvSpec ?: return data
        val encryptedLen = (data.size / 16) * 16
        if (encryptedLen == 0) return data
        cbcCipher.init(Cipher.DECRYPT_MODE, key, iv)
        val out = data.copyOf()
        cbcCipher.doFinal(data, 0, encryptedLen, out, 0)
        return out
    }

    private fun updateDecodeHealth(success: Boolean) {
        decodeAttempts++
        if (success) decodeSuccesses++
        if (decodeAttempts < DECODE_HEALTH_SAMPLE) return
        decodeHealthDecided = true
        val rate = decodeSuccesses.toDouble() / decodeAttempts
        if (rate < DECODE_HEALTH_MIN_RATE) {
            muted = true
            Logger.w(
                "Audio muted: ALAC decoded only $decodeSuccesses/$decodeAttempts frames — " +
                    "stream key looks wrong (likely an unsupported FairPlay v2 mode)"
            )
        } else {
            Logger.i("Audio decode healthy ($decodeSuccesses/$decodeAttempts frames)")
        }
    }

    companion object {
        private const val AES_KEY_LENGTH_BYTES = 16
        private const val RTP_HEADER_MIN_BYTES = 12
        private const val AUDIO_PAYLOAD_TYPE = 96
        private const val DECODE_HEALTH_SAMPLE = 24
        private const val DECODE_HEALTH_MIN_RATE = 0.8
    }
}
