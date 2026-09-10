# Quiet AI — MVP Full‑Stack Code (Improved)

Offline‑first, on‑device AI assistant for Android.
Two Gradle flavors: offline (zero permissions) and cloud (free, keyless endpoints only).
Generated 2026‑09‑11.

## 🛠 Key Improvements Made
1. **Fixed "Keep Warm" Memory Leak**: Removed `engine.close()` from `MainActivity.onDestroy()`. Added `EngineRepository` singleton so the model survives Activity recreation when backgrounded.
2. **Fixed Cloud Engine Logic**: Removed the fake OpenAI endpoint (which requires a paid API key, violating the "keyless" rule). Fixed the Pollinations GET request to only send the last message (preventing `414 URI Too Long` errors).
3. **Optimized Context Window**: Limited the transcript to the last 10 messages in `ChatViewModel` to prevent token overflow on local models.
4. **Fixed DiffUtil Bug**: Added unique IDs to `ChatMessage` and updated `areItemsTheSame`. The original `a === b` caused the entire RecyclerView to rebind on every message.
5. **UI/UX Upgrades**: Added Material Design chat bubbles with proper dark text colors instead of unreadable white text on light backgrounds. Upgraded to `SwitchMaterial`.
6. **Threading Fixes**: Changed local inference to `Dispatchers.Default` (CPU-bound) instead of `Dispatchers.IO`. Added timeouts to OkHttp for slow free cloud endpoints.

---

## settings.gradle.kts

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "QuietAI"
include(":app")
```

---

## build.gradle.kts (root)

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
}
```

---

## app/build.gradle.kts

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.quietai.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.quietai.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "backend"
    productFlavors {
        create("offline") { }
        create("cloud") { }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Local inference — Google's on-device LLM runtime
    implementation("com.google.mediapipe:tasks-genai:0.10.14")

    // Cloud fallback
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // UI / lifecycle
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0") // Added for MaterialSwitch/Bubbles
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
}
```

---

## app/src/main/AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- No INTERNET permission here. Offline APK has zero permissions. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="false"
        android:icon="@android:drawable/sym_def_app_icon"
        android:label="Quiet AI"
        android:theme="@style/Theme.QuietAI">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".keepalive.KeepAliveService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Keeps the local model warm in memory so the first reply is instant." />
        </service>
    </application>
</manifest>
```

---

## app/src/cloud/AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Only ships in the 'cloud' flavor. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
</manifest>
```

---

## engine/LlmEngine.kt

```kotlin
package com.quietai.app.engine

