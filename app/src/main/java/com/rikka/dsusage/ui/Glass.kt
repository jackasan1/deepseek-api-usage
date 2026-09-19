package com.rikka.dsusage.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Paint as ComposePaint
import android.graphics.RadialGradient as AndroidRadialGradient
import java.util.Locale

/** 一套玻璃材质配色。深浅色模式各一套，靠 CompositionLocal 下发。 */
data class GlassColors(
    val baseGradient: List<Color>,
    val blobPalette: List<Color>,
    val cardTop: Color,
    val cardBottom: Color,
    val border: List<Color>,
    val onGlass: Color,
    val onGlassMuted: Color,
    val accent: Color,
    val peak: Color,
    val offPeak: Color,
    val isDark: Boolean,
)

val LocalGlass = staticCompositionLocalOf<GlassColors> {
    error("GlassColors 未提供，请用 GlassSurface 包裹")
}

@Composable
fun rememberGlassColors(): GlassColors {
    val dark = isSystemInDarkTheme()
    return remember(dark) {
        if (dark) GlassColors(
            baseGradient = listOf(Color(0xFF05070F), Color(0xFF0D1230), Color(0xFF04121C)),
            blobPalette = listOf(Color(0xFF4D6BFE), Color(0xFF7C3AED), Color(0xFF06B6D4)),
            // 有了真模糊，卡片自身的不透明度可以压低，让背景透出来
            cardTop = Color.White.copy(alpha = 0.10f),
            cardBottom = Color.White.copy(alpha = 0.03f),
            border = listOf(
                Color.White.copy(alpha = 0.50f),
                Color.White.copy(alpha = 0.06f),
                Color.White.copy(alpha = 0.22f),
            ),
            onGlass = Color(0xFFF3F5FF),
            onGlassMuted = Color(0xFFAFB7DA),
            accent = Color(0xFF9DB0FF),
            peak = Color(0xFFFF8A93),
            offPeak = Color(0xFF5FE3AE),
            isDark = true,
        ) else GlassColors(
            baseGradient = listOf(Color(0xFFCBD7FF), Color(0xFFE6D4FF), Color(0xFFC3E8FF)),
            blobPalette = listOf(Color(0xFF4D6BFE), Color(0xFF9B5CFF), Color(0xFF00B4D8)),
            cardTop = Color.White.copy(alpha = 0.62f),
            cardBottom = Color.White.copy(alpha = 0.40f),
            border = listOf(
                Color.White.copy(alpha = 0.95f),
                Color.White.copy(alpha = 0.40f),
                Color.White.copy(alpha = 0.70f),
            ),
            onGlass = Color(0xFF12162E),
            onGlassMuted = Color(0xFF5C6484),
            accent = Color(0xFF3B57E0),
            peak = Color(0xFFC62828),
            offPeak = Color(0xFF17795A),
            isDark = false,
        )
    }
}

/** 铺满整屏的玻璃背景：底色渐变 + 三团缓慢漂移的彩色光斑。 */
@Composable
fun GlassBackground(modifier: Modifier = Modifier) {
    val c = LocalGlass.current
    val drift = rememberInfiniteTransition(label = "bg")
    val p1 by drift.animateFloatSafe(26000)
    val p2 by drift.animateFloatSafe(34000)
    val p3 by drift.animateFloatSafe(21000)

    Box(modifier.background(Brush.linearGradient(c.baseGradient))) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = maxWidth
            val h = maxHeight
            val d = if (w < h) w else h
            val s0 = d * 0.66f
            val s1 = d * 0.58f
            val s2 = d * 0.72f
            BlobTexture(
                color = c.blobPalette[0],
                size = s0,
                x = { w * (0.10f + 0.38f * p1) - s0 / 2 },
                y = { h * (0.08f + 0.14f * p2) - s0 / 2 },
            )
            BlobTexture(
                color = c.blobPalette[1],
                size = s1,
                x = { w * (0.92f - 0.34f * p2) - s1 / 2 },
                y = { h * (0.28f + 0.18f * p3) - s1 / 2 },
            )
            BlobTexture(
                color = c.blobPalette[2],
                size = s2,
                x = { w * (0.26f + 0.28f * p3) - s2 / 2 },
                y = { h * (0.84f - 0.20f * p1) - s2 / 2 },
            )
        }
    }
}

/** 把光斑预渲染成小尺寸贴图；每帧只改变换矩阵，不重新光栅化。 */
@Composable
private fun BlobTexture(color: Color, size: Dp, x: () -> Dp, y: () -> Dp) {
    val tex = remember(color) { makeBlobTexture(BLOB_TEX_PX, color) }
    Image(
        bitmap = tex,
        contentDescription = null,
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                // 状态读取延迟到绘制阶段，动画推进不触发重组、也不重画画布
                translationX = x().toPx()
                translationY = y().toPx()
            },
    )
}

