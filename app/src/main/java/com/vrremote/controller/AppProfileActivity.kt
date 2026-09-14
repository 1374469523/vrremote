package com.vrremote.controller

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vrremote.controller.remote.ProfileAction
import com.vrremote.controller.remote.ProfileTemplate
import com.vrremote.controller.remote.ProfileTrigger

/**
 * Editor for one app: seven triggers, each bound to one action.
 */
class AppProfileActivity : Activity() {

    companion object {
        const val EXTRA_PACKAGE = "package"
    }

    private lateinit var bindingList: LinearLayout
    private lateinit var appName: TextView
    private lateinit var appPackage: TextView
    private lateinit var appIcon: ImageView
    private lateinit var packageName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_profile)
        RemoteCore.init(this)

        packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        bindingList = findViewById(R.id.bindingList)
        appName = findViewById(R.id.appName)
        appPackage = findViewById(R.id.appPackage)
        appIcon = findViewById(R.id.appIcon)

        findViewById<Button>(R.id.applyNovelButton).setOnClickListener { apply(ProfileTemplate.NOVEL) }
        findViewById<Button>(R.id.applyVideoButton).setOnClickListener { apply(ProfileTemplate.SHORT_VIDEO) }
        findViewById<Button>(R.id.applyCameraButton).setOnClickListener { apply(ProfileTemplate.CAMERA) }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun apply(template: ProfileTemplate) {
        RemoteCore.profiles.applyTemplate(packageName, template)
        render()
    }

    private fun render() {
        val profile = RemoteCore.profiles.find(packageName) ?: run {
            finish()
            return
        }
        appName.text = profile.label
        appPackage.text = profile.packageName
        appIcon.setImageDrawable(
            try {
                packageManager.getApplicationIcon(profile.packageName)
            } catch (ignored: Exception) {
                packageManager.defaultActivityIcon
            }
        )

        bindingList.removeAllViews()
        for (trigger in ProfileTrigger.entries) {
            bindingList.addView(row(trigger, profile.actionFor(trigger)))
        }
    }

    private fun row(trigger: ProfileTrigger, current: ProfileAction): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 28f
                setColor(Color.WHITE)
            }
            setPadding(28, 26, 28, 26)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 12 }
        }
        container.addView(TextView(this).apply {
            text = trigger.label
            textSize = 16f
            setTextColor(0xFF1B1D21.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        container.addView(TextView(this).apply {
            text = current.label
            textSize = 15f
            setTextColor(if (current == ProfileAction.NONE) 0xFF9AA0A6.toInt() else 0xFF1A73E8.toInt())
        })
        container.setOnClickListener { chooseAction(trigger, current) }
        return container
    }

    private fun chooseAction(trigger: ProfileTrigger, current: ProfileAction) {
        val actions = ProfileAction.entries
        val labels = actions.map { it.label }.toTypedArray()
        val checked = actions.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("${trigger.label} 触发的功能")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                RemoteCore.profiles.setBinding(packageName, trigger, actions[which])
                dialog.dismiss()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