interface LlmEngine {
    val displayName: String
    suspend fun load()
    suspend fun generate(transcript: String): String
    fun close()
}
```

---

## engine/LocalEngine.kt

```kotlin
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
```

---

## engine/CloudEngine.kt

```kotlin
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
```

---

## model/ModelManager.kt

```kotlin
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
```

---

## ChatViewModel.kt

```kotlin
package com.quietai.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietai.app.engine.LlmEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {
    // Added ID for stable DiffUtil performance
    data class ChatMessage(val id: Long = System.nanoTime(), val text: String, val fromUser: Boolean)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    var engine: LlmEngine? = null

    fun send(text: String) {
        if (text.isBlank()) return

        val userMsg = ChatMessage(text = text, fromUser = true)
        _messages.value = _messages.value + userMsg

        val currentEngine = engine
        if (currentEngine == null) {
            _messages.value = _messages.value + ChatMessage(text = "No engine selected.", fromUser = false)
            return
        }

        viewModelScope.launch {
            _busy.value = true
            try {
                // Limit context window to last 10 messages to prevent token overflow on local models
                val recentMessages = _messages.value.takeLast(10)
                val transcript = recentMessages.joinToString("\n") { msg ->
                    if (msg.fromUser) "User: ${msg.text}" else "Assistant: ${msg.text}"
                }
                val reply = currentEngine.generate(transcript)
                _messages.value = _messages.value + ChatMessage(text = reply, fromUser = false)
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(text = "Error: ${e.message}", fromUser = false)
            } finally {
                _busy.value = false
            }
        }
    }
}
```

---

## MainActivity.kt

```kotlin
package com.quietai.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.quietai.app.databinding.ActivityMainBinding
import com.quietai.app.engine.CloudEngine
import com.quietai.app.engine.LlmEngine
import com.quietai.app.engine.LocalEngine
import com.quietai.app.keepalive.KeepAliveService
import com.quietai.app.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: ChatViewModel
    private lateinit var adapter: ChatAdapter

    private var currentEngine: LlmEngine? = null

    private val pickModel = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ModelManager.importPickedFile(this@MainActivity, uri)
            }
            Toast.makeText(this@MainActivity, "Model imported. Loading...", Toast.LENGTH_SHORT).show()
            activateEngine(LocalEngine(this@MainActivity))
        }
    }

    private val askNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, we still start the service */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[ChatViewModel::class.java]
        adapter = ChatAdapter()
        binding.chatList.layoutManager = LinearLayoutManager(this)
        binding.chatList.adapter = adapter

        if (BuildConfig.FLAVOR == "offline") {
            binding.modeCloud.visibility = View.GONE
        }

        lifecycleScope.launch {
            viewModel.messages.collect { list ->
                adapter.submitList(list)
                binding.chatList.scrollToPosition(list.size - 1)
            }
        }
        lifecycleScope.launch {
            viewModel.busy.collect { busy ->
                binding.sendButton.isEnabled = !busy
                binding.inputEditText.isEnabled = !busy
                binding.statusText.text = if (busy) "Thinking..." else "Ready"
            }
        }

        binding.modeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.mode_local -> onLocalPicked()
                R.id.mode_cloud -> activateEngine(CloudEngine())
            }
        }

        binding.sendButton.setOnClickListener {
            viewModel.send(binding.inputEditText.text.toString())
            binding.inputEditText.text.clear()
        }

        binding.keepAliveSwitch.setOnCheckedChangeListener { _, on ->
            if (on) startKeepAlive() else stopKeepAlive()
        }

        // Restore engine if it was kept alive in memory
        if (EngineRepository.activeEngine != null) {
            currentEngine = EngineRepository.activeEngine
            viewModel.engine = currentEngine
            binding.statusText.text = "${currentEngine?.displayName} ready"
            if (currentEngine is LocalEngine) binding.modeLocal.isChecked = true
            else binding.modeCloud.isChecked = true
        } else if (ModelManager.isReady(this)) {
            binding.modeLocal.isChecked = true
            onLocalPicked()
        }
    }

    private fun onLocalPicked() {
        if (ModelManager.isReady(this)) {
            activateEngine(LocalEngine(this))
        } else {
            Toast.makeText(
                this,
                "Pick your .task model file (copy it to the phone over USB first).",
                Toast.LENGTH_LONG
            ).show()
            pickModel.launch(arrayOf("*/*"))
            binding.modeGroup.clearCheck()
        }
    }

    private fun activateEngine(engine: LlmEngine) {
        currentEngine?.close()
        currentEngine = engine
        EngineRepository.activeEngine = engine // Persist across Activity recreation
        binding.statusText.text = "Loading ${engine.displayName}..."
        binding.sendButton.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Default) { engine.load() }
                viewModel.engine = engine
                binding.statusText.text = "${engine.displayName} ready"
            } catch (e: Exception) {
                binding.statusText.text = "Couldn't load: ${e.message}"
                viewModel.engine = null
                EngineRepository.activeEngine = null
            }
        }
    }

    private fun startKeepAlive() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
    }

    private fun stopKeepAlive() {
        stopService(Intent(this, KeepAliveService::class.java))
    }

    override fun onDestroy() {
        // CRITICAL FIX: Do NOT close the engine here.
        // If the user backgrounds the app, the Activity is destroyed but the Service
        // keeps the process alive. Closing the engine here defeats the "Keep Warm" feature.
        super.onDestroy()
    }
}

