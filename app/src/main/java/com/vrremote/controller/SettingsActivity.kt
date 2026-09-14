package com.vrremote.controller

import android.app.Activity
import android.os.Bundle
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.vrremote.controller.remote.RemoteMode
import kotlin.math.roundToInt

/**
 * Tunables for the mapping engine.
 *
 * Each slider works in its own thumb units; the label and the stored value
 * convert from those, so what the user reads is exactly what the engine uses.
 */
class SettingsActivity : Activity() {

    private lateinit var preferences: Preferences
    private var restoring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        preferences = Preferences(this)

        bindDefaultMode()

        bindSlider(
            label = findViewById(R.id.dpadStepLabel),
            seek = findViewById(R.id.dpadStepSeek),
            seekValue = preferences.dpadStep,
            text = { "方向键步长 $it（越小越灵敏）" },
            store = { preferences.dpadStep = it },
        )
        bindSlider(
            label = findViewById(R.id.touchGainLabel),
            seek = findViewById(R.id.touchGainSeek),
            seekValue = preferences.flyTouchGain.roundToInt(),
            text = { "指尖鼠标速度 $it" },
            store = { preferences.flyTouchGain = it.toFloat() },
        )
        bindSlider(
            label = findViewById(R.id.motionGainLabel),
            seek = findViewById(R.id.motionGainSeek),
            seekValue = (preferences.flyMotionGain / 100f).roundToInt(),
            text = { "体感鼠标速度 ${it * 100}" },
            store = { preferences.flyMotionGain = (it * 100).toFloat() },
        )
        bindSlider(
            label = findViewById(R.id.motionDeadZoneLabel),
            seek = findViewById(R.id.motionDeadZoneSeek),
            seekValue = (preferences.flyMotionDeadZone * 1000f).roundToInt(),
            text = { "体感鼠标死区 ${"%.3f".format(it / 1000f)}" },
            store = { preferences.flyMotionDeadZone = it / 1000f },
        )

        val hintSwitch = findViewById<Switch>(R.id.hintSwitch)
        hintSwitch.isChecked = preferences.modeHints
        hintSwitch.setOnCheckedChangeListener { _, checked ->
            if (!restoring) preferences.modeHints = checked
        }
    }

    private fun bindDefaultMode() {
        val group = findViewById<RadioGroup>(R.id.defaultModeGroup)
        restoring = true
        group.check(
            when (preferences.mode) {
                RemoteMode.NORMAL -> R.id.defaultNormal
                RemoteMode.FLY_TOUCH -> R.id.defaultFlyTouch
                RemoteMode.FLY_MOTION -> R.id.defaultFlyMotion
            }
        )
        restoring = false
        group.setOnCheckedChangeListener { _, checkedId ->
            if (restoring) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.defaultFlyTouch -> RemoteMode.FLY_TOUCH
                R.id.defaultFlyMotion -> RemoteMode.FLY_MOTION
                else -> RemoteMode.NORMAL
            }
            preferences.mode = mode
            if (RemoteCore.isInitialised) RemoteCore.setMode(mode)
        }
    }

    private fun bindSlider(
        label: TextView,
        seek: SeekBar,
        seekValue: Int,
        text: (Int) -> String,
        store: (Int) -> Unit,
    ) {
        val position = seekValue.coerceIn(0, seek.max)
        seek.progress = position
        label.text = text(position)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress.coerceAtLeast(1)
                label.text = text(value)
                if (fromUser) store(value)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    override fun onResume() {
        super.onResume()
        RemoteCore.onActivityResumed(this)
    }

    override fun onPause() {
        RemoteCore.onActivityPaused()
        super.onPause()
    }
}
