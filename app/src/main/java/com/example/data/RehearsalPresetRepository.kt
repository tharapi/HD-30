package com.example.data

import android.content.Context
import com.example.R
import com.example.model.RehearsalPreset
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class RehearsalPresetRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun presetsCollection(userId: String) =
        db.collection("users").document(userId).collection("rehearsal_presets")

    fun observePresets(userId: String): Flow<List<RehearsalPreset>> =
        presetsCollection(userId)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    RehearsalPreset::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }

    suspend fun createPreset(userId: String, preset: RehearsalPreset): Result<Unit> {
        val trimmedTitle = preset.title.trim()
        val trimmedNotes = preset.notes.trim()
        val docPath = "users/$userId/rehearsal_presets"
        return try {
            val uid = auth.currentUser?.uid
                ?: error("User must be signed in with Google before saving presets.")
            check(uid == userId) { "Signed-in user changed; refusing cross-user write." }
            require(trimmedTitle.isNotEmpty() && trimmedTitle.length <= 80) {
                "Preset title must be between 1 and 80 characters."
            }
            require(trimmedNotes.length <= 300) {
                "Notes must be 300 characters or fewer."
            }

            val docRef = presetsCollection(userId).document()
            val payload = mapOf(
                "userId" to uid,
                "title" to trimmedTitle,
                "notes" to trimmedNotes,
                "bpm" to preset.bpm.coerceIn(32L, 280L),
                "timeSignature" to preset.safeTimeSignature().name,
                "metronomeSound" to preset.safeMetronomeSound().name,
                "accentLevel" to preset.accentLevel.coerceIn(0.0, 1.0),
                "quarterLevel" to preset.quarterLevel.coerceIn(0.0, 1.0),
                "eighthLevel" to preset.eighthLevel.coerceIn(0.0, 1.0),
                "tripletLevel" to preset.tripletLevel.coerceIn(0.0, 1.0),
                "sixteenthLevel" to preset.sixteenthLevel.coerceIn(0.0, 1.0),
                "alternateMuteBar" to preset.alternateMuteBar,
                "referencePitchHz" to preset.referencePitchHz.coerceIn(430.0, 450.0),
                "temperament" to preset.safeTemperament().name,
                "rootKeyIndex" to preset.rootKeyIndex.coerceIn(0L, 11L),
                "autoDetectRoot" to preset.autoDetectRoot,
                "transposition" to preset.safeTransposition().name,
                "voice" to preset.safeVoice().name,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updatePreset(userId: String, preset: RehearsalPreset): Result<Unit> {
        val trimmedTitle = preset.title.trim()
        val trimmedNotes = preset.notes.trim()
        val presetId = preset.id
        val docPath = "users/$userId/rehearsal_presets/$presetId"
        return try {
            val uid = auth.currentUser?.uid
                ?: error("User must be signed in with Google before updating presets.")
            check(uid == userId) { "Signed-in user changed; refusing cross-user write." }
            require(presetId.isNotBlank()) { "Preset ID must not be blank for update." }
            require(trimmedTitle.isNotEmpty() && trimmedTitle.length <= 80) {
                "Preset title must be between 1 and 80 characters."
            }
            require(trimmedNotes.length <= 300) {
                "Notes must be 300 characters or fewer."
            }

            presetsCollection(userId).document(presetId).update(
                mapOf(
                    "title" to trimmedTitle,
                    "notes" to trimmedNotes,
                    "bpm" to preset.bpm.coerceIn(32L, 280L),
                    "timeSignature" to preset.safeTimeSignature().name,
                    "metronomeSound" to preset.safeMetronomeSound().name,
                    "accentLevel" to preset.accentLevel.coerceIn(0.0, 1.0),
                    "quarterLevel" to preset.quarterLevel.coerceIn(0.0, 1.0),
                    "eighthLevel" to preset.eighthLevel.coerceIn(0.0, 1.0),
                    "tripletLevel" to preset.tripletLevel.coerceIn(0.0, 1.0),
                    "sixteenthLevel" to preset.sixteenthLevel.coerceIn(0.0, 1.0),
                    "alternateMuteBar" to preset.alternateMuteBar,
                    "referencePitchHz" to preset.referencePitchHz.coerceIn(430.0, 450.0),
                    "temperament" to preset.safeTemperament().name,
                    "rootKeyIndex" to preset.rootKeyIndex.coerceIn(0L, 11L),
                    "autoDetectRoot" to preset.autoDetectRoot,
                    "transposition" to preset.safeTransposition().name,
                    "voice" to preset.safeVoice().name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deletePreset(userId: String, presetId: String): Result<Unit> {
        val docPath = "users/$userId/rehearsal_presets/$presetId"
        return try {
            val uid = auth.currentUser?.uid
                ?: error("User must be signed in with Google before deleting presets.")
            check(uid == userId) { "Signed-in user changed; refusing cross-user delete." }
            require(presetId.isNotBlank()) { "Preset ID must not be blank for delete." }
            presetsCollection(userId).document(presetId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }
}
