//  SPDX-License-Identifier: MIT
//
//  PlayRetro
//  OnboardingActivity.kt
//
//  First-run setup: storage permission, application data directory, ROM directory and
//  optional creation of the system directories. Started by the C++ application, which waits
//  until NativeBridge.nativeSetHold(false) is called and then checks the configuration again.
//

package org.play_retro.frontend

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

class OnboardingActivity : ComponentActivity() {

    private enum class Step { WELCOME, PERMISSION, DATA_DIRECTORY, ROM_DIRECTORY, SYSTEM_DIRECTORIES, DONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enterImmersiveMode()
        val settings = AppSettings(this)
        setContent {
            MaterialTheme(colorScheme = PlayRetroColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Onboarding(settings)
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Besides recreation, the activity is destroyed while setup is still pending when the
        // launcher icon brings the singleTask MainActivity to the front, which clears it from
        // the task. Open setup again on top of MainActivity in that case.
        if (pending && isFinishing && !isChangingConfigurations) {
            startActivity(
                Intent(applicationContext, OnboardingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // Lets the C++ side continue. If setup wasn't completed it sees the missing
    // configuration and quits.
    private fun releaseApplication() {
        if (!pending) return
        pending = false
        runCatching { NativeBridge.nativeSetHold(false) }
    }

    @Composable
    private fun Onboarding(settings: AppSettings) {
        var step by rememberSaveable { mutableStateOf(Step.WELCOME) }
        var hasPermission by remember { mutableStateOf(MainActivity.hasStoragePermission(this)) }
        var dataDirectory by rememberSaveable {
            mutableStateOf(settings.appDataDirectory.ifEmpty { StoragePaths.defaultAppDataDirectory() })
        }
        var romDirectory by rememberSaveable {
            mutableStateOf(settings.romDirectory.ifEmpty { StoragePaths.defaultRomDirectory() })
        }
        var romTreeUri by rememberSaveable { mutableStateOf(settings.romDirectoryTreeUri) }
        var error by remember { mutableStateOf<String?>(null) }
        val invalidDirectory = stringResource(R.string.configurator_invalid_directory)

        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            hasPermission = MainActivity.hasStoragePermission(this@OnboardingActivity)
        }

        val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            hasPermission = it
        }
        val pickDataDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { StoragePaths.treeUriToPath(it) }?.let { dataDirectory = it; error = null }
                ?: run { if (uri != null) error = invalidDirectory }
        }
        val pickRomDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val path = uri?.let { StoragePaths.treeUriToPath(it) }
            if (uri != null && path != null) {
                // Keep access to the tree so %ROMSAF% launches can grant it to emulators.
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                romDirectory = path
                romTreeUri = uri.toString()
                error = null
            } else if (uri != null) {
                error = invalidDirectory
            }
        }

        BackHandler {
            if (step == Step.WELCOME) {
                // Abandoning setup quits the application.
                releaseApplication()
                finish()
            } else {
                error = null
                step = Step.entries[step.ordinal - 1]
            }
        }

        Box(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StepIndicator(step.ordinal, Step.entries.size)
                Spacer(Modifier.height(24.dp))
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
                    },
                    label = "onboarding",
                ) { current ->
                    when (current) {
                        Step.WELCOME -> StepCard(
                            title = stringResource(R.string.configurator_welcome_header),
                            text = stringResource(R.string.configurator_welcome_text),
                        ) {
                            PrimaryButton(stringResource(R.string.configurator_welcome_button)) {
                                step = Step.PERMISSION
                            }
                        }

                        Step.PERMISSION -> StepCard(
                            title = stringResource(R.string.configurator_storage_header),
                            text = stringResource(R.string.configurator_storage_text),
                            status = if (hasPermission) stringResource(R.string.configurator_permission_granted) else null,
                        ) {
                            if (hasPermission) {
                                PrimaryButton(stringResource(R.string.configurator_storage_button_already_granted)) {
                                    step = Step.DATA_DIRECTORY
                                }
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                PrimaryButton(stringResource(R.string.configurator_storage_button)) {
                                    startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:$packageName"),
                                        ),
                                    )
                                }
                            } else {
                                PrimaryButton(stringResource(R.string.configurator_storage_button_legacy)) {
                                    legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                }
                            }
                        }

                        Step.DATA_DIRECTORY -> StepCard(
                            title = stringResource(R.string.configurator_datadir_header),
                            text = stringResource(R.string.configurator_datadir_text) + " " +
                                StoragePaths.DEFAULT_APP_DATA_NAME,
                            path = dataDirectory,
                            error = error,
                        ) {
                            PrimaryButton(stringResource(android.R.string.ok)) {
                                if (StoragePaths.isWritableDirectory(dataDirectory)) {
                                    error = null
                                    step = Step.ROM_DIRECTORY
                                } else {
                                    error = invalidDirectory
                                }
                            }
                            SecondaryButton(stringResource(R.string.configurator_datadir_button)) {
                                pickDataDirectory.launch(null)
                            }
                        }

                        Step.ROM_DIRECTORY -> StepCard(
                            title = stringResource(R.string.configurator_romsdir_header),
                            text = stringResource(R.string.configurator_romsdir_text_1) + " " +
                                StoragePaths.DEFAULT_ROM_NAME + " " + stringResource(R.string.configurator_romsdir_text_2),
                            path = romDirectory,
                            error = error,
                        ) {
                            PrimaryButton(stringResource(android.R.string.ok)) {
                                if (StoragePaths.isWritableDirectory(romDirectory)) {
                                    error = null
                                    step = Step.SYSTEM_DIRECTORIES
                                } else {
                                    error = invalidDirectory
                                }
                            }
                            SecondaryButton(stringResource(R.string.configurator_romsdir_button)) {
                                pickRomDirectory.launch(null)
                            }
                        }

                        Step.SYSTEM_DIRECTORIES -> StepCard(
                            title = stringResource(R.string.configurator_dircreate_header),
                            text = stringResource(R.string.configurator_dircreate_text_1) + " " +
                                StoragePaths.DEFAULT_ROM_NAME + " " +
                                stringResource(R.string.configurator_dircreate_text_2) + " systeminfo.txt " +
                                stringResource(R.string.configurator_dircreate_text_3),
                        ) {
                            val save = { createDirectories: Boolean ->
                                settings.appDataDirectory = dataDirectory
                                settings.romDirectory = romDirectory
                                settings.romDirectoryTreeUri = romTreeUri
                                settings.createSystemDirectories = createDirectories
                                step = Step.DONE
                            }
                            PrimaryButton(stringResource(R.string.configurator_dircreate_button_1)) { save(true) }
                            SecondaryButton(stringResource(R.string.configurator_dircreate_button_2)) { save(false) }
                        }

                        Step.DONE -> StepCard(
                            title = stringResource(R.string.configurator_completed_header),
                            text = stringResource(R.string.configurator_completed_text),
                        ) {
                            PrimaryButton(stringResource(R.string.configurator_completed_button)) {
                                releaseApplication()
                                finish()
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StepIndicator(current: Int, count: Int) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(count) { index ->
                val color = if (index <= current) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                Box(
                    modifier = Modifier
                        .size(if (index == current) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }
    }

    @Composable
    private fun StepCard(
        title: String,
        text: String,
        path: String? = null,
        status: String? = null,
        error: String? = null,
        actions: @Composable () -> Unit,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                path?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Start) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
            }
        }
    }

    @Composable
    private fun PrimaryButton(text: String, onClick: () -> Unit) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }

    @Composable
    private fun SecondaryButton(text: String, onClick: () -> Unit) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        ) {
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }

    companion object {
        private val PlayRetroColors = darkColorScheme(
            primary = Color(0xFFFF4F6D),
            onPrimary = Color.White,
            secondary = Color(0xFF7A2CF5),
            background = Color(0xFF101014),
            surface = Color(0xFF1B1B22),
            surfaceVariant = Color(0xFF2A2A34),
            onSurface = Color(0xFFF2F2F5),
            onSurfaceVariant = Color(0xFFB8B8C4),
        )

        // Set while the C++ side waits for setup to finish.
        @Volatile
        var pending = false
    }
}
