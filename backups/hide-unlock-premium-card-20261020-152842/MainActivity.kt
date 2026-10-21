package com.align.tuition

import com.align.tuition.backup.DriveAccountBinding

import java.time.format.DateTimeFormatter
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable

import android.os.Bundle
import android.util.Log
import android.app.DatePickerDialog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.YearMonth
import java.time.LocalDate
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings

private val Green = Color(0xFF087F68)
private val Page = Color(0xFFF5F7F8)
private val Ink = Color(0xFF17232D)
private val Muted = Color(0xFF667781)


private fun currentFeePeriodKey(student: StudentEntity): String? =
    when (student.feeType) {
        "MONTHLY" -> YearMonth.now().toString()
        "YEARLY" -> "YEAR:" + LocalDate.now().year
        "DATE_RANGE" -> {
            val start = runCatching { LocalDate.parse(student.feeStartDate) }.getOrNull()
            val end = runCatching { LocalDate.parse(student.feeEndDate) }.getOrNull()
            if (start != null && end != null && !end.isBefore(start)) {
                "RANGE:${student.feeStartDate}:${student.feeEndDate}"
            } else null
        }
        else -> YearMonth.now().toString()
    }

private fun feeTypeLabel(student: StudentEntity): String =
    when (student.feeType) {
        "YEARLY" -> "Yearly fee"
        "DATE_RANGE" -> "Period fee"
        else -> "Monthly fee"
    }

private fun paymentPeriodLabel(key: String): String =
    when {
        key.startsWith("YEAR:") -> key.removePrefix("YEAR:")
        key.startsWith("RANGE:") ->
            key.removePrefix("RANGE:").replace(":", " → ")
        else -> runCatching {
            YearMonth.parse(key).format(
                java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
            )
        }.getOrDefault(key)
    }

