package com.whixprs.phonelock

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Thin wrapper around BiometricPrompt (fingerprint first, device PIN as a fallback on Android 11+). */
object Auth {

    private val allowCredentialFallback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private val authenticators: Int =
        if (allowCredentialFallback) BIOMETRIC_WEAK or DEVICE_CREDENTIAL else BIOMETRIC_WEAK

    fun canAuthenticate(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (code: Int, message: CharSequence) -> Unit,
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errorCode, errString)
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(false)
            .apply { if (!allowCredentialFallback) setNegativeButtonText(activity.getString(R.string.cancel)) }
            .build()
        prompt.authenticate(info)
    }

    /** Errors that mean the user deliberately backed out. */
    fun isUserCancel(code: Int): Boolean = code == BiometricPrompt.ERROR_USER_CANCELED ||
        code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
        code == BiometricPrompt.ERROR_CANCELED
}
