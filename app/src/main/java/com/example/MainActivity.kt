package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.audio.HarmonyAudioEngine
import com.example.auth.AuthScreen
import com.example.auth.authStateFlow
import com.example.auth.signOut
import com.example.data.LocalFallbackPresetRepository
import com.example.data.RehearsalPresetRepository
import com.example.midi.MidiInputController
import com.example.ui.ConsoleViewModel
import com.example.ui.HarmonyDirectorScreen
import com.example.ui.theme.HarmonyDirectorTheme
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HarmonyDirectorTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val isFirebaseConfigured = remember(context) {
        FirebaseApp.getApps(context).isNotEmpty()
    }

    if (!isFirebaseConfigured) {
        // Standalone APK build without google-services.json: run full Harmony/Metronome/MIDI console offline
        val viewModel: ConsoleViewModel = viewModel(
            key = "local_conductor",
            factory = viewModelFactory {
                initializer {
                    val app = checkNotNull(this[APPLICATION_KEY]) {
                        "APPLICATION_KEY missing from CreationExtras"
                    }
                    ConsoleViewModel(
                        repository = LocalFallbackPresetRepository(app.applicationContext),
                        audioEngine = HarmonyAudioEngine(),
                        currentUserId = "local_conductor",
                        midiController = MidiInputController(app.applicationContext)
                    )
                }
            }
        )
        HarmonyDirectorScreen(
            viewModel = viewModel,
            onSignOut = { viewModel.stopAllSound() }
        )
        return
    }

    val auth = remember { Firebase.auth }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        val uid = user.uid
        val scope = rememberCoroutineScope()
        val credentialManager = remember(context) { CredentialManager.create(context) }

        key(uid) {
            val viewModel: ConsoleViewModel = viewModel(
                key = uid,
                factory = viewModelFactory {
                    initializer {
                        val app = checkNotNull(this[APPLICATION_KEY]) {
                            "APPLICATION_KEY missing from CreationExtras"
                        }
                        val db = RehearsalPresetRepository.resolveFirestoreInstance(app.applicationContext)
                        ConsoleViewModel(
                            repository = RehearsalPresetRepository(db = db, auth = auth),
                            audioEngine = HarmonyAudioEngine(),
                            currentUserId = uid,
                            midiController = MidiInputController(app.applicationContext)
                        )
                    }
                }
            )

            HarmonyDirectorScreen(
                viewModel = viewModel,
                onSignOut = {
                    viewModel.stopAllSound()
                    signOut(
                        credentialManager = credentialManager,
                        scope = scope
                    )
                }
            )
        }
    }
}
