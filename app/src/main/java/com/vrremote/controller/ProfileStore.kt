package com.vrremote.controller

import android.content.Context
import com.vrremote.controller.remote.AppProfile
import com.vrremote.controller.remote.ProfileAction
import com.vrremote.controller.remote.ProfileCodec
import com.vrremote.controller.remote.ProfileTemplate
import com.vrremote.controller.remote.ProfileTrigger

/**
 * The per-app profiles.
 *
 * They live as one JSON string inside the same preferences file as the rest of
 * the settings, so a profile can be read without touching the filesystem on
 * every controller frame.
 */
class ProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private var cache: List<AppProfile> = load()

    fun all(): List<AppProfile> = cache

    fun find(packageName: String): AppProfile? =
        cache.firstOrNull { it.packageName == packageName }

    /** Adds an app, or replaces the bindings when it is already known. */
    fun add(packageName: String, label: String, template: ProfileTemplate) {
        val existing = find(packageName)
        upsert(
            AppProfile(
                packageName = packageName,
                label = label,
                enabled = existing?.enabled ?: true,
                bindings = template.bindings(),
            )
        )
    }

    fun upsert(profile: AppProfile) {
        val next = cache.filterNot { it.packageName == profile.packageName } + profile
        cache = next.sortedBy { it.label.lowercase() }
        save()
    }

    fun remove(packageName: String) {
        cache = cache.filterNot { it.packageName == packageName }
        save()
    }

    fun setEnabled(packageName: String, enabled: Boolean) {
        find(packageName)?.let { upsert(it.copy(enabled = enabled)) }
    }

    fun applyTemplate(packageName: String, template: ProfileTemplate) {
        find(packageName)?.let { upsert(template.applyTo(it)) }
    }

    fun setBinding(packageName: String, trigger: ProfileTrigger, action: ProfileAction) {
        find(packageName)?.let { upsert(it.withBinding(trigger, action)) }
    }

    private fun save() {
        prefs.edit().putString(KEY_PROFILES, ProfileCodec.encode(cache)).apply()
    }

    private fun load(): List<AppProfile> =
        ProfileCodec.decode(prefs.getString(KEY_PROFILES, null))

    private companion object {
        const val KEY_PROFILES = "app_profiles"
    }
}
