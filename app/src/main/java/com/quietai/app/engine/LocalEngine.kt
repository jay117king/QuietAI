package com.quietai.app.engine

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.quietai.app.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalEngine(private val context: Context) : LlmEngine {
    override val displayName: String = "On-device"

    private var llmInference: LlmInference? = null

    override suspend fun load() = withContext(Dispatchers.IO) {
        val modelFile = ModelManager.modelFile(context)
        if (!modelFile.exists()) {
            throw IllegalStateException("Model file not found. Import a .task model first.")
        }

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(512)
            .build()

        llmInference = LlmInference.createFromOptions(context, options)
    }

    override suspend fun generate(transcript: String): String = withContext(Dispatchers.Default) {
        val engine = llmInference ?: throw IllegalStateException("Local engine not loaded")
        val prompt = "You are Quiet, a helpful and concise AI assistant. Conversation so far:\n$transcript\nAssistant:"
        engine.generateResponse(prompt)
    }

    override fun close() {
        llmInference?.close()
        llmInference = null
    }
}
