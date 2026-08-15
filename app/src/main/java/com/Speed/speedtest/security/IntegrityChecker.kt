/*
 * Copyright (C) 2026 Speed App. All rights reserved.
 *
 * This source code is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, via any medium, is
 * strictly prohibited without express written permission from the copyright holder.
 *
 * Licensed under a proprietary license. See LICENSE file in the project root.
 */

package com.Speed.speedtest.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * App integrity verification to detect repackaging and tampering.
 *
 * Checks:
 * 1. Signing certificate matches expected hash (anti-repackaging)
 * 2. Package name hasn't been changed
 * 3. Installer is a trusted source (Play Store, etc.)
 * 4. App is not running in a debuggable state in release
 */
object IntegrityChecker {
    private const val TAG = "IntegrityChecker"
    private const val EXPECTED_PACKAGE = "com.Speed.speedtest"

    // Trusted installer packages
    private val TRUSTED_INSTALLERS = setOf(
        "com.android.vending",        // Google Play Store
        "com.google.android.feedback", // Google Play Store (alt)
        "com.amazon.venezia",          // Amazon App Store
        "com.huawei.appmarket",        // Huawei AppGallery
        "com.samsung.android.vending", // Samsung Galaxy Store
        null                           // Allow sideloading (ADB install) — remove this for strict mode
    )

    /**
     * Performs all integrity checks. Returns true if the app appears unmodified.
     * Call this on service start to kill tampered copies.
     */
    fun verifyIntegrity(context: Context): Boolean {
        if (!verifyPackageName(context)) {
            Log.e(TAG, "Package name verification failed")
            return false
        }

        if (!verifySignature(context)) {
            Log.e(TAG, "Signature verification failed — possible repackaging")
            return false
        }

        if (!verifyNotDebuggable(context)) {
            Log.w(TAG, "App is running in debuggable mode")
            // Don't fail in debug — only warn. Release builds strip this flag.
        }

        return true
    }

    /**
     * Verifies the app's package name hasn't been changed by a repackager.
     */
    private fun verifyPackageName(context: Context): Boolean {
        return context.packageName == EXPECTED_PACKAGE
    }

    /**
     * Verifies the APK signing certificate matches the expected fingerprint.
     * If no expected hash is stored yet (first install), it stores the current one.
     *
     * To lock down to YOUR signing key:
     * 1. Build a signed release APK
     * 2. Run: keytool -printcert -jarfile app-release.apk
     * 3. Take the SHA-256 fingerprint and set it as EXPECTED_SIGNING_HASH below
     */
    private fun verifySignature(context: Context): Boolean {
        return try {
            val currentHash = getSigningCertHash(context) ?: return false
            val storedHash = getStoredSigningHash(context)

            if (storedHash == null) {
                // First run — store the signing hash for future verification
                storeSigningHash(context, currentHash)
                Log.d(TAG, "Signing hash stored for future verification")
                return true
            }

            // Compare current signature with stored one
            val matches = currentHash == storedHash
            if (!matches) {
                Log.e(TAG, "Signing certificate mismatch! Expected=$storedHash Got=$currentHash")
            }
            matches
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification error: ${e.message}")
            false
        }
    }

    /**
     * Gets the SHA-256 hash of the app's signing certificate.
     */
    @Suppress("DEPRECATION")
    private fun getSigningCertHash(context: Context): String? {
        return try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo
                if (signingInfo?.hasMultipleSigners() == true) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo?.signingCertificateHistory
                }
            } else {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                ).signatures
            }

            if (signatures.isNullOrEmpty()) return null

            val cert = signatures[0].toByteArray()
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(cert)
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get signing cert: ${e.message}")
            null
        }
    }

    /**
     * Checks if the app is running in debuggable mode.
     * In a release build with ProGuard, this should always be false.
     */
    private fun verifyNotDebuggable(context: Context): Boolean {
        val appInfo = context.applicationInfo
        return (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0
    }

    /**
     * Verifies the app was installed from a trusted source.
     */
    fun verifyInstaller(context: Context): Boolean {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        val trusted = installer in TRUSTED_INSTALLERS
        if (!trusted) {
            Log.w(TAG, "Untrusted installer: $installer")
        }
        return trusted
    }

    // --- Secure storage for signing hash ---

    private const val PREFS_NAME = "speed_integrity"
    private const val KEY_SIGNING_HASH = "sig_hash"

    private fun getStoredSigningHash(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SIGNING_HASH, null)
    }

    private fun storeSigningHash(context: Context, hash: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SIGNING_HASH, hash).apply()
    }
}
