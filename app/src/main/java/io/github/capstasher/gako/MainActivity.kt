package io.github.capstasher.gako

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import io.github.capstasher.gako.editor.EditorViewModel
import io.github.capstasher.gako.ui.EditorScreen
import io.github.capstasher.gako.ui.theme.GakoTheme

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::open)
    }

    private var openAsLayers = false

    /** Any file: photos, but also .ora and .xcf projects, which the photo picker hides. */
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.open(it, asLayers = openAsLayers) }
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::setExportFolder)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        // Back on the editor itself leaves the app running (and the image open) rather than closing it.
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }

        setContent {
            GakoTheme {
                EditorScreen(
                    vm = vm,
                    onPickImage = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onPickFile = { asLayers ->
                        openAsLayers = asLayers
                        filePicker.launch(arrayOf("image/*", "application/octet-stream", "application/zip",
                            "image/openraster", "image/x-xcf", "application/x-xcf"))
                    },
                    onPickFolder = { folderPicker.launch(null) },
                    onShare = ::share,
                )
            }
        }
    }

    /** Shortcuts only see keys nothing on screen used, so typing in a text field never triggers them. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (event.repeatCount == 0 && vm.onKey(keyCode, event.isCtrlPressed, event.isShiftPressed)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        vm.autosave()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri = when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            else -> null
        } ?: return
        vm.open(uri)
    }

    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share image"))
    }
}
