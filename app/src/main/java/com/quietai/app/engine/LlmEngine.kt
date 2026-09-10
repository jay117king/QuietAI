package com.quietai.app.engine

interface LlmEngine {
    val displayName: String
    suspend fun load()
    suspend fun generate(transcript: String): String
    fun close()
}
