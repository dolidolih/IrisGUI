package party.qwer.irisgui.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.R

/**
 * GlassBackground — 콘텐츠 전면에 깐는 파스텔 스티커 배경.
 *
 *   1) 대각 삼중 그래디언트(분홍 -> 라일락 -> 하늘)
 *   2) 대형 블롭으로 깊이감
 *   3) 정규화 좌표(0..1) 에 얹는 부유 에셋(구름 · 반짝임 · 꽃 · 별)
 *
 * 전부 정적이라 매 프레임 리드로 그려지지 않는다(애니 없음).
 */
data class Floating(@DrawableRes val res: Int, val fx: Float, val fy: Float,
                    val size: Dp, val rot: Float = 0f, val alpha: Float = 1f)

private val FloatingAssets = listOf(
    Floating(R.drawable.ic_cloud, 0.50f, 0.015f, 96.dp, alpha = 0.92f),
    Floating(R.drawable.ic_cloud, 0.89f, 0.075f, 66.dp, alpha = 0.85f),
    Floating(R.drawable.ic_star, 0.94f, 0.11f, 26.dp, rot = 15f, alpha = 0.8f),
    Floating(R.drawable.ic_sparkle, 0.985f, 0.25f, 24.dp, rot = 12f, alpha = 0.75f),
    Floating(R.drawable.ic_sparkle_lav, 0.01f, 0.31f, 28.dp, rot = -8f, alpha = 0.7f),
    Floating(R.drawable.ic_flower_pink, 0.00f, 0.48f, 32.dp, alpha = 0.9f),
    Floating(R.drawable.ic_flower, 0.99f, 0.54f, 30.dp, alpha = 0.8f),
    Floating(R.drawable.ic_leaf, 0.985f, 0.90f, 28.dp, rot = -18f, alpha = 0.75f),
)

@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    listOf(AppColors.Gradient1, AppColors.Gradient2, AppColors.Gradient3)
                )
            )
    ) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()

        // 깊이감 블롭 (radial feather — 별도 블러 없이 번져 보인다; Modifier.blur 는
        // API 31+ 전용이라 minSdk 30 본 앱에서는 no-op 이므로 그래디언트 페더링만 사용)
        Canvas(modifier = Modifier.matchParentSize()) {
            val side = maxOf(size.width, size.height)
            val blobs = listOf(
                Triple(Color(0xFFFFD3E6), Offset(size.width * 0.15f, size.height * 0.10f), 0.34f),
                Triple(Color(0xFFCFE6FF), Offset(size.width * 0.90f, size.height * 0.18f), 0.30f),
                Triple(Color(0xFFD9CCFF), Offset(size.width * 0.85f, size.height * 0.88f), 0.38f),
                Triple(Color(0xFFC8F2DE), Offset(size.width * 0.08f, size.height * 0.95f), 0.28f),
            )
            blobs.forEach { (c, ctr, rf) ->
                val radius = side * rf
                drawCircle(
                    brush = Brush.radialGradient(listOf(c.copy(alpha = 0.50f), c.copy(alpha = 0f)), ctr, radius),
                    radius = radius,
                    center = ctr
                )
            }
            // 흰색 베일 — 콘텐츠 위 색이 과하지 않게 배경을 담근다.
            drawRect(Color.White.copy(alpha = 0.14f))
        }

        // 부유 에셋 — 화면 정규화 좌표 중심
        val density = LocalDensity.current
        FloatingAssets.forEach { f ->
            val half = with(density) { f.size.roundToPx() } / 2f
            Image(
                painter = painterResource(f.res),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(f.size)
                    .alpha(f.alpha)
                    .rotate(f.rot)
                    .offset {
                        IntOffset(
                            (f.fx * wPx - half).toInt(),
                            (f.fy * hPx - half).toInt()
                        )
                    }
            )
        }

        content()
    }
}