private fun showFeeDatePicker(
    context: android.content.Context,
    currentValue: String,
    onSelected: (String) -> Unit
) {
    val initial = runCatching { LocalDate.parse(currentValue) }
        .getOrDefault(LocalDate.now())

    DatePickerDialog(
        context,
        { _, year, month, day ->
            onSelected(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
        },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth
    ).show()
}

class MainActivity : ComponentActivity() {
    private lateinit var driveCoordinator: com.align.tuition.backup.GoogleDriveSyncCoordinator
    private val DRIVE_AUTH_REQUEST = 7812
    private var pendingDriveAction = "backup"
    private val lifetimePremiumState = androidx.compose.runtime.mutableStateOf(false)
    private var lifetimePremiumBilling: LifetimePremiumBilling? = null

    private val initialDriveSetupState =
        androidx.compose.runtime.mutableStateOf("loading")
    private val initialDriveSetupMessage =
        androidx.compose.runtime.mutableStateOf("Connecting to Google Drive...")
    private var hadLocalDatabaseBeforeStartup = false

    private fun initialSetupPreferences() =
        getSharedPreferences("tuition_initial_drive_setup", MODE_PRIVATE)

    private fun isInitialDriveSetupComplete(): Boolean =
        initialSetupPreferences().getBoolean("initial_drive_setup_complete", false)

    private fun finishInitialDriveSetup(message: String) {
        initialSetupPreferences().edit()
            .putBoolean("initial_drive_setup_complete", true)
            .apply()
        initialDriveSetupMessage.value = message
        initialDriveSetupState.value = "complete"
    }

    private fun failInitialDriveSetup(message: String) {
        initialDriveSetupMessage.value = message
        initialDriveSetupState.value = "error"
        android.util.Log.e("TuitionInitialSetup", message)
    }

    private fun startInitialDriveAuthorization() {
        if (isInitialDriveSetupComplete()) {
            initialDriveSetupState.value = "complete"
            return
        }

        initialDriveSetupState.value = "loading"
        initialDriveSetupMessage.value = "Please connect your Google account..."
        pendingDriveAction = "initial_setup"

        try {
            com.align.tuition.backup.GoogleDriveAuthorization(this)
                .authorize()
                .addOnSuccessListener { result ->
                    if (result.hasResolution()) {
                        val sender = result.pendingIntent?.intentSender
                        if (sender == null) {
                            failInitialDriveSetup(
                                "Google authorization could not start. Tap Retry."
                            )
                        } else {
                            try {
                                startIntentSenderForResult(
                                    sender, DRIVE_AUTH_REQUEST, null, 0, 0, 0
                                )
                            } catch (error: Exception) {
                                failInitialDriveSetup(
                                    "Could not open Google authorization: " +
                                        (error.message ?: "Unknown error")
                                )
                            }
                        }
                    } else {
                        val token = result.accessToken
                        if (token.isNullOrBlank()) {
                            failInitialDriveSetup(
                                "Google returned no access token. Tap Retry."
                            )
                        } else {
                            handleInitialDriveSetup(token)
                        }
                    }
                }
                .addOnFailureListener { error ->
                    failInitialDriveSetup(
                        "Google Drive authorization failed: " +
                            (error.message ?: "Check your connection and retry.")
                    )
                }
        } catch (error: Exception) {
            failInitialDriveSetup(
                "Could not connect to Google Drive: " +
                    (error.message ?: "Unknown error")
            )
        }
    }

    private fun captureDriveAccountIdentity(accessToken: String) {
        lifetimePremiumBilling?.invalidatePremium()
        DriveAccountBinding.capture(applicationContext, accessToken) {
            runOnUiThread {
                lifetimePremiumBilling?.restorePurchases()
            }
        }
    }

    private fun handleInitialDriveSetup(accessToken: String) {
        captureDriveAccountIdentity(accessToken)
        initialDriveSetupState.value = "loading"
        initialDriveSetupMessage.value = "Checking for your Google Drive backup..."

        val restore = com.align.tuition.backup.GoogleDriveRestore(applicationContext)
        restore.findBackupMetadata(accessToken) { lookup ->
            lookup.onFailure { error ->
                failInitialDriveSetup(
                    "Could not check Google Drive. Your data was not reset. " +
                        (error.message ?: "Tap Retry.")
                )
            }.onSuccess { metadata ->
                if (metadata == null) {
                    // No remote backup: complete setup and let Room create a fresh database.
                    finishInitialDriveSetup(
                        "No backup found. Starting with a fresh database."
                    )
                } else {
                    // Drive is the source of truth: restore automatically without confirmation.
                    initialDriveSetupState.value = "loading"
                    initialDriveSetupMessage.value =
                        "Backup found. Downloading and validating it..."

                    restore.restore(accessToken, metadata.fileId) { download ->
                        download.onFailure { error ->
                            failInitialDriveSetup(
                                "Restore failed. The app will not start with fresh data. " +
                                    (error.message ?: "Tap Retry.")
                            )
                        }.onSuccess {
                            try {
                                TuitionDatabase.closeInstance()
                                val safetyCopy = restore.replaceValidatedDatabase()
                                finishInitialDriveSetup(
                                    "Restore completed. Safety copy: ${safetyCopy.name}"
                                )
                                window.decorView.postDelayed({ recreate() }, 500)
                            } catch (error: Exception) {
                                android.util.Log.e(
                                    "TuitionInitialSetup",
                                    "Initial database restore failed",
                                    error
                                )
                                failInitialDriveSetup(
                                    "Restore failed. Fresh start was not selected. " +
                                        "Check the local safety backup and retry. " +
                                        (error.message ?: "")
                                )
                            }
                        }
                    }
                }
            }
        }
    }


    @Deprecated("Deprecated in Android; retained for Drive authorization")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: android.content.Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != DRIVE_AUTH_REQUEST) return

        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            if (pendingDriveAction == "initial_setup") {
                failInitialDriveSetup(
                    "Google Drive connection is required. Tap Retry to connect."
                )
                pendingDriveAction = "backup"
            } else {
                android.widget.Toast.makeText(
                    this, "Google Drive authorization cancelled",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        try {
            val authResult =
                com.google.android.gms.auth.api.identity.Identity
                    .getAuthorizationClient(this)
                    .getAuthorizationResultFromIntent(data)
            val token = authResult.accessToken
            if (token.isNullOrBlank()) {
                throw IllegalStateException("Google returned no access token.")
            }

            when (pendingDriveAction) {
                "initial_setup" -> {
                    pendingDriveAction = "backup"
                    handleInitialDriveSetup(token)
                }
                "verify" -> {
                    pendingDriveAction = "backup"
                    verifyDriveBackup(token)
                }
                "restore" -> {
                    pendingDriveAction = "backup"
                    findAndConfirmRestore(token)
                }
                else -> {
                    captureDriveAccountIdentity(token)
                    driveCoordinator.continueManualSyncWithAccessToken(token)
                }
            }
        } catch (error: Exception) {
            val wasInitialSetup = pendingDriveAction == "initial_setup"
            pendingDriveAction = "backup"
            if (wasInitialSetup) {
                failInitialDriveSetup(
                    "Google authorization failed: " +
                        (error.message ?: error.javaClass.simpleName)
                )
            } else {
                android.widget.Toast.makeText(
                    this,
                    "Google authorization failed: ${error.message ?: error.javaClass.simpleName}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            android.util.Log.e("TuitionDriveAuth", "Authorization result failed", error)
        }
    }

    private fun startDriveVerification() {
        pendingDriveAction = "verify"
        try {
            com.align.tuition.backup.GoogleDriveAuthorization(this)
                .authorize()
                .addOnSuccessListener { result ->
                    if (result.hasResolution()) {
                        val sender = result.pendingIntent?.intentSender
                        if (sender == null) {
                            pendingDriveAction = "backup"
                            showDriveMessage("Google authorization needs to be retried.")
                        } else {
                            try {
                                startIntentSenderForResult(
                                    sender, DRIVE_AUTH_REQUEST, null, 0, 0, 0
                                )
                            } catch (error: Exception) {
                                pendingDriveAction = "backup"
                                showDriveMessage("Could not open Google authorization: ${error.message}")
                            }
                        }
                        return@addOnSuccessListener
                    }

                    val token = result.accessToken
                    if (token.isNullOrBlank()) {
                        pendingDriveAction = "backup"
                        showDriveMessage("Google returned no access token.")
                    } else {
                        pendingDriveAction = "backup"
                        verifyDriveBackup(token)
                    }
                }
                .addOnFailureListener { error ->
                    pendingDriveAction = "backup"
                    showDriveMessage("Authorization failed: ${error.message ?: "Unknown error"}")
                    android.util.Log.e("TuitionDriveAuth", "Verify authorization failed", error)
                }
        } catch (error: Exception) {
            pendingDriveAction = "backup"
            showDriveMessage("Could not start verification: ${error.message}")
        }
    }

    private fun startDriveRestore() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Restore from Google Drive?")
            .setMessage(
                "Restore will replace the tuition data currently on this device. " +
                "A local safety copy will be kept first. Continue?"
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                pendingDriveAction = "restore"
                try {
                    com.align.tuition.backup.GoogleDriveAuthorization(this)
                        .authorize()
                        .addOnSuccessListener { result ->
                            if (result.hasResolution()) {
                                val sender = result.pendingIntent?.intentSender
                                if (sender == null) {
                                    pendingDriveAction = "backup"
                                    showDriveMessage("Google authorization needs to be retried.")
                                } else {
                                    try {
                                        startIntentSenderForResult(
                                            sender, DRIVE_AUTH_REQUEST, null, 0, 0, 0
                                        )
                                    } catch (error: Exception) {
                                        pendingDriveAction = "backup"
                                        showDriveMessage("Could not open Google authorization: ${error.message}")
                                    }
                                }
                            } else {
                                val token = result.accessToken
                                pendingDriveAction = "backup"
                                if (token.isNullOrBlank()) {
                                    showDriveMessage("Google returned no access token.")
                                } else {
                                    findAndConfirmRestore(token)
                                }
                            }
                        }
                        .addOnFailureListener { error ->
                            pendingDriveAction = "backup"
                            showDriveMessage("Google authorization failed: ${error.message}")
                        }
                } catch (error: Exception) {
                    pendingDriveAction = "backup"
                    showDriveMessage("Could not start restore: ${error.message}")
                }
            }
            .show()
    }

    private fun findAndConfirmRestore(accessToken: String) {
        captureDriveAccountIdentity(accessToken)
        val restore = com.align.tuition.backup.GoogleDriveRestore(applicationContext)
        showDriveMessage("Finding Google Drive backup...")
        restore.findBackupMetadata(accessToken) { result ->
            result.onFailure { error ->
                showDriveMessage("Could not find backup: ${error.message}")
            }.onSuccess { metadata ->
                if (metadata == null) {
                    showDriveMessage("No Align-Tuition backup found in Google Drive.")
                } else {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Confirm Restore")
                        .setMessage(
                            "Backup modified: ${metadata.modifiedTime}\n\n" +
                            "Restoring replaces the data on this device. A local safety copy will be kept. " +
                            "Do not close the app during restore."
                        )
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Restore") { _, _ ->
                            showDriveMessage("Downloading and validating backup...")
                            restore.restore(accessToken, metadata.fileId) { downloadResult ->
                                downloadResult.onFailure { error ->
                                    showDriveMessage(
                                        "Restore cancelled: ${error.message ?: error.javaClass.simpleName}"
                                    )
                                }.onSuccess {
                                    try {
                                        TuitionDatabase.closeInstance()
                                        val safetyCopy = restore.replaceValidatedDatabase()
                                        showDriveMessage(
                                            "Restore completed. Local safety copy: ${safetyCopy.name}\nThe app will reload."
                                        )
                                        window.decorView.postDelayed({ recreate() }, 900)
                                    } catch (error: Exception) {
                                        android.util.Log.e(
                                            "TuitionDriveRestore", "Database replacement failed", error
                                        )
                                        showDriveMessage(
                                            "Restore failed: ${error.message ?: error.javaClass.simpleName}. " +
                                            "Check local safety backup before retrying."
                                        )
                                    }
                                }
                            }
                        }
                        .show()
                }
            }
        }
    }

    private fun verifyDriveBackup(accessToken: String) {
        captureDriveAccountIdentity(accessToken)
        showDriveMessage("Checking Google Drive backup...")
        com.align.tuition.backup.GoogleDriveRestore(applicationContext)
            .findBackupMetadata(accessToken) { result ->
                result
                    .onSuccess { metadata ->
                        if (metadata == null) {
                            showDriveMessage("No Align Tuition backup found in Google Drive app data.")
                        } else {
                            showDriveMessage(
                                "Backup found: Align Tuition Backup.db\nModified: ${metadata.modifiedTime}\nFile ID: ${metadata.fileId}"
                            )
                        }
                    }
                    .onFailure { error ->
                        showDriveMessage(
                            "Backup verification failed: ${error.message ?: error.javaClass.simpleName}"
                        )
                        android.util.Log.e("TuitionDriveAuth", "Drive backup verification failed", error)
                    }
            }
    }

    private fun showDriveMessage(message: String) {
        runOnUiThread {
            android.app.AlertDialog.Builder(this)
                .setTitle("Google Drive Backup")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Detect an existing local database before the coordinator can open/create it.
        hadLocalDatabaseBeforeStartup =
            getDatabasePath("align_tuition.db").exists()

        initialDriveSetupState.value =
            if (isInitialDriveSetupComplete()) "complete" else "loading"

        driveCoordinator = com.align.tuition.backup.GoogleDriveSyncCoordinator(
            context = applicationContext,
            authorization = com.align.tuition.backup.GoogleDriveAuthorization(this),
            backup = com.align.tuition.backup.GoogleDriveBackup(applicationContext),
            database = TuitionDatabase.get(applicationContext).openHelper.writableDatabase,
            onAuthorizationRequired = { intentSender ->
                startIntentSenderForResult(
                    intentSender,
                    DRIVE_AUTH_REQUEST,
                    null,
                    0,
                    0,
                    0
                )
            },
            onSyncStarted = {
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this, "Google Drive backup started",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onSyncSuccess = {
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this, "Google Drive backup completed",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            },
            onSyncFailure = { error ->
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this,
                        "Backup failed: ${error.message ?: "Unknown error"}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            },
            onRemoteBackupNewer = { fileId, modifiedTime, accessToken ->
                runOnUiThread {
                    handleRemoteBackupNewer(fileId, modifiedTime, accessToken)
                }
            },
            onRemoteCheckFinished = {
                // A successful background check must not change the visible screen.
            }
        )
        lifetimePremiumBilling = LifetimePremiumBilling(
            context = applicationContext,
            onPremiumChanged = { enabled ->
                runOnUiThread { lifetimePremiumState.value = enabled }
            },
            onMessage = { message ->
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this, message, android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        window.statusBarColor = android.graphics.Color.rgb(18, 61, 47)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Green,
                    secondary = Color(0xFFB7E4D8),
                    background = Page,
                    surface = Color.White
                )
            ) {
                if (initialDriveSetupState.value == "complete") {
                    TuitionApp(
                        onBackupClick = { driveCoordinator.requestManualSync() },
                        onVerifyBackupClick = { startDriveVerification() },
                        onRestoreBackupClick = { startDriveRestore() },
                        onStudentDataChanged = { driveCoordinator.requestAutoSync() },
                        isPremium = lifetimePremiumState.value,
                        onPurchasePremiumClick = {
                            lifetimePremiumBilling?.launchPurchase(this)
                                ?: android.widget.Toast.makeText(
                                    this,
                                    "Google Play Billing is still connecting.",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                        }
                    )
                } else {
                    InitialDriveSetupScreen(
                        state = initialDriveSetupState.value,
                        message = initialDriveSetupMessage.value,
                        onRetry = {
                            if (isInitialDriveSetupComplete()) {
                                initialDriveSetupState.value = "loading"
                                initialDriveSetupMessage.value = "Checking Google Drive backup..."
                                driveCoordinator.resume()
                            } else {
                                startInitialDriveAuthorization()
                            }
                        }
                    )
                }
            }
        }

        // Start initial authorization only after the setup screen is installed.
        window.decorView.post {
            if (!isInitialDriveSetupComplete()) {
                startInitialDriveAuthorization()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifetimePremiumBilling?.connect()
        // Background check only: never replace the dashboard with setup UI.
        if (::driveCoordinator.isInitialized && isInitialDriveSetupComplete()) {
            driveCoordinator.resume()
        }
    }

    override fun onDestroy() {
        lifetimePremiumBilling?.close()
        lifetimePremiumBilling = null
        super.onDestroy()
    }

    private fun handleRemoteBackupNewer(
        fileId: String,
        modifiedTime: String,
        accessToken: String
    ) {
        captureDriveAccountIdentity(accessToken)
        android.util.Log.i(
            "TuitionAutoRestore",
            "A newer Drive backup was found; restoring in the background."
        )

        val restore = com.align.tuition.backup.GoogleDriveRestore(applicationContext)
        restore.restore(accessToken, fileId) { result ->
            result.onFailure { error ->
                android.util.Log.e("TuitionAutoRestore", "Remote restore failed", error)
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this,
                        "Drive update could not be restored. Local data was kept.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }.onSuccess {
                try {
                    TuitionDatabase.closeInstance()
                    val safetyCopy = restore.replaceValidatedDatabase()
                    driveCoordinator.markRemoteBackupSynced(modifiedTime)
                    runOnUiThread {
                        android.widget.Toast.makeText(
                            this,
                            "Data updated from Google Drive.",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        window.decorView.postDelayed({ recreate() }, 500)
                    }
                } catch (error: Exception) {
                    android.util.Log.e("TuitionAutoRestore", "Database replacement failed", error)
                    runOnUiThread {
                        android.widget.Toast.makeText(
                            this,
                            "Drive restore failed. Check the local safety copy.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }
}

@Composable
private fun InitialDriveSetupScreen(
    state: String,
    message: String,
    onRetry: () -> Unit
) {
    androidx.activity.compose.BackHandler(enabled = true) {
        // The initial Google Drive setup cannot be bypassed with the Back button.
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Page)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CloudUpload,
            contentDescription = null,
            tint = Green,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Connect Google Drive",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Ink
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            fontSize = 15.sp,
            color = Muted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        if (state == "loading") {
            CircularProgressIndicator(color = Green)
        } else {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Green)
            ) {
                Text("Retry / Connect Google Drive")
            }
        }
    }
}

@Composable
private fun TuitionApp(
    onBackupClick: () -> Unit,
    onVerifyBackupClick: () -> Unit,
    onRestoreBackupClick: () -> Unit,
    onStudentDataChanged: () -> Unit,
    isPremium: Boolean,
    onPurchasePremiumClick: () -> Unit
) {
    val context = LocalContext.current
    SideEffect {
        appCurrencyCode = selectedTuitionCurrency(context).code
    }

    val dao = remember { TuitionDatabase.get(context).tuitionDao() }
    val scope = rememberCoroutineScope()
    val students by dao.observeStudents().collectAsState(initial = emptyList())
    val allStudents by dao.observeAllStudents().collectAsState(initial = emptyList())
    val payments by dao.observePayments().collectAsState(initial = emptyList())
    val pendingAmounts by dao.observeStudentPendingAmounts().collectAsState(initial = emptyList())
    val pendingByStudent = pendingAmounts.associate { it.studentId to it.pendingAmount }
    var paymentHistoryRefreshKey by remember { mutableStateOf(0) }
    val feeRecords by dao.observeFeeRecords().collectAsState(initial = emptyList())
    val profile by dao.observeProfile().collectAsState(initial = null)
    val currentMonth = remember { YearMonth.now().toString() }
    val monthPayments = remember(payments, currentMonth) {
    payments.filter { it.monthKey == currentMonth }
}
val collection = monthPayments.sumOf { it.amount }
    val pending = (feeRecords.sumOf { it.amount } - payments.sumOf { it.amount }).coerceAtLeast(0L)

    var tab by remember { mutableStateOf("Dashboard") }
    var legalPage by remember { mutableStateOf<String?>(null) }
        var pendingOnly by remember { mutableStateOf(false) }
var showStudentEditor by remember { mutableStateOf(false) }
    var studentToEdit by remember { mutableStateOf<StudentEntity?>(null) }
    var showPremiumDialog by remember { mutableStateOf(false) }
    var selectedStudent by remember { mutableStateOf<StudentEntity?>(null) }
    var recordFeeStudent by remember { mutableStateOf<StudentEntity?>(null) }
    var studentToCollect by remember { mutableStateOf<StudentEntity?>(null) }
    var paymentToEdit by remember { mutableStateOf<FeePaymentEntity?>(null) }
    var paymentToDelete by remember { mutableStateOf<FeePaymentEntity?>(null) }
    var studentToDelete by remember { mutableStateOf<StudentEntity?>(null) }
    var studentToRemoveFromCollection by remember { mutableStateOf<StudentEntity?>(null) }

BackHandler(
        enabled = legalPage != null ||
            recordFeeStudent != null ||
            selectedStudent != null ||
            tab == "Students" ||
            tab == "Profile" ||
            tab == "Collection"
    ) {
        when {
            legalPage != null -> legalPage = null
            recordFeeStudent != null -> recordFeeStudent = null
            selectedStudent != null -> {
                selectedStudent = null
                tab = "Students"
            }
            tab == "Students" -> {
                pendingOnly = false
                tab = "Dashboard"
            }
            tab == "Collection" -> tab = "Dashboard"
            tab == "Profile" -> tab = "Dashboard"
        }
    }


    Box(
        Modifier.fillMaxSize().background(
            if (recordFeeStudent != null || selectedStudent != null) {
                Color(0xFF123D2F)
            } else {
                Page
            }
        )
    ) {
        Scaffold(
            containerColor = if (recordFeeStudent != null || selectedStudent != null) {
                Color(0xFF123D2F)
            } else {
                Page
            },
            bottomBar = {
                if (selectedStudent == null && recordFeeStudent == null && tab != "Profile" && tab != "Collection") {
                    NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(
                        selected = tab == "Dashboard",
                        onClick = { tab = "Dashboard" },
                        icon = { Icon(Icons.Default.Dashboard, null) },
                        label = { Text("Home") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Green,
                            indicatorColor = Green
                        )
                    )
                    NavigationBarItem(
                        selected = tab == "Students",
                        onClick = {
                            if (tab == "Students") {
                                pendingOnly = false
                                studentToEdit = null
                                showStudentEditor = true
                            } else {
                                pendingOnly = false
                                tab = "Students"
                            }
                        },
                        icon = {
                            Icon(
                                if (tab == "Students") Icons.Default.PersonAdd
                                else Icons.Default.People,
                                contentDescription = null
                            )
                        },
                        label = {
                            Text(if (tab == "Students") "Add Student" else "Students")
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Green,
                            indicatorColor = Green
                        )
                    )
}
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().then(
                    if (tab == "Collection") Modifier
                    else if (tab == "Profile") Modifier
                    else if (tab == "Students") {
                        Modifier.padding(bottom = padding.calculateBottomPadding())
                    } else Modifier.padding(padding)
                )
            ) {
                Column(
                    modifier = if (tab == "Profile") {
                        Modifier.fillMaxSize().background(Page)
                    } else if (recordFeeStudent != null) {
                        Modifier.fillMaxSize().background(Page)
                    } else if (tab == "Collection") {
                        Modifier.fillMaxSize().background(Page)
                    } else if (selectedStudent != null || tab == "Students") {
                        Modifier.fillMaxSize().background(
                            if (selectedStudent == null && tab == "Students") {
                                Color(0xFF123D2F)
                            } else {
                                Page
                            }
                        )
                    } else {
                        Modifier.fillMaxWidth()
                            .background(Color.White)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    }
                ) {
                    if (legalPage != null) {
                        LegalDocumentScreen(
                            documentType = legalPage!!,
                            onBack = { legalPage = null }
                        )
                    } else if (recordFeeStudent != null) {
                    val feeStudent = recordFeeStudent!!
                    RecordFeeScreen(
                        student = feeStudent,
                        history = feeRecords.filter { it.studentId == feeStudent.id },
                        onBack = { recordFeeStudent = null },
                        onSave = { updatedStudent, record ->
                            dao.updateStudent(updatedStudent)
                            val savedRecord = if (record.id == 0L) {
                                record.copy(id = dao.insertFeeRecord(record))
                            } else {
                                dao.updateFeeRecord(record)
                                record
                            }
                            onStudentDataChanged()
                            selectedStudent = updatedStudent
                            savedRecord
                        },
                        onDelete = { record ->
                            dao.deleteFeeRecord(record)
                            onStudentDataChanged()
                        }
                    )
                } else if (selectedStudent != null) {
                    val detailStudent = selectedStudent!!
                    val detailPayments = payments
                        .filter { it.studentId == detailStudent.id }
                        .sortedByDescending { it.paidAt }
                    LaunchedEffect(detailStudent.id, detailPayments) {
                        Log.d("PAYMENT_UI", "Parent student=${detailStudent.id}, count=${detailPayments.size}, ids=${detailPayments.map { it.id }}")
                    }

                    key(detailStudent.id, detailPayments) {
                        StudentDetailsScreen(
                            modifier = Modifier.weight(1f),
                            student = detailStudent,
                            payments = detailPayments,
                        historyRefreshKey = paymentHistoryRefreshKey,
                        feeRecords = feeRecords.filter { it.studentId == detailStudent.id },
                        onEdit = {
                            studentToEdit = detailStudent
                            showStudentEditor = true
                        },
                        onRecordFee = { recordFeeStudent = detailStudent },
                        onCollect = { studentToCollect = detailStudent },
        onPaymentClick = { paymentToEdit = it },
                        onBack = {
                            selectedStudent = null
                            tab = "Students"
                        }
                        )
                    }
                } else when (tab) {
                    "Profile" -> ProfileScreen(
                        onBackupClick = onBackupClick,
                        onVerifyBackupClick = onVerifyBackupClick,
                        onRestoreBackupClick = onRestoreBackupClick,
                        onPrivacyPolicyClick = { legalPage = "Privacy Policy" },
                        onTermsClick = { legalPage = "Terms & Conditions" },
                        onUnlockPremiumClick = { showPremiumDialog = true },
                        isPremium = isPremium,
                        onStudentDataChanged = onStudentDataChanged,
                        modifier = Modifier.weight(1f),
                        profile = profile,
                        onBack = { tab = "Dashboard" },
                        onSave = { name, mobile ->
                            scope.launch {
                                dao.saveProfile(
                                    ProfileEntity(name = name, mobile = mobile)
                                )
                            }
                        }
                    )
                    "Students" -> key(paymentHistoryRefreshKey, payments, feeRecords) {
                        StudentsScreen(
                            modifier = Modifier.weight(1f),
                            students = students,
                            pendingOnly = pendingOnly,
                            pendingByStudent = pendingByStudent,
                            onAdd = {
                                studentToEdit = null
                                showStudentEditor = true
                            },
                            onEdit = {
                                studentToEdit = it
                                showStudentEditor = true
                            },
                            onStudentClick = { selectedStudent = it },
                            onCollect = { student ->
                                selectedStudent = student
                                studentToCollect = student
                            }
                        )
                    }
                    "Collection" -> CollectionScreen(
                        modifier = Modifier.weight(1f),
                        students = allStudents,
                        payments = payments,
                        monthKey = currentMonth,
                        onStudentClick = { student ->
                            if (student.isDeleted) studentToRemoveFromCollection = student
                        },
                        onBack = { tab = "Dashboard" }
                    )
                    "Fees" -> FeesScreen(
                        modifier = Modifier.weight(1f),
                        payments = payments,
                        students = students,
                        monthKey = currentMonth,
                        monthCollection = collection,
                        pending = pending,
                        onCollect = { studentToCollect = it }
                    )
                    else -> DashboardScreen(
                        modifier = Modifier.weight(1f),
                        studentCount = students.size,
                        collection = collection,
                        payments = payments,
                        pending = pending,
                        monthKey = currentMonth,
                        profileName = profile?.name.orEmpty(),
                        isPremium = isPremium,
                        onProfileClick = { tab = "Profile" }
                    ,
                        onPendingFeesClick = { pendingOnly = true; tab = "Students" },
                        onCollectionClick = { tab = "Collection" }
)
                }
            }
        }

        if (showPremiumDialog) {
            AlertDialog(
                onDismissRequest = { showPremiumDialog = false },
                containerColor = Color.White,
                tonalElevation = 0.dp,
                title = {
                    Text(
                        "Unlock Premium",
                        color = Color(0xFF123D2F),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        "The Free plan allows up to 2 active students. Premium lets you add unlimited students.",
                        color = Color(0xFF52635A),
                        fontSize = 14.sp,
                        lineHeight = 21.sp
                    )
                },
                confirmButton = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                showPremiumDialog = false
                                onPurchasePremiumClick()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Green),
                            colors = androidx.compose.material3.ButtonDefaults
                                .outlinedButtonColors(contentColor = Green)
                        ) {
                            Text(
                                "Premium",
                                fontSize = 11.sp,
                                maxLines = 2
                            )
                        }

                        androidx.compose.material3.OutlinedButton(
                            onClick = { showPremiumDialog = false },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Green),
                            colors = androidx.compose.material3.ButtonDefaults
                                .outlinedButtonColors(contentColor = Ink)
                        ) {
                            Text("Not now", fontSize = 12.sp, maxLines = 1)
                        }
                    }
                },
                dismissButton = {}
            )
        }

        if (showStudentEditor) {
            StudentEditorDialog(
                student = studentToEdit,
                onDismiss = { showStudentEditor = false },
                onDelete = {
                    studentToEdit?.let { studentToDelete = it }
                    showStudentEditor = false
                },
                onSave = { item ->
                    scope.launch {
                        if (item.id == 0L) {
                            if (!isPremium && students.count { !it.isDeleted } >= 2) {
                                showPremiumDialog = true
                                return@launch
                            }
                            dao.insertStudent(item)
                        } else {
                            dao.updateStudent(item)
                        }
                        onStudentDataChanged()
                        showStudentEditor = false
                    }
                    if (item.id != 0L && selectedStudent?.id == item.id) {
                        selectedStudent = item
                    }
                }
            )
        }

        studentToCollect?.let { student ->
            PaymentDialog(
                student = student,
                pendingAmount = (feeRecords.filter { it.studentId == student.id }.sumOf { it.amount } - payments.filter { it.studentId == student.id }.sumOf { it.amount }).coerceAtLeast(0L),
                onDismiss = { studentToCollect = null },
                onSave = { payment ->
                    scope.launch {
                        dao.insertPayment(payment)
                        onStudentDataChanged()
                        paymentHistoryRefreshKey++
                        studentToCollect = null
                        selectedStudent = null
                        tab = "Students"
                    }
                }
            )
        }

        paymentToEdit?.let { payment ->
            val paymentStudent = students.firstOrNull { it.id == payment.studentId }
            if (paymentStudent != null) {
                PaymentDialog(
                    student = paymentStudent,
                    pendingAmount = (feeRecords.filter { it.studentId == payment.studentId }.sumOf { it.amount } - payments.filter { it.studentId == payment.studentId }.sumOf { it.amount } + payment.amount).coerceAtLeast(0L),
                    existingPayment = payment,
                    onDismiss = { paymentToEdit = null },
                    onSave = { updated ->
                        scope.launch {
                            dao.updatePayment(updated)
                            onStudentDataChanged()
                            paymentHistoryRefreshKey++
                            paymentToEdit = null
                            selectedStudent = null
                            tab = "Students"
                        }
                    },
                    onDelete = {
                        paymentToDelete = payment
                        paymentToEdit = null
                    }
                )
            }
        }

        paymentToDelete?.let { payment ->
            AlertDialog(
                onDismissRequest = { paymentToDelete = null },
                shape = RoundedCornerShape(24.dp),
                containerColor = Color.White,
                title = { Text("Delete collected fee?") },
                text = {
                    Text("Delete this ${rupees(payment.amount)} payment? This cannot be undone.")
                },
                confirmButton = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { paymentToDelete = null },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFFD5E4DB))
                        ) {
                            Text("Cancel", color = Ink)
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    dao.deletePayment(payment)
                                    onStudentDataChanged()
                                    paymentHistoryRefreshKey++
                                    paymentToDelete = null
                                    selectedStudent = null
                                    tab = "Students"
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFFB3261E)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFB3261E)
                            )
                        ) {
                            Text("Delete")
                        }
                    }
                },
                dismissButton = {}
            )
        }


        studentToRemoveFromCollection?.let { archivedStudent ->
            AlertDialog(
                onDismissRequest = { studentToRemoveFromCollection = null },
                shape = RoundedCornerShape(24.dp),
                containerColor = Color.White,
                title = { Text("Remove deleted student?") },
                text = {
                    Text("Permanently remove ${archivedStudent.name} and their saved fee history?")
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            dao.permanentlyRemoveDeletedStudent(archivedStudent.id)
                            onStudentDataChanged()
                        }
                        studentToRemoveFromCollection = null
                    }) { Text("Remove", color = Color(0xFFB3261E)) }
                },
                dismissButton = {
                    TextButton(onClick = { studentToRemoveFromCollection = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        studentToDelete?.let { student ->
            AlertDialog(
                onDismissRequest = { studentToDelete = null },
                shape = RoundedCornerShape(24.dp),
                containerColor = Color.White,
                title = { Text("Delete student?") },
                text = {
                    Text("Delete ${student.name} from Students? Their collection history will remain available.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            dao.deleteStudent(student.id)
                            onStudentDataChanged()
                        }
                        studentToDelete = null
                    }) { Text("Delete", color = Color(0xFFB3261E)) }
                },
                dismissButton = {
                    TextButton(onClick = { studentToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
}

@Composable
private fun DashboardScreen(
    modifier: Modifier,
    studentCount: Int,
    collection: Long,
    payments: List<FeePaymentEntity>,
    pending: Long,
    monthKey: String,
    profileName: String,
    isPremium: Boolean,
    onProfileClick: () -> Unit,
    onPendingFeesClick: () -> Unit,
    onCollectionClick: () -> Unit
) {
    Column(
        modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
    Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    "Align Tuition",
                    color = Ink,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    profileName,
                    color = if (isPremium) Color(0xFFFFD76A) else Muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(onClick = onProfileClick),
                shape = RoundedCornerShape(50),
                color = Color(0xFFE5F4E9),
                border = BorderStroke(1.dp, Color(0xFFD8E9DE))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = profileName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "P",
                        color = Green,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }



        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DashboardMetricCard(
                "Total Students", studentCount.toString(),
                Color(0xFF176B43), Color(0xFFE5F4E9), Modifier.weight(1f)
            )
            DashboardMetricCard(
                "Pending Fees", rupees(pending),
                Color(0xFF9A4A08), Color(0xFFFFF0DF), Modifier.weight(1f),
                onClick = onPendingFeesClick
            )
        }

        var yearlyCollection by remember { mutableStateOf(false) }
        var collectionPeriod by remember { mutableStateOf(YearMonth.now()) }
        var collectionYear by remember { mutableStateOf(YearMonth.now().year) }

        val displayedCollection = remember(
            payments, yearlyCollection, collectionPeriod, collectionYear
        ) {
            payments.filter { payment ->
                runCatching {
                    val paymentMonth = YearMonth.parse(payment.monthKey)
                    if (yearlyCollection) {
                        paymentMonth.year == collectionYear
                    } else {
                        paymentMonth == collectionPeriod
                    }
                }.getOrDefault(false)
            }.sumOf { it.amount }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onCollectionClick),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFFE5EFFD),
            border = BorderStroke(1.dp, Color(0xFFD3E2FA))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Total Collection",
                        modifier = Modifier.weight(1f),
                        color = Color(0xFF245BB5),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Switch(
                        checked = yearlyCollection,
                        onCheckedChange = { yearlyCollection = it },
                        modifier = Modifier
                            .height(30.dp)
                            .padding(horizontal = 2.dp),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF245BB5),
                            uncheckedThumbColor = Color(0xFF245BB5),
                            checkedTrackColor = Color.White,
                            uncheckedTrackColor = Color.White,
                            checkedBorderColor = Color(0xFFD3E2FA),
                            uncheckedBorderColor = Color(0xFFD3E2FA)
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            rupees(displayedCollection),
                            color = Color(0xFF173F7A),
                            fontSize = 27.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (yearlyCollection) "Year $collectionYear"
                            else collectionPeriod.format(
                                java.time.format.DateTimeFormatter.ofPattern(
                                    "MMMM yyyy", Locale.ENGLISH
                                )
                            ),
                            color = Color(0xFF245BB5),
                            fontSize = 13.sp
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                if (yearlyCollection) collectionYear--
                                else collectionPeriod = collectionPeriod.minusMonths(1)
                            }
                        ) {
                            Text(
                                text = "‹",
                                color = Color(0xFF245BB5),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                        IconButton(
                            onClick = {
                                if (yearlyCollection) collectionYear++
                                else collectionPeriod = collectionPeriod.plusMonths(1)
                            }
                        ) {
                            Text(
                                text = "›",
                                color = Color(0xFF245BB5),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

    }
}


@Composable
private fun CollectionScreen(
    modifier: Modifier,
    students: List<StudentEntity>,
    payments: List<FeePaymentEntity>,
    monthKey: String,
    onStudentClick: (StudentEntity) -> Unit,
    onBack: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf("This Month") }
    val today = java.time.YearMonth.now()
    var selectedMonthKey by remember { mutableStateOf(monthKey) }
    var selectedYear by remember { mutableStateOf(today.year) }
    var monthPickerOpen by remember { mutableStateOf(false) }
    var yearPickerOpen by remember { mutableStateOf(false) }

    val selectedMonth = remember(selectedMonthKey) {
        runCatching { java.time.YearMonth.parse(selectedMonthKey) }
            .getOrDefault(today)
    }
    val years = remember(payments, today.year, selectedYear, selectedMonth.year) {
        (payments.mapNotNull {
            runCatching { java.time.YearMonth.parse(it.monthKey).year }.getOrNull()
        } + listOf(today.year, selectedYear, selectedMonth.year))
            .filter { it > 0 }.distinct().sortedDescending()
    }
    val monthNames = java.time.Month.values().map {
        it.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
    }

    val selectedPayments = remember(payments, selectedFilter, selectedMonthKey, selectedYear) {
        when (selectedFilter) {
            "This Month" -> payments.filter { it.monthKey == selectedMonthKey }
            "This Year" -> payments.filter {
                runCatching { java.time.YearMonth.parse(it.monthKey).year == selectedYear }
                    .getOrDefault(false)
            }
            else -> payments
        }
    }

    val monthButtonLabel = "${monthNames[selectedMonth.monthValue - 1]} ${selectedMonth.year}"
    val totalLabel = when (selectedFilter) {
        "This Month" -> monthButtonLabel
        "This Year" -> "Year $selectedYear"
        else -> "All recorded collections"
    }

    val total = selectedPayments.sumOf { it.amount }
    Column(modifier.fillMaxSize().background(Page)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF123D2F))
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Text(
                "Total Collection",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("This Month", "This Year", "All Time").forEach { filter ->
                val selected = selectedFilter == filter
                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                    androidx.compose.material3.Button(
                        onClick = {
                            if (selected) {
                                if (filter == "This Month") {
                                    monthPickerOpen = !monthPickerOpen
                                    yearPickerOpen = false
                                } else if (filter == "This Year") {
                                    yearPickerOpen = !yearPickerOpen
                                    monthPickerOpen = false
                                }
                            } else {
                                selectedFilter = filter
                                monthPickerOpen = false
                                yearPickerOpen = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) Green else Color.White,
                            contentColor = if (selected) Color.White else Ink
                        ),
                        border = if (selected) null else BorderStroke(1.dp, Color(0xFFD8E9DE))
                    ) {
                        Text(
                            when (filter) {
                                "This Month" -> if (selected) monthButtonLabel else "This Month"
                                "This Year" -> if (selected) selectedYear.toString() else "This Year"
                                else -> "All Time"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }

                    androidx.compose.material3.DropdownMenu(
                        expanded = filter == "This Month" && monthPickerOpen,
                        onDismissRequest = { monthPickerOpen = false },
                        modifier = Modifier
                            .background(Color.White)
                    ) {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = {
                                selectedMonthKey = selectedMonth.minusYears(1).toString()
                            }) { Text("‹") }
                            Text(
                                selectedMonth.year.toString(),
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = {
                                selectedMonthKey = selectedMonth.plusYears(1).toString()
                            }) { Text("›") }
                        }
                        monthNames.forEachIndexed { index, name ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(
                                        name,
                                        fontWeight = if (selectedMonth.monthValue == index + 1)
                                            FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    selectedMonthKey = selectedMonth
                                        .withMonth(index + 1).toString()
                                    monthPickerOpen = false
                                }
                            )
                        }
                        }
                    }

                    androidx.compose.material3.DropdownMenu(
                        expanded = filter == "This Year" && yearPickerOpen,
                        onDismissRequest = { yearPickerOpen = false },
                        modifier = Modifier
                            .background(Color.White)
                    ) {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                        years.forEach { year ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(
                                        year.toString(),
                                        fontWeight = if (selectedYear == year)
                                            FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    selectedYear = year
                                    yearPickerOpen = false
                                }
                            )
                        }
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE5F4EA)),
            border = BorderStroke(1.dp, Color(0xFFC6E6D0))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Total Collection",
                    color = Color(0xFF245B3F),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(totalLabel, color = Color(0xFF557565), fontSize = 13.sp)
                Text(
                    rupees(total),
                    color = Color(0xFF14532D),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (students.isEmpty()) {
                EmptyState("No collection records", "Collected fees will appear here.")
            } else if (selectedPayments.isEmpty()) {
                EmptyState("No collection for this filter", "Try another collection period.")
            }

            students.forEach { student ->
                val collected = selectedPayments
                    .filter { it.studentId == student.id }
                    .sumOf { it.amount }

                if (collected > 0L || selectedFilter == "All Time") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onStudentClick(student) },
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            1.dp,
                            if (student.isDeleted) Color(0xFFFFD9B8)
                            else Color(0xFFD8E9DE)
                        )
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    student.name,
                                    color = Ink,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    if (student.isDeleted) "Deleted student · Tap to remove"
                                    else listOf(student.className, student.phone)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                    color = if (student.isDeleted) Color(0xFF9A4A08) else Muted,
                                    fontSize = 12.sp
                                )
                            }
                            Text(
                                rupees(collected),
                                color = Color(0xFF245BB5),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardMetricCard(
    title: String,
    value: String,
    accent: Color,
    background: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        colors = CardDefaults.cardColors(containerColor = background),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                title, color = accent, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, minLines = 2
            )
            Text(
                value, color = Ink, fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(subtitle, color = accent, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Muted, fontSize = 13.sp)
            Text(value, color = Ink, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = Green, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StudentsScreen(
    modifier: Modifier,
    pendingOnly: Boolean,
    students: List<StudentEntity>,
    pendingByStudent: Map<Long, Long>,
    onAdd: () -> Unit,
    onEdit: (StudentEntity) -> Unit,
    onStudentClick: (StudentEntity) -> Unit,
    onCollect: (StudentEntity) -> Unit
) {
    var search by remember { mutableStateOf("") }
    val filtered = students.filter { student ->
        (!pendingOnly || (pendingByStudent[student.id] ?: 0L) > 0L) &&
            (
                student.name.contains(search, ignoreCase = true) ||
                student.phone.contains(search) ||
                student.className.contains(search, ignoreCase = true)
            )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Color(0xFF123D2F))) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF123D2F))
                    .padding(start = 16.dp, end = 16.dp, top = 44.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(16.dp)),
                    singleLine = true,
                    placeholder = { Text("Search name, phone or class") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    shape = RoundedCornerShape(16.dp)
                )
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Page)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (filtered.isEmpty()) {
                    EmptyState(
                        if (pendingOnly) "No pending fees" else if (students.isEmpty()) "No students added" else "No matching students",
                        "Add a student with their own fee details."
                    )
                }
                filtered.forEach { student ->
                    StudentCard(
                        student = student,
                        pendingPayment = pendingByStudent[student.id] ?: 0L,
                        onClick = { onStudentClick(student) },
                        onEdit = { onEdit(student) },
                        onCollect = { onCollect(student) }
                    )
                }
            }
        }


    }
}

