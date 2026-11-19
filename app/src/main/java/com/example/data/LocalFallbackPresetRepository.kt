package com.example.data

import android.content.Context
import com.example.model.RehearsalPreset
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.UUID

/**
 * Fallback on-device preset storage used ONLY when the APK is built standalone
 * without Firebase configuration (e.g., GitHub Actions without google-services.json).
 * When Firebase is configured, [RehearsalPresetRepository] (Cloud Firestore) is always used.
 */
class LocalFallbackPresetRepository(context: Context) : PresetRepository {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val _presets = MutableStateFlow(loadFromPrefs())

    override fun observePresets(userId: String): Flow<List<RehearsalPreset>> = _presets.asStateFlow()

    override suspend fun createPreset(userId: String, preset: RehearsalPreset): Result<Unit> {
        val trimmedTitle = preset.title.trim()
        val trimmedNotes = preset.notes.trim()
        if (trimmedTitle.isEmpty() || trimmedTitle.length > 80) {
            return Result.failure(IllegalArgumentException("Preset title must be 1–80 characters."))
        }
        val now = Timestamp(Date())
        val item = preset.copy(
            id = UUID.randomUUID().toString(),
            userId = userId,
            title = trimmedTitle,
            notes = trimmedNotes.take(300),
            timeSignature = preset.safeTimeSignature().toStorageString(),
            createdAt = now,
            updatedAt = now
        )
        val updated = listOf(item) + _presets.value
        persist(updated)
        return Result.success(Unit)
    }

    override suspend fun updatePreset(userId: String, preset: RehearsalPreset): Result<Unit> {
        val trimmedTitle = preset.title.trim()
        val trimmedNotes = preset.notes.trim()
        if (trimmedTitle.isEmpty() || trimmedTitle.length > 80) {
            return Result.failure(IllegalArgumentException("Preset title must be 1–80 characters."))
        }
        val now = Timestamp(Date())
        val updated = _presets.value.map { existing ->
            if (existing.id == preset.id) {
                preset.copy(
                    title = trimmedTitle,
                    notes = trimmedNotes.take(300),
                    timeSignature = preset.safeTimeSignature().toStorageString(),
                    createdAt = existing.createdAt ?: now,
                    updatedAt = now
                )
            } else {
                existing
            }
        }.sortedByDescending { it.updatedAt?.seconds ?: 0L }
        persist(updated)
        return Result.success(Unit)
    }

    override suspend fun deletePreset(userId: String, presetId: String): Result<Unit> {
        val updated = _presets.value.filterNot { it.id == presetId }
        persist(updated)
        return Result.success(Unit)
    }

    private fun persist(list: List<RehearsalPreset>) {
        val array = JSONArray()
        list.forEach { p ->
            array.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("userId", p.userId)
                    put("title", p.title)
                    put("notes", p.notes)
                    put("bpm", p.bpm)
                    put("timeSignature", p.safeTimeSignature().toStorageString())
                    put("metronomeSound", p.metronomeSound)
                    put("accentLevel", p.accentLevel)
                    put("quarterLevel", p.quarterLevel)
                    put("eighthLevel", p.eighthLevel)
                    put("tripletLevel", p.tripletLevel)
                    put("sixteenthLevel", p.sixteenthLevel)
                    put("alternateMuteBar", p.alternateMuteBar)
                    put("referencePitchHz", p.referencePitchHz)
                    put("temperament", p.temperament)
                    put("rootKeyIndex", p.rootKeyIndex)
                    put("autoDetectRoot", p.autoDetectRoot)
                    put("transposition", p.transposition)
                    put("voice", p.voice)
                    put("createdAtSec", p.createdAt?.seconds ?: 0L)
                    put("updatedAtSec", p.updatedAt?.seconds ?: 0L)
                }
            )
        }
        prefs.edit().putString(KEY_PRESETS_JSON, array.toString()).apply()
        _presets.value = list
    }

    private fun loadFromPrefs(): List<RehearsalPreset> {
        val raw = prefs.getString(KEY_PRESETS_JSON, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val createdSec = o.optLong("createdAtSec", 0L)
                    val updatedSec = o.optLong("updatedAtSec", createdSec)
                    add(
                        RehearsalPreset(
                            id = o.optString("id"),
                            userId = o.optString("userId", "local_conductor"),
                            title = o.optString("title"),
                            notes = o.optString("notes"),
                            bpm = o.optLong("bpm", 120L),
                            timeSignature = o.optString("timeSignature", "4/4"),
                            metronomeSound = o.optString("metronomeSound", "WOODBLOCK"),
                            accentLevel = o.optDouble("accentLevel", 1.0),
                            quarterLevel = o.optDouble("quarterLevel", 0.85),
                            eighthLevel = o.optDouble("eighthLevel", 0.0),
                            tripletLevel = o.optDouble("tripletLevel", 0.0),
                            sixteenthLevel = o.optDouble("sixteenthLevel", 0.0),
                            alternateMuteBar = o.optBoolean("alternateMuteBar", false),
                            referencePitchHz = o.optDouble("referencePitchHz", 442.0),
                            temperament = o.optString("temperament", "PURE"),
                            rootKeyIndex = o.optLong("rootKeyIndex", 0L),
                            autoDetectRoot = o.optBoolean("autoDetectRoot", true),
                            transposition = o.optString("transposition", "CONCERT_C"),
                            voice = o.optString("voice", "ORGAN"),
                            createdAt = if (createdSec > 0L) Timestamp(createdSec, 0) else null,
                            updatedAt = if (updatedSec > 0L) Timestamp(updatedSec, 0) else null
                        )
                    )
                }
            }.sortedByDescending { it.updatedAt?.seconds ?: 0L }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val PREFS_NAME = "harmony_director_local_presets"
        private const val KEY_PRESETS_JSON = "presets_json"
    }
}
