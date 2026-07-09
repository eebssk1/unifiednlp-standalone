/*
 * SPDX-FileCopyrightText: 2020, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.nlp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

class NlpBackendInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        Log.d(TAG, "Initializing NLP backends...")
        initBackends(ACTION_LOCATION_BACKEND, PREF_LOCATION_BACKENDS)
        initBackends(ACTION_GEOCODER_BACKEND, PREF_GEOCODER_BACKENDS)
        return true
    }

    private fun initBackends(action: String, prefKey: String) {
        val prefs = context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) ?: return
        if (!prefs.getStringSetCompat(prefKey).isNullOrEmpty()) {
            Log.d(TAG, "$prefKey already configured, skipping")
            return
        }
        val backends = discoverBackends(action)
        if (backends.isNotEmpty()) {
            Log.d(TAG, "Auto-enabling ${backends.size} backends for $prefKey")
            prefs.edit().putStringSetCompat(prefKey, backends).apply()
        }
    }

    private fun discoverBackends(action: String): Set<String> {
        val context = context ?: return emptySet()
        val intent = Intent(action)
        val resolveInfos = context.packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
        return resolveInfos.mapNotNull {
            val serviceInfo = it.serviceInfo
            val pkgName = serviceInfo.packageName
            val className = serviceInfo.name
            val sig = firstSignatureDigest(context, pkgName, "SHA-256")
            if (sig != null) "$pkgName/$className/$sig" else "$pkgName/$className"
        }.toSet()
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        MatrixCursor(arrayOf("initialized"))

    override fun insert(uri: Uri, values: ContentValues?): Uri? = uri
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String = "vnd.android.cursor.item/nlp_backend_init"

    companion object {
        private const val TAG = "NlpBackendInit"
        private const val PREFERENCES_NAME = "unified_nlp"
        private const val PREF_LOCATION_BACKENDS = "location_backends"
        private const val PREF_GEOCODER_BACKENDS = "geocoder_backends"
        private const val ACTION_LOCATION_BACKEND = "org.microg.nlp.LOCATION_BACKEND"
        private const val ACTION_GEOCODER_BACKEND = "org.microg.nlp.GEOCODER_BACKEND"

        private fun SharedPreferences.getStringSetCompat(key: String): Set<String>? {
            try {
                val res = getStringSet(key, null)
                if (res != null) return res.filter { it.isNotEmpty() }.toSet()
            } catch (ignored: Exception) {}
            try {
                val str = getString(key, null)
                if (str != null) return str.split("\\|".toRegex()).filter { it.isNotEmpty() }.toSet()
            } catch (ignored: Exception) {}
            return null
        }

        private fun SharedPreferences.Editor.putStringSetCompat(key: String, values: Set<String>): SharedPreferences.Editor {
            return putStringSet(key, values.filter { it.isNotEmpty() }.toSet())
        }

        @Suppress("DEPRECATION")
        fun firstSignatureDigest(context: Context, packageName: String?, algorithm: String): String? {
            if (packageName == null) return null
            val packageManager = context.packageManager
            val info: PackageInfo?
            try {
                info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            } catch (e: PackageManager.NameNotFoundException) {
                return null
            }
            if (info?.signatures?.isNotEmpty() == true) {
                for (sig in info.signatures) {
                    digest(sig.toByteArray(), algorithm)?.let { return it }
                }
            }
            return null
        }

        private fun digest(bytes: ByteArray, algorithm: String): String? {
            try {
                val md = MessageDigest.getInstance(algorithm)
                val digest = md.digest(bytes)
                val sb = StringBuilder(2 * digest.size)
                for (b in digest) {
                    sb.append(String.format("%02x", b))
                }
                return sb.toString()
            } catch (e: NoSuchAlgorithmException) {
                return null
            }
        }
    }
}
