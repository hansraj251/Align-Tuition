package com.align.tuition.backup

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Binds Premium to the email verified by the current Google Drive OAuth token.
 * Only the SHA-256 account ID is passed to Google Play Billing.
 */
object DriveAccountBinding {
    private const val PREFS = "tuition_drive_account_binding"
    private const val KEY_EMAIL = "drive_account_email"
    private val executor = Executors.newSingleThreadExecutor()

    fun currentAccountId(context: Context): String? {
        val email = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EMAIL, null)
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return null

        if (email.isBlank() || !email.contains("@")) return null
        return sha256(email)
    }

    fun capture(
        context: Context,
        accessToken: String?,
        onComplete: (() -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Fail closed while verifying; never use a previously connected account ID.
        prefs.edit().remove(KEY_EMAIL).commit()

        if (accessToken.isNullOrBlank()) {
            prefs.edit().remove(KEY_EMAIL).commit()
            onComplete?.invoke()
            return
        }

        executor.execute {
            var verifiedEmail: String? = null
            var connection: HttpURLConnection? = null

            try {
                connection = URL(
                    "https://www.googleapis.com/oauth2/v2/userinfo"
                ).openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $accessToken"
                )

                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException(
                        "Google account identity request failed: HTTP ${connection.responseCode}"
                    )
                }

                val body = connection.inputStream.bufferedReader().use {
                    it.readText()
                }

                val email = JSONObject(body)
                    .optString("email")
                    .trim()
                    .lowercase(Locale.ROOT)

                if (email.isBlank() || !email.contains("@")) {
                    throw IllegalStateException(
                        "Google did not return a valid account email."
                    )
                }

                verifiedEmail = email
            } catch (error: Exception) {
                Log.w(
                    "DriveAccountBinding",
                    "Could not verify the Google Drive account",
                    error
                )
            } finally {
                connection?.disconnect()

                // Never retain an old account if verification fails.
                if (verifiedEmail == null) {
                    prefs.edit().remove(KEY_EMAIL).commit()
                } else {
                    prefs.edit().putString(KEY_EMAIL, verifiedEmail).commit()
                }

                onComplete?.invoke()
            }
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
