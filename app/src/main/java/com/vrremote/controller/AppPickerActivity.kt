package com.vrremote.controller

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vrremote.controller.remote.ProfileTemplate

/**
 * Lists every app that has a launcher icon so one of them can be given its own
 * controller bindings.
 */
class AppPickerActivity : Activity() {

    private data class AppEntry(val packageName: String, val label: String, val icon: android.graphics.drawable.Drawable)

    private lateinit var appList: LinearLayout
    private lateinit var search: EditText
    private var apps: List<AppEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)
        RemoteCore.init(this)

        appList = findViewById(R.id.appList)
        search = findViewById(R.id.searchInput)
        apps = loadLauncherApps()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })
        render("")
    }

    private fun loadLauncherApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = if (android.os.Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
        return resolved
            .map {
                AppEntry(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(packageManager).toString(),
                    icon = it.loadIcon(packageManager),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun render(filter: String) {
        val added = RemoteCore.profiles.all().map { it.packageName }.toSet()
        appList.removeAllViews()
        val shown = apps.filter { filter.isEmpty() || it.label.contains(filter, ignoreCase = true) }
        for (app in shown) appList.addView(row(app, added.contains(app.packageName)))
    }

    private fun row(app: AppEntry, alreadyAdded: Boolean): LinearLayout {
        val container = LinearLayout(this).apply {
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
        container.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(96, 96)
            setImageDrawable(app.icon)
        })
        container.addView(TextView(this).apply {
            text = if (alreadyAdded) "${app.label}  ✓" else app.label
            textSize = 16f
            setTextColor(0xFF1B1D21.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = 24 }
        })
        container.setOnClickListener { chooseTemplate(app) }
        return container
    }

    private fun chooseTemplate(app: AppEntry) {
        val labels = ProfileTemplate.entries.map { "${it.label} — ${it.description}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("给「${app.label}」套用哪个模板？")
            .setItems(labels) { _, index ->
                RemoteCore.profiles.add(app.packageName, app.label, ProfileTemplate.entries[index])
                startActivity(
                    Intent(this, AppProfileActivity::class.java)
                        .putExtra(AppProfileActivity.EXTRA_PACKAGE, app.packageName)
                )
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
