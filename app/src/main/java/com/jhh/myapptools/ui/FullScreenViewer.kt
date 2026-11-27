package com.jhh.myapptools.ui

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jhh.myapptools.R
import java.io.File

/**
 * 全屏图片查看层：深色遮罩 + 点空白关闭 + 返回键关闭。
 *
 * - 静态图片（抽帧 JPEG）：Compose Image，ContentScale.Fit 完整显示
 * - GIF：AndroidView + AnimatedImageDrawable 保持动画；API<28 退化为首帧
 */
@Composable
internal fun FullScreenViewer(
    file: File,
    isGif: Boolean = file.name.endsWith(".gif", ignoreCase = true),
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isGif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(48.dp),
                factory = { ctx ->
                    ImageView(ctx).apply {
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        val drawable = runCatching {
                            ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
                        }.getOrNull()
                        if (drawable != null) {
                            setImageDrawable(drawable)
                            if (drawable is AnimatedImageDrawable) drawable.start()
                        }
                    }
                },
                onRelease = { view ->
                    (view.drawable as? AnimatedImageDrawable)?.stop()
                    view.setImageDrawable(null)
                },
            )
        } else {
            val bitmap = remember(file) {
                runCatching {
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                }.getOrNull()
            }
            DisposableEffect(bitmap) {
                onDispose { bitmap?.asAndroidBitmap()?.recycle() }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "全屏预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(48.dp),
                )
            } else {
                Text("无法加载预览", color = Color.White)
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "关闭",
                tint = Color.White,
            )
        }
    }
}
