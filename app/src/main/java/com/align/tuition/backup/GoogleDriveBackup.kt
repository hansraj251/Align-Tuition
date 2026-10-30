package com.align.tuition.backup

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

class GoogleDriveBackup(
    context: Context
) {

    companion object {
        private const val PREFERENCES_NAME = "tuition_backup"
        private const val FILE_ID_KEY = "google_drive_file_id"
        private const val BACKUP_FILE_NAME = "Align Tuition Backup.db"
        private const val MIME_TYPE = "application/x-sqlite3"
        private const val DRIVE_UPLOAD_URL =
            "https://www.googleapis.com/upload/drive/v3/files"
        private const val DRIVE_FILE_URL =
            "https://www.googleapis.com/drive/v3/files"
    }

    private val context = context.applicationContext
    private val preferences =
        this.context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
    private val mainHandler = Handler(Looper.getMainLooper())

    fun sync(
        database: SupportSQLiteDatabase,
        accessToken: String,
        callback: (Result<Unit>) -> Unit
    ) {
        Thread {
            var snapshot: File? = null

            try {
                snapshot = createDatabaseSnapshot(database)

                val fileIdOnCurrentAccount =
                    findExistingBackupFileId(accessToken)

                val fileId =
                    if (fileIdOnCurrentAccount == null) {
                        uploadNewFile(snapshot, accessToken)
                    } else {
                        updateExistingFile(
                            fileIdOnCurrentAccount,
                            snapshot,
                            accessToken
                        )
                        fileIdOnCurrentAccount
                    }

                preferences.edit()
                    .putString(
                        FILE_ID_KEY,
                        fileId
                    )
                    .apply()

                mainHandler.post {
                    callback(
                        Result.success(Unit)
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    callback(
                        Result.failure(error)
                    )
                }
            } finally {
                snapshot?.delete()
            }
        }.start()
    }

    private fun createDatabaseSnapshot(
        database: SupportSQLiteDatabase
    ): File {
        val snapshot =
            File(
                context.cacheDir,
                "tuition_backup_snapshot.db"
            )

        if (snapshot.exists()) {
            snapshot.delete()
        }

        val escapedPath =
            snapshot.absolutePath.replace(
                "'",
                "''"
            )

        database.execSQL(
            "VACUUM INTO '$escapedPath'"
        )

        if (!snapshot.exists() || snapshot.length() == 0L) {
            throw IllegalStateException(
                "Database snapshot could not be created."
            )
        }

        return snapshot
    }

    /**
     * Looks up the backup only inside the appDataFolder belonging to the
     * account represented by this access token.
     */
    private fun findExistingBackupFileId(accessToken: String): String? {
        val encodedQuery = URLEncoder.encode(
            "name = '$BACKUP_FILE_NAME' and trashed = false",
            "UTF-8"
        )
        val url = URL(
            "$DRIVE_FILE_URL?q=$encodedQuery" +
                "&spaces=appDataFolder" +
                "&pageSize=10" +
                "&fields=files(id,name,modifiedTime)"
        )
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 60_000
            connection.readTimeout = 60_000
            connection.setRequestProperty(
                "Authorization",
                "Bearer $accessToken"
            )

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = try {
                    connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: "No error response."
                } catch (_: Exception) {
                    "Unable to read error response."
                }
                throw IllegalStateException(
                    "Drive backup lookup failed: HTTP $responseCode: $errorBody"
                )
            }

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }
            val files = JSONObject(response).optJSONArray("files")
                ?: return null

            if (files.length() == 0) return null
            return files.getJSONObject(0).optString("id").takeIf {
                it.isNotBlank()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadNewFile(
        snapshot: File,
        accessToken: String
    ): String {
        val boundary =
            "LedgerBackupBoundary${System.currentTimeMillis()}"

        val metadata =
            """
            {
              "name": "$BACKUP_FILE_NAME",
              "mimeType": "$MIME_TYPE",
              "parents": ["appDataFolder"]
            }
            """.trimIndent()

        val connection =
            URL(
                "$DRIVE_UPLOAD_URL?uploadType=multipart"
            ).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 60_000
            connection.readTimeout = 60_000

            connection.setRequestProperty(
                "Authorization",
                "Bearer $accessToken"
            )

            connection.setRequestProperty(
                "Content-Type",
                "multipart/related; boundary=$boundary"
            )

            connection.outputStream.use { output ->

                output.write(
                    "--$boundary\r\n".toByteArray()
                )

                output.write(
                    "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                        .toByteArray()
                )

                output.write(
                    metadata.toByteArray()
                )

                output.write(
                    "\r\n--$boundary\r\n".toByteArray()
                )

                output.write(
                    "Content-Type: $MIME_TYPE\r\n\r\n"
                        .toByteArray()
                )

                snapshot.inputStream().use { input ->
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

                output.write(
                    "\r\n--$boundary--\r\n".toByteArray()
                )
            }

            val responseCode =
                connection.responseCode

            if (responseCode !in 200..299) {
                val errorBody = readError(connection)

                Log.e(
                    "GoogleDriveBackup",
                    "UPLOAD HTTP $responseCode: $errorBody"
                )

                throw IOExceptionWithResponse(
                    responseCode,
                    errorBody
                )
            }

            val response =
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

            return JSONObject(response)
                .getString("id")
        } finally {
            connection.disconnect()
        }
    }

    private fun updateExistingFile(
        fileId: String,
        snapshot: File,
        accessToken: String
    ) {
        val encodedFileId =
            URLEncoder.encode(
                fileId,
                "UTF-8"
            )

        val connection =
            URL(
                "$DRIVE_UPLOAD_URL/$encodedFileId?uploadType=media"
            ).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "PATCH"
            connection.doOutput = true
            connection.connectTimeout = 60_000
            connection.readTimeout = 60_000

            connection.setRequestProperty(
                "Authorization",
                "Bearer $accessToken"
            )

            connection.setRequestProperty(
                "Content-Type",
                MIME_TYPE
            )

            snapshot.inputStream().use { input ->
                connection.outputStream.use { output ->
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

            val responseCode =
                connection.responseCode

            if (responseCode !in 200..299) {
                val errorBody = readError(connection)

                Log.e(
                    "GoogleDriveBackup",
                    "UPLOAD HTTP $responseCode: $errorBody"
                )

                throw IOExceptionWithResponse(
                    responseCode,
                    errorBody
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readError(
        connection: HttpURLConnection
    ): String {
        return try {
            connection.errorStream
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: "No error response."
        } catch (_: Exception) {
            "Unable to read error response."
        }
    }

    private class IOExceptionWithResponse(
        code: Int,
        message: String
    ) : Exception(
        "Google Drive HTTP $code: $message"
    )
}
