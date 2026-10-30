package com.align.tuition.backup

import android.content.Context
import android.content.IntentSender
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class GoogleDriveSyncCoordinator(
    context: Context,
    private val authorization: GoogleDriveAuthorization,
    private val backup: GoogleDriveBackup,
    private val database: SupportSQLiteDatabase,
    private val onAuthorizationRequired: ((IntentSender) -> Unit)? = null,
    private val onSyncStarted: (() -> Unit)? = null,
    private val onSyncSuccess: (() -> Unit)? = null,
    private val onSyncFailure: ((Throwable) -> Unit)? = null,
    private val onRemoteBackupNewer:
        ((String, String, String) -> Unit)? = null,
    private val onRemoteCheckFinished: (() -> Unit)? = null
) {
    companion object {
        private const val AUTO_SYNC_DEBOUNCE_MS = 2500L
        private const val PREFS_NAME = "tuition_google_drive_sync"
        private const val AUTO_SYNC_ENABLED = "auto_sync_enabled"
        private const val PENDING_GENERATION = "pending_generation"
        private const val COMPLETED_GENERATION = "completed_generation"
        private const val LAST_SYNCED_DRIVE_MODIFIED_TIME =
            "last_synced_drive_modified_time"
    }

    private val applicationContext =
        context.applicationContext

    private val prefs =
        applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    private val lock = Any()

    private val generation =
        AtomicLong(
            prefs.getLong(
                PENDING_GENERATION,
                0L
            )
        )

    private var completedGeneration =
        prefs.getLong(
            COMPLETED_GENERATION,
            0L
        )

    private var running = false
    private var paused = false

    private val autoSyncHandler =
        android.os.Handler(android.os.Looper.getMainLooper())

    private val autoSyncRunnable = Runnable {
        requestSync(manual = false)
    }

    fun isAutoSyncEnabled(): Boolean =
        prefs.getBoolean(AUTO_SYNC_ENABLED, true)

    fun setAutoSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(AUTO_SYNC_ENABLED, enabled).apply()
        if (!enabled) {
            autoSyncHandler.removeCallbacks(autoSyncRunnable)
        }
    }

    fun requestAutoSync() {
        // Always persist local data changes, even while Auto Sync is OFF.
        // This lets startup resume upload changes made during the OFF session.
        synchronized(lock) {
            generation.incrementAndGet()
            persistPendingGeneration()
        }

        if (!isAutoSyncEnabled()) {
            autoSyncHandler.removeCallbacks(autoSyncRunnable)
            return
        }

        autoSyncHandler.removeCallbacks(autoSyncRunnable)
        autoSyncHandler.postDelayed(autoSyncRunnable, AUTO_SYNC_DEBOUNCE_MS)
    }

    /**
     * Uploads the current local database directly to the account represented
     * by accessToken. This deliberately skips startup remote-backup checks
     * and never restores remote data over the local database.
     */
    fun syncLocalDataToAccount(
        accessToken: String,
        callback: (Result<Unit>) -> Unit
    ) {
        onSyncStarted?.invoke()
        backup.sync(database, accessToken) { result ->
            result.onSuccess {
                onSyncSuccess?.invoke()
            }.onFailure { error ->
                onSyncFailure?.invoke(error)
            }
            callback(result)
        }
    }

    fun requestManualSync() {
        requestSync(manual = true)
    }

    fun pause() {
        synchronized(lock) {
            paused = true
        }
    }

    fun resume() {
        val shouldCheckRemote = synchronized(lock) {
            if (running) {
                false
            } else {
                paused = true
                running = true
                true
            }
        }

        if (shouldCheckRemote) {
            checkRemoteBackupBeforeSync()
        }
    }

    private fun checkRemoteBackupBeforeSync() {
        authorization
            .authorize()
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    synchronized(lock) {
                        running = false
                    }
                    return@addOnSuccessListener
                }

                val accessToken = result.accessToken

                if (accessToken.isNullOrBlank()) {
                    finishStartupCheck(true)
                    return@addOnSuccessListener
                }

                val restore =
                    GoogleDriveRestore(applicationContext)

                restore.findBackupMetadata(accessToken) { result ->
                    result
                        .onSuccess { metadata ->
                            if (metadata == null) {
                                finishStartupCheck(true)
                                return@onSuccess
                            }

                            val remote =
                                runCatching {
                                    Instant.parse(
                                        metadata.modifiedTime
                                    )
                                }.getOrNull()

                            if (remote == null) {
                                finishStartupCheck(true)
                                return@onSuccess
                            }

                            val localText =
                                prefs.getString(
                                    LAST_SYNCED_DRIVE_MODIFIED_TIME,
                                    null
                                )

                            val local =
                                localText?.let {
                                    runCatching {
                                        Instant.parse(it)
                                    }.getOrNull()
                                }

                            if (
                                local == null ||
                                remote.isAfter(local)
                            ) {
                                synchronized(lock) {
                                    paused = true
                                    running = false
                                }

                                onRemoteBackupNewer?.invoke(
                                    metadata.fileId,
                                    metadata.modifiedTime,
                                    accessToken
                                )
                            } else {
                                finishStartupCheck(true)
                            }
                        }
                        .onFailure {
                            finishStartupCheck(true)
                        }
                }
            }
            .addOnFailureListener {
                // Drive unavailable must never block local app startup.
                finishStartupCheck(true)
            }
    }

    private fun finishStartupCheck(
        startPendingSync: Boolean
    ) {
        val shouldStart = synchronized(lock) {
            paused = false
            running = false

            isAutoSyncEnabled() &&
                startPendingSync &&
                generation.get() > completedGeneration
        }

        onRemoteCheckFinished?.invoke()

        if (shouldStart) {
            synchronized(lock) {
                if (running || paused) {
                    return@synchronized
                }
                running = true
            }

            startSync(manual = false)
        }
    }

    fun markRemoteBackupSynced(
        modifiedTime: String
    ) {
        prefs.edit()
            .putString(
                LAST_SYNCED_DRIVE_MODIFIED_TIME,
                modifiedTime
            )
            .apply()

        synchronized(lock) {
            paused = false
            running = false
        }
    }

    fun resumeAfterRemoteRestoreFailure() {
        finishStartupCheck(true)
    }

    private fun requestSync(manual: Boolean) {
        if (!manual && !isAutoSyncEnabled()) return
        val shouldStart = synchronized(lock) {
            generation.incrementAndGet()
            persistPendingGeneration()

            if (
                !paused &&
                !running
            ) {
                running = true
                true
            } else {
                false
            }
        }

        if (shouldStart) {
            startSync(manual)
        }
    }

    /**
     * Continue the pending manual backup with the token returned by Google's
     * authorization activity. Do not call authorize() again here.
     */
    fun continueManualSyncWithAccessToken(accessToken: String) {
        if (accessToken.isBlank()) {
            synchronized(lock) { running = false }
            onSyncFailure?.invoke(
                IllegalStateException("Google Drive returned an empty access token.")
            )
            return
        }

        val syncGeneration = synchronized(lock) {
            if (running) {
                return
            }
            running = true
            generation.get()
        }

        onSyncStarted?.invoke()
        backup.sync(database, accessToken) { syncResult ->
            syncResult
                .onSuccess {
                    finishSync(syncGeneration, success = true, error = null)
                }
                .onFailure { error ->
                    finishSync(syncGeneration, success = false, error = error)
                }
        }
    }

    private fun startSync(manual: Boolean) {
        val syncGeneration = generation.get()

        onSyncStarted?.invoke()

        authorization
            .authorize()
            .addOnSuccessListener { result ->

                if (result.hasResolution()) {
                    synchronized(lock) {
                        running = false
                    }

                    if (manual) {
                        val pendingIntent = result.pendingIntent

                        if (pendingIntent != null) {
                            onAuthorizationRequired?.invoke(
                                pendingIntent.intentSender
                            )
                        } else {
                            onSyncFailure?.invoke(
                                IllegalStateException(
                                    "Google Drive authorization is required."
                                )
                            )
                        }
                    }

                    return@addOnSuccessListener
                }

                val accessToken = result.accessToken

                if (accessToken.isNullOrBlank()) {
                    finishSync(
                        syncGeneration,
                        success = false,
                        error = IllegalStateException(
                            "Google Drive access token is unavailable."
                        )
                    )
                    return@addOnSuccessListener
                }

                backup.sync(
                    database,
                    accessToken
                ) { syncResult ->

                    syncResult
                        .onSuccess {
                            finishSync(
                                syncGeneration,
                                success = true,
                                error = null
                            )
                        }
                        .onFailure { error ->
                            finishSync(
                                syncGeneration,
                                success = false,
                                error = error
                            )
                        }
                }
            }
            .addOnFailureListener { error ->
                finishSync(
                    syncGeneration,
                    success = false,
                    error = error
                )
            }
    }

    private fun finishSync(
        syncGeneration: Long,
        success: Boolean,
        error: Throwable?
    ) {
        var startNext = false

        synchronized(lock) {
            running = false

            if (success) {
                if (generation.get() == syncGeneration) {
                    completedGeneration = syncGeneration
                    persistCompletedGeneration()
                } else if (!paused) {
                    /*
                     * Changes happened while this upload was running.
                     * Do not start another upload in parallel; start it
                     * only after this upload has completely finished.
                     */
                    running = true
                    startNext = true
                }
            }
        }

        if (!success) {
            error?.let {
                onSyncFailure?.invoke(it)
            }
            return
        }

        if (success) {
            refreshLastSyncedDriveModifiedTime()
        }

        onSyncSuccess?.invoke()

        if (startNext) {
            startSync(manual = false)
        }
    }

    private fun refreshLastSyncedDriveModifiedTime() {
        authorization
            .authorize()
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    return@addOnSuccessListener
                }

                val token =
                    result.accessToken
                        ?: return@addOnSuccessListener

                val restore =
                    GoogleDriveRestore(applicationContext)

                restore.findBackupMetadata(token) { result ->
                    result.onSuccess { metadata ->
                        metadata?.modifiedTime?.let {
                            prefs.edit()
                                .putString(
                                    LAST_SYNCED_DRIVE_MODIFIED_TIME,
                                    it
                                )
                                .apply()
                        }
                    }
                }
            }
    }

    private fun persistPendingGeneration() {
        prefs.edit()
            .putLong(
                PENDING_GENERATION,
                generation.get()
            )
            .apply()
    }

    private fun persistCompletedGeneration() {
        prefs.edit()
            .putLong(
                COMPLETED_GENERATION,
                completedGeneration
            )
            .apply()
    }
}