@Composable
private fun StudentCard(
    student: StudentEntity,
    pendingPayment: Long,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onCollect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onClick,
            onLongClick = onEdit
        ),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFFE2EBE5)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(46.dp)
                        .background(Color(0xFFE7F3EC), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        student.name.trim().firstOrNull()?.uppercase() ?: "?",
                        color = Color(0xFF176B48),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        student.name,
                        color = Ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        listOf(student.className, student.phone)
                            .filter { it.isNotBlank() }
                            .joinToString(" • "),
                        color = Muted,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
                if (student.monthlyFee > 0L) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(rupees(student.monthlyFee), color = Green, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(feeTypeLabel(student), color = Muted, fontSize = 11.sp)
                    }
                }
            }

            HorizontalDivider(color = Color(0xFFEDF1EE), thickness = 1.dp)

            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(Color(0xFFF1F8F4), RoundedCornerShape(13.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Pending Payment", color = Color(0xFF52665A), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(
                        rupees(pendingPayment),
                        color = if (pendingPayment > 0L) Color(0xFFB45309) else Green,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Text(
                    if (pendingPayment > 0L) "Collect" else "CLEAR",
                    color = if (pendingPayment > 0L) Color(0xFF9A4A08) else Green,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(
                            if (pendingPayment > 0L) Color(0xFFFFEBD1) else Color(0xFFDDF3E5),
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = if (pendingPayment > 0L) Color(0xFFE9B875) else Color(0xFFB8DEC7),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .then(
                            if (pendingPayment > 0L) {
                                Modifier.clickable(onClick = onCollect)
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun StudentDetailsScreen(
    modifier: Modifier,
    student: StudentEntity,
    payments: List<FeePaymentEntity>,
    historyRefreshKey: Int,
    feeRecords: List<FeeRecordEntity>,
    onEdit: () -> Unit,
    onRecordFee: () -> Unit,
    onCollect: () -> Unit,
    onPaymentClick: (FeePaymentEntity) -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(student.id, payments) {
        Log.d("PAYMENT_UI", "Details student=${student.id}, count=${payments.size}, ids=${payments.map { it.id }}")
    }
    Column(Modifier.fillMaxSize().background(Page)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Color(0xFF123D2F))
                .padding(start = 12.dp, top = 44.dp, end = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                student.name,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

        val pendingPaymentTotal = (feeRecords.sumOf { it.amount } - payments.sumOf { it.amount }).coerceAtLeast(0L)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFFD8E9DE))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Pending Payment",
                    color = Muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    rupees(pendingPaymentTotal),
                    color = Green,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

            Text("Payment history", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            if (payments.isEmpty()) {
                EmptyState("No payments yet", "Payments collected for this student will appear here.")
            } else {
                payments.forEach { payment ->
                    key(payment.id, historyRefreshKey) {
                    Card(
                        onClick = { onPaymentClick(payment) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    paymentPeriodLabel(payment.monthKey),
                                    color = Ink,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "${payment.method} • ${dateText(payment.paidAt)}",
                                    color = Muted,
                                    fontSize = 12.sp
                                )
                                if (payment.note.isNotBlank()) {
                                    Text(payment.note, color = Muted, fontSize = 12.sp)
                                }
                            }
                            Text(rupees(payment.amount), color = Green, fontWeight = FontWeight.Bold)
                        }
                    }
                    }
                }
            }
    }

        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Page)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onRecordFee,
                modifier = Modifier.weight(1f).height(60.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFFE0AD00)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFFFFF8E1),
                    contentColor = Color(0xFF795900)
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        Icons.Filled.ReceiptLong,
                        contentDescription = null,
                        modifier = Modifier.size(21.dp)
                    )
                    Text(
                        "Record Fee",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            Button(
                onClick = onCollect,
                modifier = Modifier.weight(1f).height(60.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(21.dp)
                    )
                    Text(
                        "Collect Fee",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }


}
}


@Composable
private fun FeesScreen(
    modifier: Modifier,
    payments: List<FeePaymentEntity>,
    students: List<StudentEntity>,
    monthKey: String,
    monthCollection: Long,
    pending: Long,
    onCollect: (StudentEntity) -> Unit
) {
    val names = students.associate { it.id to it.name }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("This month • $monthKey", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                StatCard("Collected", rupees(monthCollection), "This month")
            }
            Column(Modifier.weight(1f)) {
                StatCard("Pending", rupees(pending), "This month")
            }
        }

        Text("Record a payment", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (students.isEmpty()) {
            EmptyState("No students", "Add a student before recording a payment.")
        } else {
            students.forEach { student ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(student.name, color = Ink, fontWeight = FontWeight.SemiBold)
                            Text("Monthly fee: ${rupees(student.monthlyFee)}", color = Muted, fontSize = 12.sp)
                        }
                        Button(onClick = { onCollect(student) }) { Text("Collect") }
                    }
                }
            }
        }

        HorizontalDivider()
        Text("Payment history", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (payments.isEmpty()) {
            EmptyState("No payments yet", "Payments you record will appear here.")
        } else {
            payments.forEach { payment ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(names[payment.studentId] ?: "Student", color = Ink, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${paymentPeriodLabel(payment.monthKey)} • ${payment.method} • ${dateText(payment.paidAt)}",
                                color = Muted,
                                fontSize = 11.sp
                            )
                            if (payment.note.isNotBlank()) {
                                Text(payment.note, color = Muted, fontSize = 12.sp)
                            }
                        }
                        Text(rupees(payment.amount), color = Green, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}


@Composable
private fun RecordFeeScreen(
    student: StudentEntity,
    history: List<FeeRecordEntity>,
    onBack: () -> Unit,
    onSave: suspend (StudentEntity, FeeRecordEntity) -> FeeRecordEntity,
    onDelete: suspend (FeeRecordEntity) -> Unit
) {
    val context = LocalContext.current

    val feeScreenScrollState = rememberScrollState()
    val feeScreenScope = rememberCoroutineScope()
    var editingRecord by remember(student.id) { mutableStateOf<FeeRecordEntity?>(null) }
    var displayedHistory by remember(student.id) { mutableStateOf(history) }
var showDeleteConfirmation by remember(student.id) { mutableStateOf(false) }
    var entryKind by remember(student.id) {
        mutableStateOf(
            context.getSharedPreferences("tuition_preferences", 0)
                .getString("record_fee_entry_kind", "FEES") ?: "FEES"
        )
    }
    var titleText by remember(student.id) { mutableStateOf("") }
    var feeType by remember(student.id) {
        mutableStateOf(
            context.getSharedPreferences("tuition_preferences", 0)
                .getString("record_fee_type", "MONTHLY") ?: "MONTHLY"
        )
    }
    var feeText by remember(student.id) { mutableStateOf("") }
    var noteText by remember(student.id) { mutableStateOf("") }
    var startDate by remember(student.id) {
        mutableStateOf(LocalDate.now().withDayOfMonth(1).toString())
    }
    var endDate by remember(student.id) { mutableStateOf(LocalDate.now().toString()) }

    var selectedMonthKey by remember(student.id) {
        mutableStateOf(YearMonth.now().toString())
    }
    var selectedYear by remember(student.id) {
        mutableStateOf(LocalDate.now().year.toString())
    }
    var monthMenuExpanded by remember { mutableStateOf(false) }
    var yearMenuExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(Modifier.fillMaxSize().background(Page)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Color(0xFF123D2F))
                .padding(start = 12.dp, top = 44.dp, end = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (editingRecord == null) "Record Fee" else "Edit Fee Record",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(feeScreenScrollState)
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
    

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFFE0E8E3)),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = entryKind == "FEES",
                        onClick = {
                            entryKind = "FEES"
                            context.getSharedPreferences("tuition_preferences", 0)
                                .edit().putString("record_fee_entry_kind", "FEES").apply()
                            error = ""
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFC107),
                            selectedLabelColor = Color(0xFF212121)
                        ),
                        label = { Text("Fees") }
                    )
                    FilterChip(
                        selected = entryKind == "OTHER",
                        onClick = {
                            entryKind = "OTHER"
                            context.getSharedPreferences("tuition_preferences", 0)
                                .edit().putString("record_fee_entry_kind", "OTHER").apply()
                            error = ""
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFC107),
                            selectedLabelColor = Color(0xFF212121)
                        ),
                        label = { Text("Other Payment") }
                    )
                }

                if (entryKind == "FEES") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        "MONTHLY" to "Monthly",
                        "YEARLY" to "Yearly",
                        "DATE_RANGE" to "Custom"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = feeType == value,
                            onClick = {
                                feeType = value
                                context.getSharedPreferences("tuition_preferences", 0)
                                    .edit().putString("record_fee_type", value).apply()
                                error = ""
                            },
                            label = {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, maxLines = 1, textAlign = TextAlign.Center)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF14532D),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                }

                if (entryKind == "FEES") {
                when (feeType) {
                    "MONTHLY" -> {
                        val monthLabel = runCatching {
                            YearMonth.parse(selectedMonthKey).format(
                                DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
                            )
                        }.getOrDefault(selectedMonthKey)

                        Box(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = monthLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Select month") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White,
                                    disabledContainerColor = Color.White,
                                    focusedBorderColor = Green,
                                    unfocusedBorderColor = Color(0xFFD8DEE8)
                                ),
                                trailingIcon = {
                                    Text("▾", color = Green)
                                }
                            )
                            Box(Modifier.matchParentSize().clickable {
                                monthMenuExpanded = true
                            })
                            DropdownMenu(
                                expanded = monthMenuExpanded,
                                onDismissRequest = { monthMenuExpanded = false },
                                modifier = Modifier.heightIn(max = 280.dp).background(Color.White)
                            ) {
                                (-12L..12L).map { YearMonth.now().plusMonths(it) }
                                    .forEach { month ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    month.format(
                                                        DateTimeFormatter.ofPattern(
                                                            "MMMM yyyy",
                                                            Locale.getDefault()
                                                        )
                                                    )
                                                )
                                            },
                                            onClick = {
                                                selectedMonthKey = month.toString()
                                                monthMenuExpanded = false
                                                error = ""
                                            }
                                        )
                                    }
                            }
                        }
                    }

                    "YEARLY" -> {
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = selectedYear,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Select year") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White,
                                    disabledContainerColor = Color.White,
                                    focusedBorderColor = Green,
                                    unfocusedBorderColor = Color(0xFFD8DEE8)
                                ),
                                trailingIcon = {
                                    Text("▾", color = Green)
                                }
                            )
                            Box(Modifier.matchParentSize().clickable {
                                yearMenuExpanded = true
                            })
                            DropdownMenu(
                                expanded = yearMenuExpanded,
                                onDismissRequest = { yearMenuExpanded = false },
                                modifier = Modifier.heightIn(max = 280.dp).background(Color.White)
                            ) {
                                ((LocalDate.now().year - 10)..(LocalDate.now().year + 10))
                                    .forEach { year ->
                                        DropdownMenuItem(
                                            text = { Text(year.toString()) },
                                            onClick = {
                                                selectedYear = year.toString()
                                                yearMenuExpanded = false
                                                error = ""
                                            }
                                        )
                                    }
                            }
                        }
                    }

                    "DATE_RANGE" -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value = startDate,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("From date", maxLines = 1) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                Box(Modifier.matchParentSize().clickable {
                                    showFeeDatePicker(context, startDate) {
                                        startDate = it
                                        error = ""
                                    }
                                })
                            }
                            Box(Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value = endDate,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("To date", maxLines = 1) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                Box(Modifier.matchParentSize().clickable {
                                    showFeeDatePicker(context, endDate) {
                                        endDate = it
                                        error = ""
                                    }
                                })
                            }
                        }
                    }
                }

                } else {
                    OutlinedTextField(
                        value = titleText,
                        onValueChange = { titleText = it; error = "" },
                        label = { Text("Title *") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = feeText,
                    onValueChange = { feeText = it; error = "" },
                    label = {
                        Text(
                            if (entryKind == "OTHER") "Amount (${selectedTuitionCurrency(context).symbol}) *" else when (feeType) {
                                "YEARLY" -> "Yearly fee (${selectedTuitionCurrency(context).symbol}) *"
                                "DATE_RANGE" -> "Total period fee (${selectedTuitionCurrency(context).symbol}) *"
                                else -> "Monthly fee (${selectedTuitionCurrency(context).symbol}) *"
                            }
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )

                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                if (error.isNotBlank()) {
                    Text(error, color = Color(0xFFB3261E), fontSize = 13.sp)
                }

                Button(
                    onClick = {
                        val fee = feeText.toLongOrNull()
                        val start = runCatching { LocalDate.parse(startDate) }.getOrNull()
                        val end = runCatching { LocalDate.parse(endDate) }.getOrNull()

                        when {
                            entryKind == "OTHER" && titleText.isBlank() ->
                                error = "Enter a title."
                            fee == null || fee <= 0L ->
                                error = "Enter a fee amount greater than zero."
                            entryKind == "FEES" && feeType == "DATE_RANGE" &&
                                (start == null || end == null || end.isBefore(start)) ->
                                error = "Select a valid From Date and To Date."
                            else -> {
                                val updatedStudent = student
                                val record = FeeRecordEntity(
                                    id = editingRecord?.id ?: 0L,
                                    studentId = student.id,
                                    feeType = if (entryKind == "OTHER") "OTHER" else feeType,
                                    amount = fee,
                                    startDate = when (feeType) { "DATE_RANGE" -> startDate; "MONTHLY" -> selectedMonthKey; else -> selectedYear },
                                    endDate = if (feeType == "DATE_RANGE") endDate else "",
                                    recordedAt = editingRecord?.recordedAt ?: System.currentTimeMillis(),
                                    note = noteText.trim(),
                                    title = if (entryKind == "OTHER") titleText.trim() else ""
                                )
                                feeScreenScope.launch {
                                    try {
                                        val savedRecord = onSave(updatedStudent, record)
                                        keyboardController?.hide()
                                        displayedHistory = if (record.id == 0L) {
                                            listOf(savedRecord) + displayedHistory
                                        } else {
                                            displayedHistory.map { if (it.id == savedRecord.id) savedRecord else it }
                                        }
                                        editingRecord = null
                                        feeText = ""
                                        noteText = ""
                                        titleText = ""
                                        selectedMonthKey = YearMonth.now().toString()
                                        selectedYear = LocalDate.now().year.toString()
                                        startDate = LocalDate.now().withDayOfMonth(1).toString()
                                        endDate = LocalDate.now().toString()
                                        error = ""
                                    } catch (e: Exception) {
                                        error = "Could not save fee record. Please try again."
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Green)
                ) {
                    Text(if (editingRecord != null) "Save Changes" else if (entryKind == "OTHER") "Save Other Payment" else "Save Fee")
                }

                if (editingRecord != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { showDeleteConfirmation = true },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFFB3261E)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFB3261E)
                            )
                        ) {
                            Text("Delete", maxLines = 1)
                        }

                        OutlinedButton(
                            onClick = {
                                editingRecord = null
                                feeText = ""
                                noteText = ""
                                titleText = ""
                                selectedMonthKey = YearMonth.now().toString()
                                selectedYear = LocalDate.now().year.toString()
                                startDate = LocalDate.now().withDayOfMonth(1).toString()
                                endDate = LocalDate.now().toString()
                                error = ""
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFFD8DEE8)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Muted
                            )
                        ) {
                            Text("Cancel Edit", maxLines = 1)
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        if (displayedHistory.isEmpty()) {
            EmptyState("No fee records yet", "Saved Monthly, Yearly and Custom fees will appear here.")
        } else {
            displayedHistory.forEach { record ->
                val typeLabel = if (record.feeType == "OTHER") {
                    record.title.ifBlank { "Other Payment" }
                } else when (record.feeType) {
                    "YEARLY" -> "Yearly"
                    "DATE_RANGE" -> "Custom"
                    else -> "Monthly"
                }
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        feeScreenScope.launch {
                            feeScreenScrollState.animateScrollTo(0)
                        }
                        editingRecord = record
                        entryKind = if (record.feeType == "OTHER") "OTHER" else "FEES"
                        titleText = record.title
                        feeType = if (record.feeType == "OTHER") "MONTHLY" else record.feeType
                        feeText = record.amount.toString()
                        noteText = record.note
                        when (record.feeType) {
                            "MONTHLY" -> selectedMonthKey = record.startDate
                            "YEARLY" -> selectedYear = record.startDate
                            "DATE_RANGE" -> {
                                startDate = record.startDate
                                endDate = record.endDate
                            }
                        }
                        error = ""
                    },
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(typeLabel, color = Ink, fontWeight = FontWeight.SemiBold)
                            when (record.feeType) {
                                "OTHER" -> Unit
                                "MONTHLY" -> Text(
                                    runCatching {
                                        YearMonth.parse(record.startDate).format(
                                            java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
                                        )
                                    }.getOrDefault(record.startDate),
                                    color = Muted,
                                    fontSize = 12.sp
                                )
                                "YEARLY" -> Text(record.startDate, color = Muted, fontSize = 12.sp)
                                "DATE_RANGE" -> Text(
                                    "${record.startDate} to ${record.endDate}",
                                    color = Muted,
                                    fontSize = 12.sp
                                )
                            }
                            Text(
                                "Recorded ${dateText(record.recordedAt)}",
                                color = Muted,
                                fontSize = 12.sp
                            )
                            if (record.note.isNotBlank()) {
                                Text(
                                    "Note: ${record.note}",
                                    color = Ink,
                                    fontSize = 13.sp
                                )
                            }
                        }
                        Text(rupees(record.amount), color = Green, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color.White,
            title = { Text("Delete fee record?") },
            text = {
                Text(
                    "This permanently deletes this fee-history entry."
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { showDeleteConfirmation = false },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFD5E4DB))
                    ) {
                        Text("Cancel", color = Ink)
                    }

                    OutlinedButton(
                        onClick = {
                            val recordToDelete = editingRecord
                            if (recordToDelete != null) {
                                feeScreenScope.launch {
                                    try {
                                        onDelete(recordToDelete)
                                        displayedHistory = displayedHistory.filterNot { it.id == recordToDelete.id }
                                        editingRecord = null
                                        feeText = ""
                                        noteText = ""
                                        titleText = ""
                                        selectedMonthKey = YearMonth.now().toString()
                                        selectedYear = LocalDate.now().year.toString()
                                        startDate = LocalDate.now().withDayOfMonth(1).toString()
                                        endDate = LocalDate.now().toString()
                                        error = ""
                                        showDeleteConfirmation = false
                                    } catch (e: Exception) {
                                        error = "Could not delete fee record. Please try again."
                                        showDeleteConfirmation = false
                                    }
                                }
                            } else {
                                showDeleteConfirmation = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFB3261E)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFB3261E)
                        )
                    ) {
                        Text("Delete")
                    }
                }
            },
            dismissButton = {}
        )
    }

}


