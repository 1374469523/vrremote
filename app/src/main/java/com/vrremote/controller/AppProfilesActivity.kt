package com.vrremote.controller

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.vrremote.controller.remote.AppProfile

/**
 * The list of apps that have their own controller bindings.
 */
class AppProfilesActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_profiles)
        RemoteCore.init(this)

        list = findViewById(R.id.profileList)
        emptyText = findViewById(R.id.emptyText)
        findViewById<Button>(R.id.addAppButton).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        RemoteCore.init(this)
        render()
    }

    private fun render() {
        val profiles = RemoteCore.profiles.all()
        list.removeAllViews()
        emptyText.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        for (profile in profiles) list.addView(row(profile))

        // The mode reads the profile store on demand, so nothing else to refresh.
        RemoteCore.reloadSettings()
    }

    private fun row(profile: AppProfile): View {
        val context = this
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 28f
                setColor(Color.WHITE)
            }
            setPadding(28, 24, 28, 24)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 12 }
        }

        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(96, 96)
            setImageDrawable(iconOf(profile.packageName))
        }
        container.addView(icon)

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = 24 }
        }
        texts.addView(TextView(context).apply {
            text = profile.label
            textSize = 16f
            setTextColor(0xFF1B1D21.toInt())
        })
        texts.addView(TextView(context).apply {
            text = "${profile.packageName}\n已配置 ${profile.bindings.size} 个按键"
            textSize = 12f
            setTextColor(0xFF6B7280.toInt())
        })
        container.addView(texts)

        container.addView(Switch(context).apply {
            isChecked = profile.enabled
            setOnCheckedChangeListener { _, checked ->
                RemoteCore.profiles.setEnabled(profile.packageName, checked)
            }
        })

        container.addView(Button(context).apply {
            text = "删除"
            setOnClickListener {
                RemoteCore.profiles.remove(profile.packageName)
                render()
            }
        })

        container.setOnClickListener {
            startActivity(
                Intent(this, AppProfileActivity::class.java)
                    .putExtra(AppProfileActivity.EXTRA_PACKAGE, profile.packageName)
            )
        }
        return container
    }

    private fun iconOf(packageName: String) = try {
        packageManager.getApplicationIcon(packageName)
    } catch (ignored: Exception) {
        packageManager.defaultActivityIcon
    }
}
