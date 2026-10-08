package app.stillroom.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.stillroom.data.BarcodeDecoder
import app.stillroom.domain.*

internal const val ViewfinderInset = 0.1f

internal fun barcodeViewfinderRegion(width: Int, height: Int): ScanRegion {
    if (width <= 0 || height <= 0) return ScanRegion(0f, 0f, 0f, 0f)
    return ScanRegion(0f, 0f, width.toFloat(), height.toFloat())
}

@Composable
fun CameraScanner(active:Boolean=true,onCodes:(List<ScanCode>)->Unit) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onCodes)
    val analyzing by rememberUpdatedState(active)
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) }
    var enabled by remember { mutableStateOf(false) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted=it;enabled=it }
    if(!enabled || !granted) {
        if (!active) return
        PrimaryButton(onClick={if(granted)enabled=true else permission.launch(Manifest.permission.CAMERA)}) { Text("Start camera") }
        Text("Camera permission is optional. Manual entry works below.")
        return
    }
    val controller=remember { LifecycleCameraController(context).apply { setEnabledUseCases(androidx.camera.view.CameraController.IMAGE_ANALYSIS) } }
    val preview=remember {
        PreviewView(context).apply {
            implementationMode=PreviewView.ImplementationMode.COMPATIBLE
            scaleType=PreviewView.ScaleType.FILL_CENTER
            layoutParams=ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
            outlineProvider=object:ViewOutlineProvider() {
                override fun getOutline(view:View, outline:Outline) {
                    outline.setRoundRect(0,0,view.width,view.height,24f*view.resources.displayMetrics.density)
                }
            }
            clipToOutline=true
            clipChildren=true
            this.controller=controller
        }
    }
    val decoder=remember { BarcodeDecoder() }
    var error by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(0f) }
    var hasFlash by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    DisposableEffect(controller,owner) {
        val executor=ContextCompat.getMainExecutor(context)
        controller.setImageAnalysisAnalyzer(executor,MlKitAnalyzer(listOf(decoder.detector),ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,executor) { result ->
            if(analyzing && preview.width>0 && preview.height>0) {
                val target=barcodeViewfinderRegion(preview.width,preview.height)
                result.getValue(decoder.detector)?.let { callback(decoder.inTarget(it,target)) }
                if(result.getThrowable(decoder.detector)!=null) error="Camera analysis unavailable. Use manual entry."
            }
        })
        try { controller.bindToLifecycle(owner) } catch(_:Exception) { error="Camera unavailable. Use manual entry." }
        val future=controller.initializationFuture
        future.addListener({
            runCatching { future.get();hasFlash=controller.cameraInfo?.hasFlashUnit()==true;ready=controller.cameraInfo!=null }.onFailure { error="Camera unavailable. Use manual entry." }
        },executor)
        onDispose { controller.enableTorch(false);controller.clearImageAnalysisAnalyzer();controller.unbind();decoder.close() }
    }
    LaunchedEffect(active,torch,ready) { if(ready && hasFlash) controller.enableTorch(active && torch) }
    if (!active) return
    val viewfinderShape=MaterialTheme.shapes.medium
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(viewfinderShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clipToBounds()
            .testTag("barcode-viewfinder")
            .semantics { contentDescription="Barcode scanner" }
    ) {
        // PreviewView can draw past Compose layout; clip and inset so the live feed stays in the target window.
        AndroidView(
            factory={preview},
            modifier=Modifier
                .fillMaxSize()
                .padding(horizontal=maxWidth*ViewfinderInset, vertical=maxHeight*ViewfinderInset)
                .clip(viewfinderShape)
                .clipToBounds()
        )
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                Color.White,
                topLeft=Offset(size.width*ViewfinderInset,size.height*ViewfinderInset),
                size=Size(size.width*(1-2*ViewfinderInset),size.height*(1-2*ViewfinderInset)),
                cornerRadius=CornerRadius(24.dp.toPx()),
                style=Stroke(3.dp.toPx())
            )
        }
    }
    Text("Place the barcode inside the target. Scanning pauses when a product is found.")
    Row {
        SecondaryButton(onClick={torch=!torch},enabled=hasFlash && ready) { Text(if(torch)"Turn flash off" else "Turn flash on") }
        QuietButton(onClick={enabled=false}) { Text("Stop camera") }
    }
    Text("Zoom")
    Slider(value=zoom,onValueChange={zoom=it;controller.setLinearZoom(it)},enabled=ready,modifier=Modifier.semantics { contentDescription="Camera zoom" })
    error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
}
