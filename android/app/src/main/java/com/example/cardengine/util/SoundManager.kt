package com.example.cardengine.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.example.cardengine.R

object SoundManager {
    private var soundPool: SoundPool? = null
    private var soundClick: Int = 0
    private var soundScan: Int = 0
    private var soundClaim: Int = 0
    private var isLoaded: Boolean = false

    fun init(context: Context) {
        if (soundPool != null) return

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(audioAttributes)
            .build().apply {
                soundClick = load(context, R.raw.sfx_click, 1)
                soundScan = load(context, R.raw.sfx_scan, 1)
                soundClaim = load(context, R.raw.sfx_claim, 1)
                setOnLoadCompleteListener { _, _, _ -> isLoaded = true }
            }
    }

    fun playClick() = playSound(soundClick)
    fun playScan() = playSound(soundScan)
    fun playClaim() = playSound(soundClaim)

    private fun playSound(soundId: Int) {
        if (isLoaded && soundPool != null) {
            soundPool?.play(soundId, 1f, 1f, 0, 0, 1f)
        }
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        isLoaded = false
    }
}