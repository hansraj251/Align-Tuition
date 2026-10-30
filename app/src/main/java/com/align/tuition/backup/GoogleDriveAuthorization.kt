package com.align.tuition.backup

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope

class GoogleDriveAuthorization(
    context: Context
) {

    companion object {
        const val DRIVE_FILE_SCOPE =
            "https://www.googleapis.com/auth/" + "drive.file"
        const val DRIVE_APPDATA_SCOPE =
            "https://www.googleapis.com/auth/drive.appdata"
    }

    private val authorizationClient: AuthorizationClient =
        Identity.getAuthorizationClient(
            context
        )

    fun createAuthorizationRequest(
        forceAccountSelection: Boolean = false
    ): AuthorizationRequest {
        val builder = AuthorizationRequest
            .builder()
            .setRequestedScopes(
                listOf(
                    Scope(DRIVE_FILE_SCOPE),
                    Scope(DRIVE_APPDATA_SCOPE),
                    Scope("https://www.googleapis.com/auth/userinfo.email")
                )
            )

        if (forceAccountSelection) {
            builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        }

        return builder.build()
    }

    fun authorize(forceAccountSelection: Boolean = false) =
        authorizationClient.authorize(
            createAuthorizationRequest(forceAccountSelection)
        )
}