@Composable
private fun StudentEditorDialog(
    student: StudentEntity?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (StudentEntity) -> Unit
) {
    var name by remember(student?.id) { mutableStateOf(student?.name.orEmpty()) }
    var phone by remember(student?.id) { mutableStateOf(student?.phone.orEmpty()) }
    var className by remember(student?.id) { mutableStateOf(student?.className.orEmpty()) }
    var error by remember { mutableStateOf("") }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Green,
        unfocusedBorderColor = Color(0xFFD8DEE8),
        focusedLabelColor = Green,
        unfocusedLabelColor = Muted,
        focusedContainerColor = Color.White,
        unfocusedContainerColor = Color.White
    )

    AlertDialog(
        shape = RoundedCornerShape(24.dp),
        containerColor = Color.White,
        onDismissRequest = onDismiss,
        title = { Text(if (student == null) "Add Student" else "Edit Student") },
        text = {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = "" },
                    label = { Text("Student name *") },
                    singleLine = true,
                    colors = fieldColors,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = fieldColors,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = className,
                    onValueChange = { className = it },
                    label = { Text("Class / Batch") },
                    singleLine = true,
                    colors = fieldColors,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (error.isNotBlank()) {
                    Text(error, color = Color(0xFFB3261E), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (student != null) {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFB3261E)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFB3261E)
                        )
                    ) {
                        Text("Delete Student")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFD8DEE8)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
                    ) {
                        Text("Cancel")
                    }

                    OutlinedButton(
                        onClick = {
                            if (name.isBlank()) {
                                error = "Student name is required."
                            } else {
                                onSave(
                                    StudentEntity(
                                        id = student?.id ?: 0L,
                                        name = name.trim(),
                                        phone = phone.trim(),
                                        className = className.trim(),
                                        monthlyFee = student?.monthlyFee ?: 0L,
                                        feeType = student?.feeType ?: "MONTHLY",
                                        feeStartDate = student?.feeStartDate.orEmpty(),
                                        feeEndDate = student?.feeEndDate.orEmpty(),
                                        joinedAt = student?.joinedAt
                                            ?: System.currentTimeMillis()
                                    )
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Green),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Green)
                    ) {
                        Text("Save")
                    }
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
private fun PaymentDialog(
    student: StudentEntity,
    pendingAmount: Long,
    existingPayment: FeePaymentEntity? = null,
    onDismiss: () -> Unit,
    onSave: (FeePaymentEntity) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var amountText by remember(student.id, existingPayment?.id) {
        mutableStateOf(existingPayment?.amount?.toString().orEmpty())
    }
    var selectedMonthKey by remember(student.id, existingPayment?.id) {
        mutableStateOf(
            runCatching { YearMonth.parse(existingPayment?.monthKey.orEmpty()) }
                .getOrDefault(YearMonth.now()).toString()
        )
    }
    var monthMenuExpanded by remember(student.id, existingPayment?.id) {
        mutableStateOf(false)
    }
    var method by remember(student.id, existingPayment?.id) {
        mutableStateOf(
            if (existingPayment?.method == "Cash") "Cash"
            else if (existingPayment != null) "Online"
            else "Cash"
        )
    }
    var note by remember(student.id, existingPayment?.id) {
        mutableStateOf(existingPayment?.note.orEmpty())
    }
    var error by remember(student.id, existingPayment?.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color(0xFFF7FAF8),
        title = {
            Text(
                if (existingPayment == null) "Collect Fee" else "Edit Collected Fee",
                color = Color(0xFF123D2F),
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = {
                            amountText = it.filter(Char::isDigit)
                            error = ""
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("Amount (${selectedTuitionCurrency(LocalContext.current).symbol})") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        )
                    )

                    if (student.feeType == "MONTHLY") {
                    val selectedMonthLabel = runCatching {
                        YearMonth.parse(selectedMonthKey).format(
                            java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
                        )
                    }.getOrDefault(selectedMonthKey)

                    Box(
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = selectedMonthLabel,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Select Month") },
                            trailingIcon = { Text("▾", color = Green) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp)
                        )
                        Box(Modifier.matchParentSize().clickable {
                            monthMenuExpanded = true
                        })
                        DropdownMenu(
                            expanded = monthMenuExpanded,
                            onDismissRequest = { monthMenuExpanded = false },
                            modifier = Modifier.heightIn(max = 280.dp).background(Color.White)
                        ) {
                            (-24L..12L).map { YearMonth.now().plusMonths(it) }
                                .forEach { month ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                month.format(
                                                    java.time.format.DateTimeFormatter.ofPattern(
                                                        "MMM yyyy", Locale.ENGLISH
                                                    )
                                                )
                                            )
                                        },
                                        onClick = {
                                            selectedMonthKey = month.toString()
                                            monthMenuExpanded = false
                                            error = ""
                                        }
                                    )
                                }
                        }
                    }
                    }
                }

                Text(
                    "PAYMENT METHOD",
                    color = Ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Cash", "Online").forEach { option ->
                        FilterChip(
                            selected = method == option,
                            onClick = { method = option },
                            label = { Text(option) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFDCEFE3),
                                selectedLabelColor = Color(0xFF123D2F)
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )

                if (error.isNotBlank()) {
                    Text(error, color = Color(0xFFB3261E), fontSize = 12.sp)
                }

                if (existingPayment != null && onDelete != null) {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFB3261E)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFB3261E)
                        )
                    ) {
                        Text("Delete", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFD5E4DB))
                ) {
                    Text("Cancel", color = Ink)
                }

                Button(
                    onClick = {
                        val amount = amountText.toLongOrNull()
                        when {
                            amount == null || amount <= 0L ->
                                error = "Enter an amount greater than zero."
                            amount > pendingAmount ->
                                error = "Amount cannot exceed ${rupees(pendingAmount)}."
                            else -> onSave(
                                FeePaymentEntity(
                                    id = existingPayment?.id ?: 0L,
                                    studentId = student.id,
                                    monthKey = if (student.feeType == "MONTHLY") {
                                        selectedMonthKey
                                    } else {
                                        existingPayment?.monthKey
                                            ?: currentFeePeriodKey(student)
                                            ?: YearMonth.now().toString()
                                    },
                                    amount = amount,
                                    paidAt = existingPayment?.paidAt
                                        ?: System.currentTimeMillis(),
                                    method = method,
                                    note = note.trim()
                                )
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = pendingAmount > 0L,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF123D2F)
                    )
                ) {
                    Text(
                        if (existingPayment == null) "Collect" else "Save",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
private fun EmptyState(title: String, message: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = Ink, fontWeight = FontWeight.Bold)
        Text(message, color = Muted, fontSize = 13.sp)
    }
}

private data class TuitionCurrency(
    val code: String,
    val symbol: String,
    val label: String,
    val locale: Locale
)

private val tuitionCurrencies = listOf(
    TuitionCurrency("INR", "₹", "Indian Rupee (INR)", Locale("en", "IN")),
    TuitionCurrency("USD", "$", "US Dollar (USD)", Locale.US),
    TuitionCurrency("EUR", "€", "Euro (EUR)", Locale.GERMANY),
    TuitionCurrency("GBP", "£", "British Pound (GBP)", Locale.UK),
    TuitionCurrency("AED", "د.إ", "UAE Dirham (AED)", Locale("en", "AE")),
    TuitionCurrency("CAD", "C$", "Canadian Dollar (CAD)", Locale.CANADA),
    TuitionCurrency("AUD", "A$", "Australian Dollar (AUD)", Locale("en", "AU"))
)

private const val CURRENCY_PREFS = "tuition_preferences"
private const val CURRENCY_KEY = "display_currency"
private var appCurrencyCode by mutableStateOf("INR")


private fun selectedTuitionCurrency(context: android.content.Context): TuitionCurrency {
    val code = context.getSharedPreferences(CURRENCY_PREFS, 0)
        .getString(CURRENCY_KEY, "INR")
    return tuitionCurrencies.firstOrNull { it.code == code } ?: tuitionCurrencies.first()
}

private fun rupees(amount: Long): String {
    val currencyCode = appCurrencyCode
    val currency = tuitionCurrencies.firstOrNull { it.code == currencyCode }
        ?: tuitionCurrencies.first()
    return currency.symbol + "%,d".format(currency.locale, amount)
}

private fun dateText(timestamp: Long): String =
    SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(timestamp))


