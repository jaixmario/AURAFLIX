package com.mario.movies.data

import android.content.Context
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.InputStreamReader

data class TokenData(
    val access_token: String,
    val refresh_token: String,
    @SerializedName("expires_in")
    val expires_in: Long = 3600,
    @SerializedName("expires_at", alternate = ["expire_at"])
    val expire_at: Long? // Timestamp when it expires
)

data class Links(
    val downloadLink: String?,
    val shareLink: String?
)

class TokenManager(private val context: Context) {
    private val client = OkHttpClient()
    private val gson = Gson()
    
    companion object {
        private const val CLIENT_ID = "59790544-ca0c-4b77-b338-26ff9d1b676f"
        private const val TENANT_ID = "0fd666e8-0b3d-41ea-a5ef-1c509130bd94"
        private const val GRAPH_ROOT = "https://graph.microsoft.com/v1.0"
        
        private const val DEFAULT_TOKEN_FILE = "user_token.json"
        private const val MARIO_TOKEN_FILE = "user_token_mario.json"
        
        private const val ASSETS_DEFAULT_TOKEN = "user_token.json"
        private const val ASSETS_MARIO_TOKEN = "user_token2.json"
        
        private const val PREFS_NAME = "dev_prefs"
        private const val KEY_DEV_MODE = "developer_mode"

        private const val GITHUB_TOKEN_URL = "https://raw.githubusercontent.com/jaixmario/token-db/refs/heads/main/user_token.json"
        private const val GITHUB_TOKEN_MARIO_URL = "https://raw.githubusercontent.com/jaixmario/token-db/refs/heads/main/user_token2.json"
    }

