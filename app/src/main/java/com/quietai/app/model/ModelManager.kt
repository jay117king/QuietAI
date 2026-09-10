package com.quietai.app.model

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object ModelManager {
    private const val MODEL_FILE_NAME = "model.task"

    fun modelFile(context: Context): File = File(context.filesDir, MODEL_FILE_NAME)

    fun isReady(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.length() > 100_000_000 // sanity check: real model is 100MB+
    }

    /** Copy a user-picked file into private storage. */
    fun importPickedFile(context: Context, sourceUri: Uri): File {
        val dest = modelFile(context)
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not open picked file")
        return dest
    }
}