@Composable
private fun ProfileScreen(
    modifier: Modifier,
    profile: ProfileEntity?,
    onBack: () -> Unit,
    onSave: (String, String) -> Unit,
    onBackupClick: () -> Unit,
    onVerifyBackupClick: () -> Unit,
    onRestoreBackupClick: () -> Unit,
    onPrivacyPolicyClick: () -> Unit,
    onTermsClick: () -> Unit,
    onUnlockPremiumClick: () -> Unit,
    onStudentDataChanged: () -> Unit,
    isPremium: Boolean
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    SideEffect {
        appCurrencyCode = selectedTuitionCurrency(context).code
    }
    var name by remember(profile?.name) { mutableStateOf(profile?.name.orEmpty()) }
    var mobile by remember(profile?.mobile) { mutableStateOf(profile?.mobile.orEmpty()) }
    var savedMessage by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().background(Page)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF123D2F))
                .padding(start = 12.dp, end = 12.dp, top = 44.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Profile & Settings",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Manage your account",
                    color = Color(0xFFD0E6DC),
                    fontSize = 12.sp
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF176B4D))
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                            .padding(end = 76.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(66.dp),
                            shape = RoundedCornerShape(22.dp),
                            color = Color.White
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "P",
                                    color = Color(0xFF176B4D),
                                    fontSize = 27.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                name.ifBlank { "Your Profile" },
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                mobile.ifBlank { "Add your mobile number" },
                                color = Color(0xFFD9EEE4),
                                fontSize = 14.sp
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 20.dp, end = 20.dp),
                        shape = RoundedCornerShape(50),
                        color = if (isPremium) Color(0xFFFFD76A)
                                else Color.White.copy(alpha = 0.18f)
                    ) {
                        Text(
                            if (isPremium) "Premium" else "Free User",
                            modifier = Modifier.padding(
                                horizontal = 10.dp,
                                vertical = 6.dp
                            ),
                            color = if (isPremium) Color(0xFF6B3F12) else Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2EBE5))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        "Personal Information",
                        color = Ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; savedMessage = false },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Name") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(
                        value = mobile,
                        onValueChange = {
                            mobile = it.filter { c -> c.isDigit() || c == '+' || c == ' ' || c == '-' }
                                .take(16)
                            savedMessage = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Mobile Number") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                        shape = RoundedCornerShape(14.dp)
                    )
                    Button(
                        onClick = {
                            onSave(name.trim(), mobile.trim())
                            onStudentDataChanged()
                            savedMessage = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Save Profile")
                    }
                    if (savedMessage) {
                        Text(
                            "Profile Saved",
                            color = Green,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Text(
                "APP SETTINGS",
                color = Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )







            var selectedCurrencyCode by remember {
                mutableStateOf(selectedTuitionCurrency(context).code)
            }
            var currencyMenuExpanded by remember { mutableStateOf(false) }
            val selectedCurrency = tuitionCurrencies.first {
                it.code == selectedCurrencyCode
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                ProfileSettingCard(
                    icon = Icons.Default.Settings,
                    title = "Currency Setting",
                    subtitle = selectedCurrency.label,
                    status = "Change",
                    onClick = { currencyMenuExpanded = true }
                )
                DropdownMenu(
                    expanded = currencyMenuExpanded,
                    onDismissRequest = { currencyMenuExpanded = false },
                    modifier = Modifier.background(Color.White)
                ) {
                    tuitionCurrencies.forEach { currency ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${currency.symbol}  ${currency.label}",
                                    color = Ink
                                )
                            },
                            onClick = {
                                context.getSharedPreferences(CURRENCY_PREFS, 0)
                                    .edit()
                                    .putString(CURRENCY_KEY, currency.code)
                                    .apply()
                                appCurrencyCode = currency.code
                                selectedCurrencyCode = currency.code
                                currencyMenuExpanded = false
                            }
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                border = BorderStroke(1.dp, Color(0xFFD9E5DE))
            ) {
                ProfileSettingCard(
                    icon = Icons.Default.Settings,
                    title = "Unlock Premium",
                    subtitle = "Add unlimited students.",
                    status = "Explore",
                    onClick = onUnlockPremiumClick
                )
            }

            ProfileSettingCard(
                icon = Icons.Default.Settings,
                title = "Privacy Policy",
                subtitle = "How Align Tuition handles your data",
                status = "Read",
                onClick = onPrivacyPolicyClick
            )

            ProfileSettingCard(
                icon = Icons.Default.Settings,
                title = "Terms & Conditions",
                subtitle = "Terms for using Align Tuition",
                status = "Read",
                onClick = onTermsClick
            )



        }
    }
}

