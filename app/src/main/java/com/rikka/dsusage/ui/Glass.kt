package com.rikka.dsusage.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Paint as ComposePaint
import android.graphics.RadialGradient as AndroidRadialGradient
import java.util.Locale

/**
 * 设计系统 · 冷色 Frost
 *
 * 三条硬规则：
 *  1. 只用一套深色配色。玻璃的通透感建立在深背景上，浅底上的"玻璃"永远像塑料。
 *  2. 数字一律用等宽字形（见 numStyle），刷新时宽度不跳。
 *  3. 语义色分离：蓝紫=主数据 / 薄荷绿=正向 / 珊瑚红=负向 / 琥珀=待办。
 */
data class GlassColors(
    val baseGradient: List<Color>,
    val blobPalette: List<Color>,
    val cardTop: Color,
    val cardBottom: Color,
    val onGlass: Color,
    val onGlassMuted: Color,
    val onGlassFaint: Color,
    val accent: Color,
    val accentHi: Color,
    val peak: Color,
    val offPeak: Color,
    val warn: Color,
    val glow: Color,
    val isDark: Boolean,
)

val LocalGlass = staticCompositionLocalOf<GlassColors> {
    error("GlassColors 未提供，请用 GlassSurface 包裹")
}

@Composable
fun rememberGlassColors(): GlassColors = remember {
    GlassColors(
        baseGradient = listOf(Color(0xFF04060F), Color(0xFF071722), Color(0xFF050A18)),
        blobPalette = listOf(Color(0xFF5B9CFF), Color(0xFF2DD4BF), Color(0xFF7A5AFF)),
        cardTop = Color.White.copy(alpha = 0.075f),
        cardBottom = Color.White.copy(alpha = 0.018f),
        onGlass = Color(0xFFEAF0FF),
        onGlassMuted = Color(0xFF93A3C7),
        onGlassFaint = Color(0xFF5A6B8F),
        accent = Color(0xFF5B9CFF),
        accentHi = Color(0xFFA8C6FF),
        peak = Color(0xFFFF5C8A),
        offPeak = Color(0xFF2DD4BF),
        warn = Color(0xFFFFB84D),
        glow = Color(0x6B5B9CFF),
        isDark = true,
    )
}

/** 铺满整屏的背景：深空渐变 + 三团缓慢漂移的光斑，同时作为小组件的取色基准。 */
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
            BlobTexture(c.blobPalette[0], s0,
                x = { w * (0.10f + 0.38f * p1) - s0 / 2 },
                y = { h * (0.08f + 0.14f * p2) - s0 / 2 })
            BlobTexture(c.blobPalette[1], s1,
                x = { w * (0.92f - 0.34f * p2) - s1 / 2 },
                y = { h * (0.28f + 0.18f * p3) - s1 / 2 })
            BlobTexture(c.blobPalette[2], s2,
                x = { w * (0.26f + 0.28f * p3) - s2 / 2 },
                y = { h * (0.84f - 0.20f * p1) - s2 / 2 })
        }
    }
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

@Composable
private fun BlobTexture(color: Color, size: Dp, x: () -> Dp, y: () -> Dp) {
    val tex = remember(color) { makeBlobTexture(160, color) }
    Image(
        bitmap = tex,
        contentDescription = null,
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                translationX = x().toPx()
                translationY = y().toPx()
            },
    )
}

private fun makeBlobTexture(sizePx: Int, color: Color): ImageBitmap {
    val bmp = ImageBitmap(sizePx, sizePx)
    val canvas = GraphicsCanvas(bmp)
    val paint = ComposePaint().apply {
        asFrameworkPaint().shader = AndroidRadialGradient(
            sizePx / 2f, sizePx / 2f, sizePx / 2f,
            color.copy(alpha = 0.42f).toArgb(),
            color.copy(alpha = 0f).toArgb(),
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), paint)
    return bmp
}

