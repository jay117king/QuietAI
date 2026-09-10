package com.quietai.app.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class CloudEngine : LlmEngine {
    override val displayName: String = "Cloud (free)"

    // Increased timeout for slow free endpoints
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun load() { /* nothing to load */ }

    override suspend fun generate(transcript: String): String = withContext(Dispatchers.IO) {
        try {
            return@withContext askPollinations(transcript)
        } catch (e: Exception) {
            Log.e("CloudEngine", "Pollinations failed", e)
            throw IOException("Free endpoint is down or rate-limited. Wait a minute and retry.")
        }
    }

    private fun askPollinations(transcript: String): String {
        // Extract only the last user message to avoid URL length limits on GET requests
        val lastUserLine = transcript.lines().lastOrNull { it.startsWith("User: ") }
            ?.removePrefix("User: ") ?: transcript
        
        val prompt = "You are Quiet, a helpful assistant. Answer concisely: $lastUserLine"
        val url = "https://text.pollinations.ai/" + URLEncoder.encode(prompt, "UTF-8")
        
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IOException("Empty response")
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
    }
}