@Composable
private fun ProfileSettingCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    status: String,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(18.dp))
            .then(
                if (onClick != null) Modifier.clickable { onClick() }
                else Modifier
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
        border = BorderStroke(1.5.dp, if (title == "Unlock Premium") Color(0xFF16805D) else Color(0xFFE2EBE5))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFE5F4E9)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = Green)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Muted, fontSize = 12.sp)
            }
            Text(status, color = Muted, fontSize = 10.sp)
        }
    }
}


@Composable
private fun LegalDocumentScreen(
    documentType: String,
    onBack: () -> Unit
) {
    val isPrivacy = documentType == "Privacy Policy"
    val title = if (isPrivacy) "Privacy Policy" else "Terms & Conditions"

    Column(modifier = Modifier.fillMaxSize().background(Page)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF123D2F))
                .padding(start = 8.dp, end = 16.dp, top = 40.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Align Tuition", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color(0xFF123D2F))
            Text("Effective date: 19 October 2026", fontSize = 12.sp, color = Muted)

            if (isPrivacy) {
                LegalSection("1. Introduction",
                    "This Privacy Policy explains how AlignOS handles information when you use Align Tuition. For privacy questions, contact alignos.in@gmail.com.")
                LegalSection("2. Information stored",
                    "Information you enter may include your profile name and mobile number, student details, fee records, and fee collection or payment records. Please avoid entering information that is not needed for your tuition-management work.")
                LegalSection("3. Local storage",
                    "Tuition records are stored in the app's local database on your device. Anyone who can access your unlocked device or app data may be able to access those records. Keep your device secure.")
                LegalSection("4. Google Drive backup and restore",
                    "If you choose to use Google Drive backup, the app uses Google authorization to access backup functionality. The backup may contain student, fee, collection, and profile records. Restoring a backup can replace current app data. Keep a separate copy of important records before restoring.")
                LegalSection("5. Sharing and service providers",
                    "The app does not require you to create an Align Tuition online account. Google Drive features involve Google services under Google's own terms and privacy policy. Any additional analytics, advertising, or crash-reporting services, if added, must be disclosed according to their actual behavior.")
                LegalSection("6. Retention and deletion",
                    "Records may remain on your device until you delete them or remove the app and its data. A backup previously created in Google Drive may remain there even after local records are deleted; manage backup files through the applicable Google Drive controls.")
                LegalSection("7. Security and changes",
                    "Use device security features and protect access to your Google account. No method of storage or transfer can be guaranteed completely secure. This policy may be updated when the app's features or data practices change.")
                LegalSection("8. Contact",
                    "Publisher: AlignOS\nEmail: alignos.in@gmail.com")
            } else {
                LegalSection("1. Acceptance and purpose",
                    "Align Tuition is intended to help users manage tuition-related student information, fees, and collections. By using the app, you agree to use it lawfully and responsibly.")
                LegalSection("2. Your records and responsibilities",
                    "You are responsible for the accuracy, legality, and appropriate use of information entered into the app. Check fee entries, collections, and reports carefully. The app should not be treated as a substitute for maintaining any records required by law.")
                LegalSection("3. Device and backup responsibility",
                    "You are responsible for securing your device and Google account, where used. Backup availability depends on successful authorization, network access, Google services, and the backup process. Verify important backups and retain independent copies of critical records.")
                LegalSection("4. Restore operations",
                    "Restoring a backup may replace current app data. Review the selected backup carefully before proceeding. AlignOS is not responsible for data loss caused by an incorrect restore, device failure, account loss, or a backup that was not successfully completed.")
                LegalSection("5. Acceptable use",
                    "Do not use the app for unlawful activity, unauthorized access, or misuse of another person's information. You must have an appropriate basis and permission to enter and manage student information.")
                LegalSection("6. Availability and changes",
                    "Features may change, be interrupted, or become unavailable. We will make reasonable efforts to maintain the app, but uninterrupted operation and error-free storage cannot be guaranteed.")
                LegalSection("7. Disclaimer and liability",
                    "To the extent permitted by applicable law, the app is provided without a guarantee that it will meet every requirement or remain continuously available. Nothing in these terms excludes liability that cannot lawfully be excluded.")
                LegalSection("8. Intellectual property",
                    "The app's software, branding, and associated materials belong to their respective owners and may not be copied or misused except as permitted by law.")
                LegalSection("9. Governing law and contact",
                    "These terms are subject to applicable laws of India. Any legal rights available to you under applicable law remain unaffected. Publisher: AlignOS. Contact: alignos.in@gmail.com.")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun LegalSection(heading: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(heading, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF123D2F))
        Text(body, fontSize = 14.sp, lineHeight = 21.sp, color = Color(0xFF34423B))
    }
}