    private fun isDevMode(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DEV_MODE, false)
    }
    
    fun setDevMode(enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DEV_MODE, enabled).apply()
    }
    
    private suspend fun showToast(message: String) {
        if (!isDevMode()) return
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun getAccessToken(isMario: Boolean = false): String? = withContext(Dispatchers.IO) {
        var tokenData = loadToken(isMario)
        if (tokenData == null) {
            showToast("Token missing, trying to update from Server...")
            if (updateTokensFromGithub()) {
                tokenData = loadToken(isMario)
            }
        }
        
        if (tokenData == null) {
            showToast("Error: No token data found (${if(isMario) "Server" else "Default"})")
            return@withContext null
        }
        
        val finalTokenData = tokenData!!
        
        val currentTime = System.currentTimeMillis() / 1000
        val expiryTime = finalTokenData.expire_at ?: (currentTime + finalTokenData.expires_in)
        
        if (currentTime > expiryTime - 300) {
            showToast("Refreshing ${if(isMario) "Server" else "Default"} token...")
            val refreshed = refreshToken(finalTokenData.refresh_token, isMario)
            if (refreshed != null) return@withContext refreshed
            
            // If refresh failed, try updating from SERVER as a fallback
            showToast("Refresh failed, trying update from Server...")
            if (updateTokensFromGithub()) {
                val newTokenData = loadToken(isMario)
                if (newTokenData != null) {
                    val newExpiry = newTokenData.expire_at ?: (currentTime + newTokenData.expires_in)
                    if (currentTime < newExpiry - 300) {
                        return@withContext newTokenData.access_token
                    }
                    // If the new token from github also needs refresh, try it once
                    return@withContext refreshToken(newTokenData.refresh_token, isMario)
                }
            }
        }
        
        return@withContext finalTokenData.access_token
    }
    
    private suspend fun loadToken(isMario: Boolean): TokenData? {
        val fileName = if (isMario) MARIO_TOKEN_FILE else DEFAULT_TOKEN_FILE
        val assetsName = if (isMario) ASSETS_MARIO_TOKEN else ASSETS_DEFAULT_TOKEN
        
        val file = File(context.filesDir, fileName)
        if (file.exists()) {
             try {
                 return gson.fromJson(file.readText(), TokenData::class.java)
             } catch (e: Exception) {}
        }
        
        return try {
            val inputStream = context.assets.open(assetsName)
            val reader = InputStreamReader(inputStream)
            val tokenData = gson.fromJson(reader, TokenData::class.java)
            initializeToken(tokenData, isMario)
        } catch (e: Exception) {
            if (isMario) showToast("Server token file not found in assets")
            null
        }
    }

    private fun initializeToken(tokenData: TokenData, isMario: Boolean): TokenData {
        val currentTime = System.currentTimeMillis() / 1000
        val updatedToken = if (tokenData.expire_at == null) {
            tokenData.copy(expire_at = currentTime + tokenData.expires_in)
        } else tokenData
        
        saveToken(updatedToken, isMario)
        return updatedToken
    }
    
    private fun saveToken(tokenData: TokenData, isMario: Boolean) {
        val fileName = if (isMario) MARIO_TOKEN_FILE else DEFAULT_TOKEN_FILE
        val file = File(context.filesDir, fileName)
        file.writeText(gson.toJson(tokenData))
    }
    
    private suspend fun refreshToken(refreshToken: String, isMario: Boolean): String? {
        val url = "https://login.microsoftonline.com/$TENANT_ID/oauth2/v2.0/token"
        
        val formBody = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scope", "https://graph.microsoft.com/.default")
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
            
        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .build()
            
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = response.body?.string() ?: return null
                    val newToken = gson.fromJson(json, TokenData::class.java)
                    
                    val currentTime = System.currentTimeMillis() / 1000
                    val updatedToken = newToken.copy(expire_at = currentTime + newToken.expires_in)
                    
                    saveToken(updatedToken, isMario)
                    showToast("${if(isMario) "Server" else "Default"} token refreshed")
                    return updatedToken.access_token
                } else {
                    showToast("Refresh failed (${if(isMario) "Server" else "Default"}): ${response.code}")
                }
            }
        } catch (e: Exception) {
            showToast("Refresh exception: ${e.message}")
        }
        return null
    }
    
    suspend fun updateTokensFromGithub(): Boolean = withContext(Dispatchers.IO) {
        val successDefault = downloadAndSaveToken(GITHUB_TOKEN_URL, false)
        val successMario = downloadAndSaveToken(GITHUB_TOKEN_MARIO_URL, true)
        successDefault || successMario
    }

    private suspend fun downloadAndSaveToken(url: String, isMario: Boolean): Boolean {
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = response.body?.string() ?: return false
                    val tokenData = gson.fromJson(json, TokenData::class.java)
                    initializeToken(tokenData, isMario)
                    true
                } else {
                    showToast("Server download failed: ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            showToast("Server download error: ${e.message}")
            false
        }
    }

    suspend fun getLinks(originalItemId: String): Links? = withContext(Dispatchers.IO) {
        val isMario = originalItemId.startsWith("MARIO")
        val itemId = if (isMario) originalItemId.removePrefix("MARIO") else originalItemId
        
        showToast("Generating link for ${if(isMario) "Server" else "Default"} ID")
        
        val token = getAccessToken(isMario)
        if (token == null) {
            showToast("Failed: No token")
            return@withContext null
        }
        
        var downloadLink: String? = null
        var shareLink: String? = null
        
        try {
            val url = "$GRAPH_ROOT/me/drive/items/$itemId"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string()
                val responseObj = gson.fromJson(json, JsonObject::class.java)
                if (responseObj.has("@microsoft.graph.downloadUrl")) {
                    downloadLink = responseObj.get("@microsoft.graph.downloadUrl").asString
                }
            } else {
                showToast("API Error ${response.code}")
            }
        } catch (e: Exception) {
            showToast("API Exception: ${e.message}")
        }
        
        try {
            val url = "$GRAPH_ROOT/me/drive/items/$itemId/createLink"
            val jsonBody = """
                {
                    "type": "view",
                    "scope": "anonymous"
                }
            """.trimIndent()
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
                
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string()
                val responseObj = gson.fromJson(json, JsonObject::class.java)
                if (responseObj.has("link")) {
                    val linkObj = responseObj.getAsJsonObject("link")
                    if (linkObj.has("webUrl")) {
                        shareLink = linkObj.get("webUrl").asString
                    }
                }
            }
        } catch (e: Exception) {}
        
        if (downloadLink != null || shareLink != null) {
            showToast("Links generated!")
            return@withContext Links(downloadLink, shareLink)
        }
        
        showToast("Failed to generate links")
        return@withContext null
    }
}
