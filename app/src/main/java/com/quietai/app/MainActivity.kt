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
