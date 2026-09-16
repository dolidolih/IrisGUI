package party.qwer.irisgui.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * StickerShape - 둥근 모서리 + 잔물결 스칼럽(스티커/도장) 가장자리.
 *
 * 둥근 사각형(반지름 cornerDp) 둘레를 arc-length 로 매개변수화하고, 각 점에서
 * outward normal 을 따라 sinus 진동을 더한다: offset(s) = amp * sin(2π * bumpsPerPx * s)
 * 그래서 폭/높이가 커지면 bump 수가 자연히 늘어 카드 크기 변화에 shape 이 깨지지 않는다.
 *
 * 좌표는 createOutline 안에서 절대 px 로 계산한다(그룹 변환 없음).
 */
class StickerShape(
    val cornerDp: Float,
    val bumpsPerPx: Float = 0.028f,
    val ampFrac: Float = 0.013f,
) : Shape {
    /** Path 생성은 trig 220× per call — 크기 불변이면 재사용한다(무한 애니메이션과
     *  조합되면 매 프레임 생성은 지양된다). */
    private var cachedW = -1f
    private var cachedH = -1f
    private var cachedPath: Path? = null

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return Outline.Rectangle(Rect.Zero)
        val path = cachedPath
        if (path != null && cachedW == w && cachedH == h) {
            return Outline.Generic(path)
        }
        val r = min(cornerDp * density.density, min(w, h) / 2f)
        val p = stickerPath(w, h, r, bumpsPerPx, ampFrac)
        cachedW = w
        cachedH = h
        cachedPath = p
        return Outline.Generic(p)
    }
}

private class Seg(val x: Float, val y: Float, val nx: Float, val ny: Float)
private class Pt(var x: Float, var y: Float)

private val QUARTER_ARC = (PI * 0.5).toFloat()

private fun segmentFull(i: Int, t: Float, w: Float, h: Float, r: Float): Seg {
    val a = (t * (PI / 2)).toFloat()
    return when (i) {
        0 -> Seg(r + t * (w - 2 * r), 0f, 0f, -1f)
        1 -> {
            val ang = -PI.toFloat() / 2f + a
            val cx = w - r
            val cy = r
            val x = cx + r * cos(ang).toFloat()
            val y = cy + r * sin(ang).toFloat()
            Seg(x, y, (x - cx) / r, (y - cy) / r)
        }
        2 -> Seg(w, r + t * (h - 2 * r), 1f, 0f)
        3 -> {
            val ang = a
            val cx = w - r
            val cy = h - r
            val x = cx + r * cos(ang).toFloat()
            val y = cy + r * sin(ang).toFloat()
            Seg(x, y, (x - cx) / r, (y - cy) / r)
        }
        4 -> Seg(w - r - t * (w - 2 * r), h, 0f, 1f)
        5 -> {
            val ang = PI.toFloat() / 2f + a
            val cx = r
            val cy = h - r
            val x = cx + r * cos(ang).toFloat()
            val y = cy + r * sin(ang).toFloat()
            Seg(x, y, (x - cx) / r, (y - cy) / r)
        }
        6 -> Seg(0f, h - r - t * (h - 2 * r), -1f, 0f)
        else -> {
            val ang = PI.toFloat() + a
            val cx = r
            val cy = r
            val x = cx + r * cos(ang).toFloat()
            val y = cy + r * sin(ang).toFloat()
            Seg(x, y, (x - cx) / r, (y - cy) / r)
        }
    }
}

private fun segLenOf(i: Int, w: Float, h: Float, r: Float): Float = when (i) {
    0, 4 -> w - 2 * r
    2, 6 -> h - 2 * r
    else -> QUARTER_ARC * r
}

private fun stickerPath(w: Float, h: Float, r: Float, bumpsPerPx: Float, ampFrac: Float): Path {
    val lens = FloatArray(8) { segLenOf(it, w, h, r) }
    val total = lens.sum()
    val amp = ampFrac * min(w, h)
    val phaseK = 2f * PI.toFloat() * bumpsPerPx
    val starts = FloatArray(8)
    var acc = 0f
    for (i in 0 until 8) {
        starts[i] = acc
        acc += lens[i]
    }
    val N = 300
    val pts = ArrayList<Pt>(N)
    var segI = 0
    for (i in 0 until N) {
        val target = i.toFloat() / N * total
        while (segI < 7 && target >= starts[segI] + lens[segI]) segI++
        val t = ((target - starts[segI]) / lens[segI]).coerceIn(0f, 1f)
        val p = segmentFull(segI, t, w, h, r)
        val off = amp * sin(phaseK * target)
        pts.add(Pt(p.x + p.nx * off, p.y + p.ny * off))
    }
    val path = Path()
    catmullRomClosed(path, pts)
    return path
}

/** closed Catmull-Rom → cubic bezier 로 path 를 채운다. */
private fun catmullRomClosed(path: Path, p: List<Pt>) {
    val n = p.size
    if (n < 2) return
    path.moveTo(p[0].x, p[0].y)
    for (i in 0 until n) {
        val p0 = p[(i - 1 + n) % n]
        val p1 = p[i]
        val p2 = p[(i + 1) % n]
        val p3 = p[(i + 2) % n]
        val c1x = p1.x + (p2.x - p0.x) / 6f
        val c1y = p1.y + (p2.y - p0.y) / 6f
        val c2x = p2.x - (p3.x - p1.x) / 6f
        val c2y = p2.y - (p3.y - p1.y) / 6f
        path.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
    }
    path.close()
}