/**
 * 玻璃卡片。
 *
 * 描边不是整圈均匀的：**顶部亮、向下衰减**——真实玻璃的边缘就是这样，光从上方来。
 * glow=true 时在卡片上方叠一层品牌色径向光晕，用于 Hero 级卡片。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    contentPadding: Dp = 15.dp,
    glow: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalGlass.current
    val shape = RoundedCornerShape(cornerRadius)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, tween(150), label = "cardScale")
    val lift by animateFloatAsState(if (pressed) 4f else 12f, tween(150), label = "cardLift")

    var m = modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .shadow(
            elevation = lift.dp,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.50f),
            spotColor = Color.Black.copy(alpha = 0.50f),
        )
        .clip(shape)

    if (onClick != null) {
        m = m.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    }

    Column(
        modifier = m
            .background(Brush.verticalGradient(listOf(c.cardTop, c.cardBottom)))
            .then(
                if (glow) {
                    Modifier.drawBehind {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(c.glow, Color.Transparent),
                                center = Offset(size.width * 0.5f, 0f),
                                radius = size.width * 0.72f,
                            ),
                        )
                    }
                } else {
                    Modifier
                }
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.30f),
                        Color.White.copy(alpha = 0.09f),
                        Color.White.copy(alpha = 0.05f),
                    )
                ),
                shape = shape,
            )
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(9.dp),
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
            .background(tint.copy(alpha = 0.15f))
            .border(1.dp, tint.copy(alpha = 0.40f), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = tint, style = MaterialTheme.typography.labelSmall)
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
                    topLeft = Offset(w * 0.13f, h * 0.27f),
                    size = Size(w * 0.74f, h * 0.52f),
                    cornerRadius = CornerRadius(w * 0.17f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw * 1.1f),
                )
                drawCircle(color = tint, radius = w * 0.07f, center = Offset(w * 0.69f, h * 0.53f))
            }
            Glyph.BARS -> {
                drawRoundRect(color = tint, topLeft = Offset(w * 0.17f, h * 0.50f), size = Size(w * 0.15f, h * 0.33f), cornerRadius = CornerRadius(w * 0.07f))
                drawRoundRect(color = tint, topLeft = Offset(w * 0.42f, h * 0.29f), size = Size(w * 0.15f, h * 0.54f), cornerRadius = CornerRadius(w * 0.07f))
                drawRoundRect(color = tint, topLeft = Offset(w * 0.68f, h * 0.13f), size = Size(w * 0.15f, h * 0.70f), cornerRadius = CornerRadius(w * 0.07f))
            }
            Glyph.CLOCK -> {
                drawCircle(color = tint, radius = w * 0.35f, center = Offset(w * 0.5f, h * 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw))
                drawLine(color = tint, start = Offset(w * 0.5f, h * 0.5f), end = Offset(w * 0.5f, h * 0.31f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(color = tint, start = Offset(w * 0.5f, h * 0.5f), end = Offset(w * 0.66f, h * 0.56f), strokeWidth = sw, cap = StrokeCap.Round)
            }
            Glyph.SLIDERS -> {
                drawLine(color = tint, start = Offset(w * 0.15f, h * 0.35f), end = Offset(w * 0.85f, h * 0.35f), strokeWidth = sw * 0.9f, cap = StrokeCap.Round)
                drawLine(color = tint, start = Offset(w * 0.15f, h * 0.65f), end = Offset(w * 0.85f, h * 0.65f), strokeWidth = sw * 0.9f, cap = StrokeCap.Round)
                drawCircle(color = tint, radius = w * 0.105f, center = Offset(w * 0.37f, h * 0.35f))
                drawCircle(color = tint, radius = w * 0.105f, center = Offset(w * 0.66f, h * 0.65f))
            }
        }
    }
}

/** 注入玻璃配色。 */
@Composable
fun GlassSurface(content: @Composable () -> Unit) {
    val colors = rememberGlassColors()
    CompositionLocalProvider(LocalGlass provides colors) { content() }
}
