package com.align.tuition.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

class GoogleDriveRestore(
    context: Context
) {

    data class BackupMetadata(
        val fileId: String,
        val modifiedTime: String
    )


    companion object {
        private const val PREFERENCES_NAME = "tuition_backup"
        private const val FILE_ID_KEY = "google_drive_file_id"
        private const val DRIVE_FILE_URL =
            "https://www.googleapis.com/drive/v3/files"
    }

    private val context = context.applicationContext

    private val preferences =
        this.context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )

    private val mainHandler =
        Handler(Looper.getMainLooper())

    fun restore(
        accessToken: String,
        callback: (Result<Unit>) -> Unit
    ) {
        restore(accessToken, null, callback)
    }

    fun restore(
        accessToken: String,
        fileIdOverride: String?,
        callback: (Result<Unit>) -> Unit
    ) {
        Thread {
            val downloadedFile =
                File(
                    context.cacheDir,
                    "tuition_restore_download.db"
                )

            val validatedFile =
                File(
                    context.cacheDir,
                    "tuition_restore_validated.db"
                )

            try {
                val fileId =
                    fileIdOverride
                        ?: preferences.getString(
                            FILE_ID_KEY,
                            null
                        )

                if (fileId.isNullOrBlank()) {
                    throw NoBackupFoundException()
                }

                preferences.edit()
                    .putString(
                        FILE_ID_KEY,
                        fileId
                    )
                    .apply()

                downloadedFile.delete()
                validatedFile.delete()

                downloadBackup(
                    fileId,
                    accessToken,
                    downloadedFile
                )

                validateDatabase(
                    downloadedFile
                )

                downloadedFile.copyTo(
                    validatedFile,
                    overwrite = true
                )

                mainHandler.post {
                    callback(
                        Result.success(Unit)
                    )
                }
            } catch (error: Exception) {
                downloadedFile.delete()
                validatedFile.delete()

                mainHandler.post {
                    callback(
                        Result.failure(error)
                    )
                }
            }
        }.start()
    }

    fun findBackupFile(
        accessToken: String,
        callback: (Result<String?>) -> Unit
    ) {
        Thread {
            try {
                val metadata = findBackupMetadataBlocking(accessToken)

                mainHandler.post {
                    callback(
                        Result.success(metadata?.fileId)
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    callback(
                        Result.failure(error)
                    )
                }
            }
        }.start()
    }

    fun findBackupMetadata(
        accessToken: String,
        callback: (Result<BackupMetadata?>) -> Unit
    ) {
        Thread {
            try {
                val metadata = findBackupMetadataBlocking(accessToken)

                mainHandler.post {
                    callback(
                        Result.success(metadata)
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    callback(
                        Result.failure(error)
                    )
                }
            }
        }.start()
    }

    private fun findBackupMetadataBlocking(
        accessToken: String
    ): BackupMetadata? {
        val encodedQuery =
            URLEncoder.encode(
                "name = 'Align Tuition Backup.db' and trashed = false",
                "UTF-8"
            )

        val encodedFields =
            URLEncoder.encode(
                "files(id,name,mimeType,modifiedTime)",
                "UTF-8"
            )

        val url =
            URL(
                "https://www.googleapis.com/drive/v3/files" +
                    "?q=$encodedQuery" +
                    "&spaces=appDataFolder" +
                    "&pageSize=10" +
                    "&fields=$encodedFields"
            )

        val connection =
            url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 60_000
            connection.readTimeout = 60_000

            connection.setRequestProperty(
                "Authorization",
                "Bearer $accessToken"
            )

            val responseCode =
                connection.responseCode

            if (responseCode !in 200..299) {
                val errorBody =
                    connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: "No error response."

                throw Exception(
                    "Google Drive HTTP $responseCode: $errorBody"
                )
            }

            val response =
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

            val json =
                org.json.JSONObject(response)

            val files =
                json.optJSONArray("files")
                    ?: return null

            if (files.length() == 0) {
                return null
            }

            val file = files.getJSONObject(0)

            val fileId = file.getString("id")
            val modifiedTime = file.optString("modifiedTime")

            if (modifiedTime.isBlank()) {
                throw IllegalStateException(
                    "Google Drive backup modifiedTime is unavailable."
                )
            }

            return BackupMetadata(
                fileId = fileId,
                modifiedTime = modifiedTime
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads the Drive backup into the device Downloads folder.
     * This method never restores or replaces the app database.
     */
    fun downloadBackupToDownloads(
        accessToken: String,
        callback: (Result<String>) -> Unit
    ) {
        Thread {
            var tempFile: File? = null
            var destinationUri: android.net.Uri? = null

            try {
                if (android.os.Build.VERSION.SDK_INT < 29) {
                    throw IllegalStateException(
                        "Saving directly to Downloads requires Android 10 or later."
                    )
                }

                val metadata = findBackupMetadataBlocking(accessToken)
                    ?: throw NoBackupFoundException()

                tempFile = File(
                    context.cacheDir,
                    "tuition_export_${System.currentTimeMillis()}.tmp"
                )

                downloadBackup(metadata.fileId, accessToken, tempFile)

                val values = android.content.ContentValues().apply {
                    put(
                        android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                        "Align Tuition Backup.db"
                    )
                    put(
                        android.provider.MediaStore.MediaColumns.MIME_TYPE,
                        "application/octet-stream"
                    )
                    put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }

                destinationUri = context.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                ) ?: throw IllegalStateException(
                    "Could not create the backup file in Downloads."
                )

                val output = context.contentResolver.openOutputStream(destinationUri!!)
                    ?: throw IllegalStateException(
                        "Could not open the Downloads file for writing."
                    )

                tempFile.inputStream().use { input ->
                    output.use { stream -> input.copyTo(stream) }
                }

                val publishedValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(
                    destinationUri!!,
                    publishedValues,
                    null,
                    null
                )

                mainHandler.post {
                    callback(
                        Result.success(
                            "Backup downloaded to Downloads/Align Tuition Backup.db"
                        )
                    )
                }
            } catch (error: Exception) {
                destinationUri?.let { uri ->
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                }

                mainHandler.post {
                    callback(Result.failure(error))
                }
            } finally {
                tempFile?.delete()
            }
        }.start()
    }


    /**
     * Reads a user-selected local backup, validates it, and stages it for
     * replaceValidatedDatabase(). It does not replace the live database.
     */
    fun validateLocalBackup(
        uri: android.net.Uri,
        callback: (Result<Unit>) -> Unit
    ) {
        Thread {
            val incoming = File(
                context.cacheDir,
                "tuition_local_restore_${System.currentTimeMillis()}.db"
            )
            val validated = File(
                context.cacheDir,
                "tuition_restore_validated.db"
            )

            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open the selected backup file.")

                input.use { source ->
                    FileOutputStream(incoming).use { output ->
                        source.copyTo(output)
                    }
                }

                if (!incoming.exists() || incoming.length() == 0L) {
                    throw IllegalStateException("The selected backup file is empty.")
                }

                // Validate before making this file eligible for restore.
                validateDatabase(incoming)

                incoming.copyTo(validated, overwrite = true)

                mainHandler.post { callback(Result.success(Unit)) }
            } catch (error: Exception) {
                incoming.delete()
                validated.delete()
                mainHandler.post { callback(Result.failure(error)) }
            } finally {
                incoming.delete()
            }
        }.start()
    }

    class NoBackupFoundException : Exception(
        "No Align-Tuition backup found on Google Drive."
    )

    private fun downloadBackup(
        fileId: String,
        accessToken: String,
        destination: File
    ) {
        val encodedFileId =
            URLEncoder.encode(
                fileId,
                "UTF-8"
            )

        val connection =
            URL(
                "$DRIVE_FILE_URL/$encodedFileId?alt=media"
            ).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 60_000
            connection.readTimeout = 60_000

            connection.setRequestProperty(
                "Authorization",
                "Bearer $accessToken"
            )

            val responseCode =
                connection.responseCode

            if (responseCode !in 200..299) {
                val errorBody =
                    connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: "No error response."

                throw Exception(
                    "Google Drive HTTP $responseCode: $errorBody"
                )
            }

            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer =
                        ByteArray(16 * 1024)

                    while (true) {
                        val count =
                            input.read(buffer)

                        if (count <= 0) {
                            break
                        }

                        output.write(
                            buffer,
                            0,
                            count
                        )
                    }
                }
            }

            if (
                !destination.exists() ||
                destination.length() == 0L
            ) {
                throw IllegalStateException(
                    "Downloaded backup is empty."
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun validateDatabase(
        file: File
    ) {
        val database =
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )

        try {
            database.rawQuery(
                "PRAGMA integrity_check",
                null
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    throw IllegalStateException(
                        "Database integrity check returned no result."
                    )
                }

                val result =
                    cursor.getString(0)

                if (result != "ok") {
                    throw IllegalStateException(
                        "Backup database failed integrity check: $result"
                    )
                }
            }

            val requiredTables = listOf(
                "students",
                "fee_records",
                "fee_payments",
                "app_profile"
            )
            for (table in requiredTables) {
                database.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                    arrayOf(table)
                ).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        throw IllegalStateException(
                            "Backup is not a valid Align-Tuition database: missing $table table."
                        )
                    }
                }
            }

            database.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getInt(0) != 7) {
                    throw IllegalStateException(
                        "Backup database version is not compatible with Align-Tuition version 7."
                    )
                }
            }
        } finally {
            database.close()
        }
    }

    /**
     * Replace the app database only after download and validation succeeded.
     * Call TuitionDatabase.closeInstance() before invoking this method.
     * A timestamped safety copy is retained under filesDir/database-backups.
     */
    fun replaceValidatedDatabase(): File {
        val source = getValidatedBackupFile()
        validateDatabase(source)

        val target = context.getDatabasePath("align_tuition.db")
        val parent = target.parentFile
            ?: throw IllegalStateException("Database directory is unavailable.")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("Could not create database directory.")
        }

        val safetyDir = File(context.filesDir, "database-backups")
        if (!safetyDir.exists() && !safetyDir.mkdirs()) {
            throw IllegalStateException("Could not create safety backup directory.")
        }

        val stamp = java.text.SimpleDateFormat(
            "yyyyMMdd-HHmmss", java.util.Locale.US
        ).format(java.util.Date())
        val safetyCopy = File(safetyDir, "align_tuition-before-restore-$stamp.db")
        val staged = File(parent, "align_tuition.restore-staged.db")
        val rollback = File(parent, "align_tuition.restore-rollback.db")

        if (target.exists()) {
            target.copyTo(safetyCopy, overwrite = false)
            if (safetyCopy.length() == 0L) {
                safetyCopy.delete()
                throw IllegalStateException("Safety copy is empty; restore cancelled.")
            }
        }

        staged.delete()
        rollback.delete()
        source.copyTo(staged, overwrite = true)
        validateDatabase(staged)

        var movedOriginal = false
        try {
            if (target.exists()) {
                if (!target.renameTo(rollback)) {
                    throw IllegalStateException("Could not stage the existing database.")
                }
                movedOriginal = true
            }
            if (!staged.renameTo(target)) {
                throw IllegalStateException("Could not install the restored database.")
            }
            validateDatabase(target)
            rollback.delete()
            return safetyCopy
        } catch (error: Exception) {
            target.delete()
            if (movedOriginal && rollback.exists() && !rollback.renameTo(target)) {
                throw IllegalStateException(
                    "Restore failed and automatic rollback failed. Safety copy: ${safetyCopy.absolutePath}",
                    error
                )
            }
            throw error
        } finally {
            staged.delete()
        }
    }

    fun getValidatedBackupFile(): File {
        val file =
            File(
                context.cacheDir,
                "tuition_restore_validated.db"
            )

        if (!file.exists()) {
            throw IllegalStateException(
                "Validated backup file does not exist."
            )
        }

        return file
    }
}
