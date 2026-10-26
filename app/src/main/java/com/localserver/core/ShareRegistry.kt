package com.localserver.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ShareRoot(
    val id: String,
    val name: String,
    val uriString: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isAccessible: Boolean = true
) {
    val uri: Uri get() = Uri.parse(uriString)
}

class ShareRegistry(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _shares = MutableStateFlow<List<ShareRoot>>(emptyList())
    val shares: StateFlow<List<ShareRoot>> = _shares.asStateFlow()

    init {
        loadShares()
    }

    @Synchronized
    fun addShare(treeUri: Uri, customName: String? = null): ShareRoot? {
        val contentResolver = context.contentResolver

        // Persist permissions across reboots
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(treeUri, takeFlags)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to take persistable URI permission: ${e.message}")
        }

        val docFile = DocumentFile.fromTreeUri(context, treeUri)
        val name = customName ?: docFile?.name ?: "Shared Folder"
        val id = UUID.randomUUID().toString().substring(0, 8)

        val newShare = ShareRoot(
            id = id,
            name = name,
            uriString = treeUri.toString()
        )

        val currentList = _shares.value.toMutableList()
        currentList.removeAll { it.uriString == treeUri.toString() }
        currentList.add(newShare)
        saveShares(currentList)
        _shares.value = currentList
        return newShare
    }

    @Synchronized
    fun removeShare(id: String) {
        val share = _shares.value.find { it.id == id } ?: return
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(share.uri, flags)
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing persistable permission: ${e.message}")
        }

        val updated = _shares.value.filter { it.id != id }
        saveShares(updated)
        _shares.value = updated
    }

    fun isShareAccessible(share: ShareRoot): Boolean {
        return try {
            val hasPersistedPermission = context.contentResolver.persistedUriPermissions.any {
                it.uri == share.uri && it.isReadPermission
            }
            val doc = DocumentFile.fromTreeUri(context, share.uri)
            hasPersistedPermission && doc != null && doc.canRead()
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun getShare(id: String): ShareRoot? {
        val share = _shares.value.find { it.id == id } ?: return null
        val accessible = isShareAccessible(share)
        return if (share.isAccessible != accessible) {
            share.copy(isAccessible = accessible)
        } else {
            share
        }
    }

    private fun loadShares() {
        val raw = prefs.getString(KEY_SHARES, null) ?: return
        val list = mutableListOf<ShareRoot>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val rawShare = ShareRoot(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    uriString = obj.getString("uriString"),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
                list.add(rawShare.copy(isAccessible = isShareAccessible(rawShare)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading shares from preferences", e)
        }
        _shares.value = list
    }

    private fun saveShares(list: List<ShareRoot>) {
        try {
            val array = JSONArray()
            for (item in list) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("uriString", item.uriString)
                    put("createdAt", item.createdAt)
                }
                array.put(obj)
            }
            prefs.edit().putString(KEY_SHARES, array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving shares to preferences", e)
        }
    }

    companion object {
        private const val TAG = "ShareRegistry"
        private const val PREFS_NAME = "local_media_shares_prefs"
        private const val KEY_SHARES = "shared_folders_list"
    }
}
