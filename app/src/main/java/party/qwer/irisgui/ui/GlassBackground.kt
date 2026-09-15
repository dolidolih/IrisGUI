package party.qwer.irisgui.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import party.qwer.irisgui.AppColors
import kotlin.random.Random

/**
 * GlassBackground — 콘텐츠 전면에 깐는 파스텔 블롭 매시.
 *
 * 각 블롭은 가장자리가 투명까지 퍼지는 radial 그래디언트라 별도 블러 없이도 번지는
 * 느낌으로 그려진다. (Modifier.blur 는 API 31+ 에서만 동작하므로 minSdk 30인 본 앱과
 * 테스트 장비에서는 no-op — 그래서 그래디언트 페더링만으로 번짐을 낸다.)
 *
 * 세 정지 배경 — 세션마다 한 번 색 조합/배치를 뽑고(seed), rememberSaveable 로 화면 회전·
 * 탭 전환 중 같은 배경을 유지한다. 리스트 스크롤마다 배경이 invalidate 되지 않도록
 * 애니메이션(매 프레임 드로잉)은 넣지 않는다.
 */
private data class PastelBlob(val color: Color, val x: Float, val y: Float, val radius: Float)

/** 화사하게 쓰기 위한 파스텔 풀 — 여기서 4개를 뽑아 얹는다. (좌표는 화면 정규화값) */
private val PastelPool = listOf(
    PastelBlob(Color(0xFFBFD8FF), 0.12f, 0.08f, 1.10f),   // periwinkle
    PastelBlob(Color(0xFFDCCDFF), 0.88f, 0.04f, 1.00f),   // lilac
    PastelBlob(Color(0xFFB8F2DE), 0.04f, 0.94f, 1.02f),   // mint
    PastelBlob(Color(0xFFFFCBD9), 0.98f, 0.88f, 0.96f),   // peach pink
    PastelBlob(Color(0xFFC9ECFF), 0.54f, 1.06f, 0.88f),   // sky
    PastelBlob(Color(0xFFFFE9C4), 0.80f, 0.50f, 0.72f),   // butter
    PastelBlob(Color(0xFFCEDFFC), 0.20f, 0.52f, 0.80f),   // cornflower
)

/** 블롭 옅기/흰색 베일 — 베일이 강할수록 배경이 희석되어 블록 위에 얹는 색이 과해지지 않는다. */
private const val BlobAlpha = 0.70f
private const val VeilAlpha = 0.10f
/** 캔버스 전체를 10% 어둡게 — 블록 아래 배경 위에서만 어두워지므로 블록 면은 그대로다. */
private const val DarkenAlpha = 0.10f

@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val seed = rememberSaveable { Random.nextLong() }
    val blobs = remember(seed) {
        val rnd = Random(seed)
        PastelPool.shuffled(rnd).take(5).map { blob ->
            blob.copy(
                x = (blob.x + (rnd.nextFloat() - 0.5f) * 0.12f).coerceIn(0.04f, 0.96f),
                y = (blob.y + (rnd.nextFloat() - 0.5f) * 0.12f).coerceIn(0.04f, 0.96f)
            )
        }
    }

    Box(modifier.fillMaxSize().background(AppColors.CanvasBase)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val side = maxOf(size.width, size.height) * 0.85f
            blobs.forEach { b ->
                val center = Offset(size.width * b.x, size.height * b.y)
                val radius = side * b.radius
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(b.color.copy(alpha = BlobAlpha), b.color.copy(alpha = 0f)),
                        center,
                        radius
                    ),
                    radius = radius,
                    center = center
                )
            }
            // 흰색 베일 — 배경을 밝게 데워 블록 안 잉크/액센트가 과하게 서지 않게 한다.
            drawRect(Color.White.copy(alpha = VeilAlpha))
            // 캔버스만 10% 딤 — content(블록/필) 는 이 위로 그려져 영향을 받지 않는다.
            drawRect(Color.Black.copy(alpha = DarkenAlpha))
        }
        content()
    }
}
