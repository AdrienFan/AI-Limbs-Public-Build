package com.ai.limbs.plugins.artstudio

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One cancellable worker per open dialog; no preview result can commit an edit. */
@Composable
internal fun StudioFilterPreview(request:Map<String,String>, arguments:()->JSONObject,
    render:(JSONObject)->Bitmap) {
    val context=LocalContext.current
    val view=remember {ImageView(context).apply {scaleType=ImageView.ScaleType.FIT_CENTER}}
    val executor=remember {Executors.newSingleThreadExecutor()}
    val generation=remember {AtomicInteger()}
    val handler=remember {Handler(Looper.getMainLooper())}
    var image by remember {mutableStateOf<Bitmap?>(null)}
    var message by remember {mutableStateOf("预览计算中…")}
    DisposableEffect(request) {
        val token=generation.incrementAndGet();val active=AtomicBoolean(true)
        message="预览计算中…"
        view.setImageDrawable(null);image?.recycle();image=null
        // Parse on the UI thread: the background worker never reads Compose's mutable map.
        val parsed=try {arguments()} catch(error:IllegalArgumentException) {message=error.message ?: "参数无效";null}
        val task=if(parsed==null)null else executor.submit {
            var result:Bitmap?=null
            try {
                Thread.sleep(200)
                result=render(parsed)
                val ready=result;result=null
                handler.post {
                    if(active.get() && generation.get()==token) {
                        view.setImageBitmap(ready);image?.recycle();image=ready
                        message="当前图层预览 · 白底显示透明区域"
                    } else ready?.recycle()
                }
            } catch(error:Exception) {
                if(active.get() && !Thread.currentThread().isInterrupted && error !is java.util.concurrent.CancellationException) {
                    android.util.Log.w("ArtStudioFilter","Filter preview failed",error)
                    handler.post {if(active.get() && generation.get()==token)message=error.message ?: "预览失败"}
                }
            } finally {result?.recycle()}
        }
        onDispose {active.set(false);generation.incrementAndGet();task?.cancel(true)}
    }
    DisposableEffect(Unit) {
        onDispose {generation.incrementAndGet();executor.shutdownNow();view.setImageDrawable(null);image?.recycle();image=null}
    }
    Column {
        Text(message)
        AndroidView(factory={view},modifier=Modifier.fillMaxWidth().height(180.dp))
    }
}
