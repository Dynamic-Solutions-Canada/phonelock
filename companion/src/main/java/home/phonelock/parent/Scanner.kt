package home.phonelock.parent

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Full-screen camera that returns the first pairing link it sees. */
@Composable
fun QrScanner(onResult: (String) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(Modifier.padding(24.dp)) {
            Text(stringResource(R.string.setup_camera_needed))
            Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.setup_allow_camera)) }
            Button(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
        return
    }

    val owner = LocalLifecycleOwner.current
    val done = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    // Used only from the executor's one thread, as QrFrameDecoder requires.
    val decoder = remember { QrFrameDecoder() }
    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
            val view = PreviewView(c)
            val future = ProcessCameraProvider.getInstance(c)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                val main = ContextCompat.getMainExecutor(c)
                analysis.setAnalyzer(executor) { proxy ->
                    // Each frame is decoded here, off the main thread; the first pairing link ends the scan.
                    val hit = proxy.use { if (done.get()) null else QrFrameDecoder.pairingLink(decoder.decode(it)) }
                    if (hit != null && done.compareAndSet(false, true)) {
                        // The camera and the result belong to the main thread.
                        main.execute {
                            provider.unbindAll()
                            onResult(hit)
                        }
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(c))
            view
        })
        Column(Modifier.align(Alignment.BottomCenter).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.setup_point_at_code), color = MaterialTheme.colorScheme.onPrimary)
            Button(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    }
}