private const val BLOB_TEX_PX = 160

private fun makeBlobTexture(sizePx: Int, color: Color): ImageBitmap {
    val bmp = ImageBitmap(sizePx, sizePx)
    val canvas = GraphicsCanvas(bmp)
    val paint = ComposePaint().apply {
        asFrameworkPaint().shader = AndroidRadialGradient(
            sizePx / 2f,
            sizePx / 2f,
            sizePx / 2f,
            color.copy(alpha = 0.62f).toArgb(),
            color.copy(alpha = 0f).toArgb(),
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), paint)
    return bmp
}

@Composable
private fun InfiniteTransition.animateFloatSafe(periodMs: Int) =
    animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift$periodMs",
    )


/** 玻璃卡片：半透明渐变 + 镜面高光描边 + 柔和投影。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 26.dp,
    contentPadding: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalGlass.current
    val shape = RoundedCornerShape(cornerRadius)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, tween(150), label = "cardScale")
    val lift by animateFloatAsState(if (pressed) 4f else 14f, tween(150), label = "cardLift")

    var m = modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .shadow(
            elevation = lift.dp,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = if (c.isDark) 0.45f else 0.12f),
            spotColor = Color.Black.copy(alpha = if (c.isDark) 0.45f else 0.12f),
        )
        .clip(shape)

    if (onClick != null) {
        m = m.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    }

    Column(
        modifier = m
            .background(Brush.verticalGradient(listOf(c.cardTop, c.cardBottom)))
            .border(1.dp, Brush.linearGradient(c.border), shape)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** 玻璃胶囊标签。 */
@Composable
fun GlassPill(text: String, tint: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(tint.copy(alpha = 0.18f))
            .border(1.dp, tint.copy(alpha = 0.45f), shape)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(text, color = tint, style = MaterialTheme.typography.labelMedium)
    }
}

/** 从 0 滚动到目标值的数字，用于余额 / 金额。 */
@Composable
fun RollingMoney(
    target: Double,
    style: TextStyle,
    color: Color,
    prefix: String = "¥",
    durationMs: Int = 900,
) {
    var shown by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(target) {
        animate(
            initialValue = 0f,
            targetValue = target.toFloat(),
            animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
        ) { value, _ -> shown = value }
    }
    Text(
        prefix + String.format(Locale.CHINA, "%.2f", shown.toDouble()),
        style = style,
        color = color,
    )
}

/** 导航图标：纯 Canvas 手绘，避免引入 material-icons 依赖。 */
enum class Glyph { WALLET, BARS, CLOCK, SLIDERS }

@Composable
fun NavGlyph(glyph: Glyph, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.085f
        when (glyph) {
            Glyph.WALLET -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.10f, h * 0.26f),
                    size = Size(w * 0.80f, h * 0.50f),
                    cornerRadius = CornerRadius(w * 0.14f),
                    style = Stroke(width = sw),
                )
                drawCircle(color = tint, radius = w * 0.075f, center = Offset(w * 0.68f, h * 0.51f))
            }
            Glyph.BARS -> {
                drawRoundRect(color = tint, topLeft = Offset(w * 0.16f, h * 0.48f), size = Size(w * 0.17f, h * 0.34f), cornerRadius = CornerRadius(w * 0.08f))
                drawRoundRect(color = tint, topLeft = Offset(w * 0.42f, h * 0.30f), size = Size(w * 0.17f, h * 0.52f), cornerRadius = CornerRadius(w * 0.08f))
                drawRoundRect(color = tint, topLeft = Offset(w * 0.68f, h * 0.16f), size = Size(w * 0.17f, h * 0.66f), cornerRadius = CornerRadius(w * 0.08f))
            }
            Glyph.CLOCK -> {
                drawCircle(color = tint, radius = w * 0.36f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(width = sw))
                drawLine(color = tint, start = Offset(w * 0.5f, h * 0.5f), end = Offset(w * 0.5f, h * 0.29f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(color = tint, start = Offset(w * 0.5f, h * 0.5f), end = Offset(w * 0.67f, h * 0.57f), strokeWidth = sw, cap = StrokeCap.Round)
            }
            Glyph.SLIDERS -> {
                listOf(0.28f, 0.50f, 0.72f).forEachIndexed { i, y ->
                    drawLine(color = tint, start = Offset(w * 0.14f, h * y), end = Offset(w * 0.86f, h * y), strokeWidth = sw * 0.85f, cap = StrokeCap.Round)
                    drawCircle(color = tint, radius = w * 0.105f, center = Offset(w * (0.30f + i * 0.20f), h * y))
                }
            }
        }
    }
}

/**
 * 注入玻璃配色。
 */
@Composable
fun GlassSurface(content: @Composable () -> Unit) {
    val colors = rememberGlassColors()
    CompositionLocalProvider(
        LocalGlass provides colors,
    ) { content() }
}
