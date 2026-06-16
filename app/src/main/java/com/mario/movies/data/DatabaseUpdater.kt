package com.mario.movies.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import com.google.gson.annotations.SerializedName
import okhttp3.Request

data class RemoteDbInfo(
    @SerializedName("version")
    val version: String,
    @SerializedName("file_id")
    val file_id: String
)

class DatabaseUpdater(private val context: Context) {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val dbHelper = DatabaseHelper.getInstance(context)
    private val tokenManager = TokenManager(context)

    companion object {
        private const val CONFIG_URL = "https://raw.githubusercontent.com/jaixmario/database/refs/heads/main/database.json"
        private const val GRAPH_ROOT = "https://graph.microsoft.com/v1.0"
    }

    suspend fun checkAndUpdateDatabase(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Fetch remote config
            val remoteInfo = fetchRemoteConfig() 
            if (remoteInfo == null) {
                return@withContext false
            }
            
            // 2. Get local version
            val localVersion = dbHelper.getCurrentVersion()
            
            Log.d("DatabaseUpdater", "Local version: $localVersion, Remote version: ${remoteInfo.version}")
            
            if (localVersion == remoteInfo.version) {
                Log.d("DatabaseUpdater", "Database is up to date.")
                return@withContext true
            }

            // 3. Versions don't match, download new database
            Log.d("DatabaseUpdater", "New version found: ${remoteInfo.version}. Starting download...")
            val downloadUrl = getDownloadUrl(remoteInfo.file_id) 
            if (downloadUrl == null) {
                return@withContext false
            }
            
            val success = downloadAndReplaceDb(downloadUrl, remoteInfo.version)
            if (success) {
                Log.d("DatabaseUpdater", "Database updated to ${remoteInfo.version}")
            }
            return@withContext success
        } catch (e: Exception) {
            Log.e("DatabaseUpdater", "Error during update check", e)
            return@withContext false
        }
    }

    private fun fetchRemoteConfig(): RemoteDbInfo? {
        val request = Request.Builder().url(CONFIG_URL).build()
        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return null
                val root = gson.fromJson(json, JsonObject::class.java)
                val dbObj = root.getAsJsonObject("database")
                RemoteDbInfo(
                    version = dbObj.get("version").asString,
                    file_id = dbObj.get("file_id").asString
                )
            } else {
                Log.e("DatabaseUpdater", "Config fetch failed: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e("DatabaseUpdater", "Config fetch exception", e)
            null
        }
    }

    private suspend fun getDownloadUrl(fileId: String): String? {
        val token = tokenManager.getAccessToken() ?: return null
        val url = "$GRAPH_ROOT/me/drive/items/$fileId"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
            
        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string()
                val responseObj = gson.fromJson(json, JsonObject::class.java)
                if (responseObj.has("@microsoft.graph.downloadUrl")) {
                    responseObj.get("@microsoft.graph.downloadUrl").asString
                } else {
                    Log.e("DatabaseUpdater", "Download URL missing in response")
                    null
                }
            } else {
                Log.e("DatabaseUpdater", "Failed to get item info: ${response.code} ${response.message}")
                null
            }
        } catch (e: Exception) {
            Log.e("DatabaseUpdater", "OneDrive API exception", e)
            null
        }
    }

    private fun downloadAndReplaceDb(url: String, newVersion: String): Boolean {
        val request = Request.Builder().url(url).build()
        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val inputStream = response.body?.byteStream() ?: return false
                val replaced = dbHelper.replaceDatabaseFile(inputStream)
                if (replaced) {
                    dbHelper.updateDatabaseVersion(newVersion)
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e("DatabaseUpdater", "Download/Replace exception", e)
            false
        }
    }
}
