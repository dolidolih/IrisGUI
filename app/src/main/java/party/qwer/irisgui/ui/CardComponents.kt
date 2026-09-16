package party.qwer.irisgui.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppTypography
import androidx.compose.animation.core.animateFloatAsState

/** 화면 블록 공용 스페이스. */
val ScreenPadding = 18.dp

/** 스티커 블록 기본 외곽선 굵기. */
val StickerBorderWidth = 2.dp

/** 둥근 모서리 + 잔물결 스칼럽 스티커 shape. */
@Composable
fun stickerShape(ampFrac: Float = 0.013f, bumpsPerPx: Float = 0.028f): StickerShape {
    val density = LocalDensity.current
    val cornerDp = AppColors.BlockRadius.value.toFloat()
    return remember(ampFrac, bumpsPerPx, cornerDp, density) {
        StickerShape(cornerDp = cornerDp, bumpsPerPx = bumpsPerPx, ampFrac = ampFrac)
    }
}

/**
 * SurfaceCard — 스티커 블록.
 *
 * 흰 면 + 웜 잉크 외곽선 + 파스텔 drop shadow + 잔물결 스칼럽 가장자리.
 */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    fillHeight: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    shape: StickerShape = stickerShape(),
    fill: Color = AppColors.GlassFill,
    outline: Color = AppColors.StickerOutline,
    content: @Composable ColumnScope.() -> Unit
) {
    // shadow 는 값싼 둥근 rect shape 만 쓰고, 무거운 스칼럽 path 는 clip/border 에만 쓴다
    // (무한 애니메이션과 조합되면 path shadow 재래스터라이즈가 ANR 을 유발한다).
    val shadowShape = RoundedCornerShape(AppColors.BlockRadius)
    var blockModifier = modifier
        .fillMaxWidth()
        .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
        .shadow(14.dp, shadowShape, ambientColor = AppColors.BlockShadow, spotColor = AppColors.BlockShadow, clip = false)
        .clip(shape)
        .background(fill, shape)
        .border(StickerBorderWidth, outline, shape)
    if (onClick != null) blockModifier = blockModifier.clickable(onClick = onClick)
    Box(modifier = blockModifier) {
        Column(
            modifier = if (fillHeight) Modifier.fillMaxSize().padding(contentPadding)
            else Modifier.padding(contentPadding),
            verticalArrangement = if (fillHeight) Arrangement.SpaceBetween else Arrangement.Top,
            content = content
        )
    }
}