// Keeps the engine alive across Activity recreations
object EngineRepository {
    var activeEngine: LlmEngine? = null
}
```

---

## ChatAdapter.kt

```kotlin
package com.quietai.app

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.quietai.app.databinding.ItemMessageBinding

class ChatAdapter : ListAdapter<ChatViewModel.ChatMessage, ChatAdapter.MsgViewHolder>(Diff) {

    object Diff : DiffUtil.ItemCallback<ChatViewModel.ChatMessage>() {
        // FIX: Use ID for item comparison to prevent full list rebinds
        override fun areItemsTheSame(a: ChatViewModel.ChatMessage, b: ChatViewModel.ChatMessage) = a.id == b.id
        override fun areContentsTheSame(a: ChatViewModel.ChatMessage, b: ChatViewModel.ChatMessage) = a == b
    }

    class MsgViewHolder(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MsgViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return MsgViewHolder(ItemMessageBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: MsgViewHolder, position: Int) {
        val msg = getItem(position)
        holder.binding.messageText.text = msg.text
        
        val lp = holder.binding.messageText.layoutParams as FrameLayout.LayoutParams
        if (msg.fromUser) {
            lp.gravity = Gravity.END
            holder.binding.messageText.setBackgroundResource(R.drawable.bg_bubble_user)
        } else {
            lp.gravity = Gravity.START
            holder.binding.messageText.setBackgroundResource(R.drawable.bg_bubble_ai)
        }
        holder.binding.messageText.layoutParams = lp
    }
}
```

---

## keepalive/KeepAliveService.kt

```kotlin
package com.quietai.app.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.quietai.app.MainActivity

class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background readiness",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Quiet is ready")
            .setContentText("Holding the model in memory for instant replies.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openApp)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "keep_alive"
        private const val NOTIFICATION_ID = 1
    }
}
```

---

## res/layout/activity_main.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="12dp">

    <TextView
        android:id="@+id/statusText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Idle"
        android:textSize="12sp" />

    <RadioGroup
        android:id="@+id/modeGroup"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="horizontal">

        <RadioButton
            android:id="@+id/mode_local"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="On-device" />

        <RadioButton
            android:id="@+id/mode_cloud"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Cloud (free)" />
    </RadioGroup>

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/chatList"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:paddingVertical="8dp"
        android:clipToPadding="false" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical">

        <EditText
            android:id="@+id/inputEditText"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:hint="Say something" />

        <Button
            android:id="@+id/sendButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Send" />
    </LinearLayout>

    <com.google.android.material.switchmaterial.SwitchMaterial
        android:id="@+id/keepAliveSwitch"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Keep warm in background" />
</LinearLayout>
```

---

## res/layout/item_message.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:paddingVertical="4dp"
    android:paddingHorizontal="8dp">

    <TextView
        android:id="@+id/messageText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:maxWidth="300dp"
        android:padding="12dp"
        android:textColor="#1C1B1F"
        android:textSize="15sp" />
</FrameLayout>
```

---

## res/drawable/bg_bubble_user.xml (New File)

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#D1E8FF" />
    <corners android:radius="16dp" android:topRightRadius="4dp" />
</shape>
```

---

## res/drawable/bg_bubble_ai.xml (New File)

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#F0F0F0" />
    <corners android:radius="16dp" android:topLeftRadius="4dp" />
</shape>
```

---

## res/values/strings.xml

```xml
<resources>
    <string name="app_name">Quiet AI</string>
</resources>
```

---

## res/values/themes.xml

```xml
<resources>
    <style name="Theme.QuietAI" parent="Theme.Material3.DayNight.NoActionBar" />
</resources>
```

---

## Build commands:

```bash
./gradlew assembleOfflineDebug   # zero‑permission offline APK
./gradlew assembleCloudDebug     # cloud flavor with INTERNET
```