package party.qwer.irisgui.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import party.qwer.irisgui.AppColors

/** 화면 블록 공용 스페이스/반경. */
val ScreenPadding = 16.dp

/**
 * 표준 glass 블록 — 반투명 흰 면 + 은은한 흰색 하이라이트 구분선 + 22dp.
 * 배경 블롭이 면 아래로 살짝 비치므로 화면 어디에서 같은 형태를 쓴다.
 */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    fillHeight: Boolean = false,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(AppColors.BlockRadius)
    val blockModifier = modifier
        .fillMaxWidth()
        .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
        .shadow(14.dp, shape, ambientColor = AppColors.BlockShadow, spotColor = AppColors.BlockShadow, clip = false)
        .clip(shape)
        .background(AppColors.GlassFill, shape)
    Box(
        modifier = if (onClick != null) blockModifier.clickable(onClick = onClick) else blockModifier
    ) {
        Column(
            modifier = if (fillHeight) Modifier.fillMaxSize().padding(contentPadding)
            else Modifier.padding(contentPadding),
            verticalArrangement = if (fillHeight) Arrangement.SpaceBetween else Arrangement.Top,
            content = content
        )
    }
}

/** 절(header) — icon chip + title. 우측 액션(예: 새로고침/View all) 슬롯 가능. */
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
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        trailing?.invoke()
    }
}

/** 원형 아이콘 배경 칩 — 절/행 앞에 붙는 통일 아이콘. */
@Composable
fun IconChip(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = AppColors.PrimaryAccent,
    background: Color = AppColors.PrimaryAccent.copy(alpha = 0.13f)
) {
    Box(
        modifier = modifier
            .size(34.dp)
            .background(background, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 상태 알약 — 초록/빨래 점 + 텍스트. fill 된 폭을 쓰지 않고 필요한 만큼만 차지한다. */
@Composable
fun StatusPill(
    ok: Boolean,
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                color = (if (ok) AppColors.SuccessVivid else AppColors.ErrorVivid).copy(alpha = 0.15f),
                shape = RoundedCornerShape(50)
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(
                    color = if (ok) AppColors.SuccessVivid else AppColors.ErrorVivid,
                    shape = CircleShape
                )
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (ok) AppColors.SuccessVivid else AppColors.ErrorVivid
        )
    }
}

/** 하나의 stat 타일 값. onClick 이 있으면 타일을 탭 가능한 편집 셀로 만든다. */
data class StatItem(
    val label: String,
    val value: String,
    val icon: ImageVector? = null,
    val valueColor: Color = AppColors.TextMain,
    val onClick: (() -> Unit)? = null
)

/**
 * 값쌍을 가로폭에 맞춰 타일로 나열. 행을 좌/우 양 끝으로 벌리는(SpaceBetween) 방식의
 * "한 카드 = 한 행" 레이아웃을 대체한다. span=2 로 타일 하나가 행 전체를 쓸 수 있다.
 */
@Composable
fun StatTiles(
    vararg items: StatItem,
    columns: Int = 2,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = PaddingValues(0.dp)
) {
    val shape = RoundedCornerShape(AppColors.TileRadius)
    val tileFill = if (items.any { it.onClick != null }) AppColors.ConfigTile else AppColors.GlassFillStrong
    Column(
        modifier.fillMaxWidth().then(if (fillHeight) Modifier.fillMaxSize() else Modifier)
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
                        .then(if (fillHeight) Modifier.fillMaxHeight()
                              else Modifier.height(IntrinsicSize.Min))
                    // clip 은 clickable 앞에 와야 ripple/press 하이라이트가 모서리 둥근 사각형에
                    // 맞춰 그려진다. (clickable 뒤 background(shape) 는 그리기만 둥글게 하고 하이라이트 clip 은 각진 채로 남는다)
                    if (item.onClick != null) cellModifier = cellModifier.clip(shape).clickable(onClick = item.onClick)
                    // 타일 구성: 위 — icon chip / 한복판 — 값(숫자는 모노, 액센트 잉크) / 아래 — 캡션 라벨.
                    Column(
                        modifier = cellModifier.background(tileFill, shape = shape)
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (item.icon != null) {
                            IconChip(
                                icon = item.icon,
                                modifier = Modifier.size(30.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .then(if (fillHeight) Modifier.weight(1f) else Modifier),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                item.value,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = FontFamily.Monospace,
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
                                letterSpacing = 0.6.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = AppColors.TextSub,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 마지막 행이 columns 로 안 맞으면 빈 셀로 폭 정렬 유지
                repeat(columns - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