/** rounded-rect 스티커 블록 — 카드 안 중첩 필드/서브 블록용. */
@Composable
fun InnerCard(
    modifier: Modifier = Modifier,
    fill: Color = AppColors.ConfigTile,
    radius: androidx.compose.ui.unit.Dp = AppColors.TileRadius,
    outline: Color = Color.Transparent,
    outlineWidth: androidx.compose.ui.unit.Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    var mod = modifier.clip(shape).background(fill, shape)
    if (outline != Color.Transparent && outlineWidth > 0.dp) {
        mod = mod.border(outlineWidth, outline, shape)
    }
    Box(modifier = mod) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/** 절(header) — icon chip + title. 우측 액션 슬롯. */
@Composable
fun SectionHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconChip(icon = icon)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

/** 절(header) — vector asset 아이콘 버전. */
@Composable
fun SectionHeaderAsset(
    @DrawableRes iconRes: Int,
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconChipAsset(iconRes = iconRes)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

/** 원형 아이콘 배경 칩 — vector icon. */
@Composable
fun IconChip(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = AppColors.PrimaryDeep,
    background: Color = AppColors.ConfigTile
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .background(background, shape = CircleShape)
            .border(1.5.dp, AppColors.StickerOutline.copy(alpha = 0.28f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
    }
}

/** 원형 아이콘 배경 칩 — kawaii vector asset. */
@Composable
fun IconChipAsset(
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 36.dp,
) {
    Box(
        modifier = modifier.size(size).background(AppColors.StickerFill, CircleShape)
            .border(1.5.dp, AppColors.StickerOutline.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(size - 12.dp)
        )
    }
}

/** 상태 알약 — 점 + 텍스트. */
@Composable
fun StatusPill(
    ok: Boolean,
    label: String,
    modifier: Modifier = Modifier
) {
    val col = if (ok) AppColors.SuccessVivid else AppColors.ErrorVivid
    Row(
        modifier = modifier
            .background(col.copy(alpha = 0.16f), RoundedCornerShape(50))
            .border(1.5.dp, col.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(col, shape = CircleShape)
                .border(1.dp, AppColors.StickerOutline.copy(alpha = 0.2f), CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = col
        )
    }
}

/** 하나의 stat 타일 값. iconRes 는 kawaii vector asset. */
data class StatItem(
    val label: String,
    val value: String,
    val icon: ImageVector? = null,
    @DrawableRes val iconRes: Int = 0,
    val valueColor: Color = AppColors.TextMain,
    val onClick: (() -> Unit)? = null
)

/** 값쌍을 kawaii 타일 그리드로. */
@Composable
fun StatTiles(
    vararg items: StatItem,
    columns: Int = 2,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val shape = RoundedCornerShape(AppColors.TileRadius)
    val tileFill = if (items.any { it.onClick != null }) AppColors.ConfigTile else AppColors.GlassFillStrong
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (fillHeight) Modifier.fillMaxSize() else Modifier)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items.toList().chunked(columns).forEach { rowItems ->
            val rowModifier = if (fillHeight) Modifier.weight(1f) else Modifier
            Row(
                modifier = rowModifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowItems.forEach { item ->
                    var cellModifier = Modifier.weight(1f)
                        .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier.height(IntrinsicSize.Min))
                    if (item.onClick != null) cellModifier = cellModifier.clip(shape).clickable(onClick = item.onClick)
                    Column(
                        modifier = cellModifier.background(tileFill, shape = shape)
                            .border(1.5.dp, AppColors.StickerOutline.copy(alpha = 0.12f), shape)
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (item.iconRes != 0 || item.icon != null) {
                            val chipMod = Modifier.size(40.dp)
                                .background(AppColors.StickerFill, CircleShape)
                                .border(1.5.dp, AppColors.StickerOutline.copy(alpha = 0.16f), CircleShape)
                            Box(modifier = chipMod, contentAlignment = Alignment.Center) {
                                if (item.iconRes != 0) {
                                    Image(painter = painterResource(item.iconRes), contentDescription = null,
                                        modifier = Modifier.size(28.dp))
                                } else if (item.icon != null) {
                                    Icon(item.icon, contentDescription = null, tint = AppColors.PrimaryDeep,
                                        modifier = Modifier.size(19.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        Box(
                            modifier = Modifier.fillMaxWidth().then(if (fillHeight) Modifier.weight(1f) else Modifier),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                item.value,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = AppTypography.RoundNumeric,
                                    letterSpacing = 0.4.sp
                                ),
                                fontWeight = FontWeight.Bold,
                                color = item.valueColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                letterSpacing = 0.6.sp, fontWeight = FontWeight.Bold
                            ),
                            color = AppColors.TextSub,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                repeat(columns - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * StickerSwitch — kawaii 토글. 알약 트랙 + 얼굴 손잡이.
 */
@Composable
fun StickerSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    faceRes: Int = 0
) {
    val thumbSize = 32.dp
    val width = 68.dp
    val height = 38.dp
    val trackShape = RoundedCornerShape(50)
    val bg = if (checked) AppColors.Blush else AppColors.ConfigTile

    val animX = animateFloatAsState(targetValue = if (checked) 1f else 0f, label = "thumb").value
    val shift = (width - thumbSize) - 8.dp

    Box(
        modifier = modifier
            .size(width, height)
            .clip(trackShape)
            .background(bg, trackShape)
            .border(2.dp, AppColors.StickerOutline.copy(alpha = 0.35f), trackShape)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = shift * animX)
                .size(thumbSize)
                .clip(CircleShape)
                .background(AppColors.StickerFill, CircleShape)
                .border(2.dp, AppColors.StickerOutline.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (faceRes != 0) {
                Image(
                    painter = painterResource(faceRes),
                    contentDescription = null,
                    modifier = Modifier.size(thumbSize - 8.dp)
                )
            } else {
                val ink = AppColors.StickerOutline
                Canvas(Modifier.size(thumbSize)) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val er = size.width * 0.14f
                    drawCircle(ink.copy(alpha = 0.8f), radius = er, center = androidx.compose.ui.geometry.Offset(cx - er * 1.8f, cy - er * 0.6f))
                    drawCircle(ink.copy(alpha = 0.8f), radius = er, center = androidx.compose.ui.geometry.Offset(cx + er * 1.8f, cy - er * 0.6f))
                    val mouth = androidx.compose.ui.graphics.Path().apply {
                        moveTo(cx - er * 1.6f, cy + er * 1.0f)
                        quadraticBezierTo(cx, cy + er * 2.6f, cx + er * 1.6f, cy + er * 1.0f)
                    }
                    drawPath(mouth, ink.copy(alpha = 0.8f), style = Stroke(width = size.width * 0.06f, cap = StrokeCap.Round))
                }
            }
        }
    }
}
