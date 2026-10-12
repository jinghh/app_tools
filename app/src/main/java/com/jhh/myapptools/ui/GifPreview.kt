package com.jhh.myapptools.ui

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/** GIF 结果预览：API 28+ 用 AnimatedImageDrawable 播放动图，低版本退化为静态首帧 */
@Composable
internal fun GifPreview(file: File, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                ImageView(ctx).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
                    setImageDrawable(drawable)
                    if (drawable is AnimatedImageDrawable) {
                        drawable.start()
                    }
                }
            },
        )
    } else {
        val bitmap = remember(file) { BitmapFactory.decodeFile(file.absolutePath) }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "GIF 预览",
                modifier = modifier,
            )
        }
    }
}
