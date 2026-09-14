package party.qwer.irisgui.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import party.qwer.irisgui.AppColors

/** 화면 카드 공용 스페이스/반경. */
val CardCornerRadius = 20.dp
val ScreenPadding = 16.dp

/** 표준 light 카드. 화면 어디서나 같은 형태(흰 면 + 은은한 구분선 + 20dp)를 쓴다. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(CardCornerRadius)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        border = BorderStroke(1.dp, AppColors.CardBorder),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = if (onClick != null) Modifier
                .padding(contentPadding)
                .fillMaxWidth()
                .clickable(onClick = onClick)
            else Modifier.padding(contentPadding),
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
    background: Color = AppColors.InputBg
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
                color = if (ok) AppColors.SuccessVivid.copy(alpha = 0.10f) else AppColors.ErrorVivid.copy(alpha = 0.10f),
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
    contentPadding: androidx.compose.foundation.layout.PaddingValues = PaddingValues(0.dp)
) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxWidth().padding(contentPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.toList().chunked(columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowItems.forEach { item ->
                    var cellModifier = Modifier.weight(1f).height(IntrinsicSize.Min)
                    if (item.onClick != null) cellModifier = cellModifier.clickable(onClick = item.onClick)
                    Column(
                        modifier = cellModifier.background(AppColors.InputBg, shape = shape)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (item.icon != null) {
                                Icon(
                                    item.icon, contentDescription = null,
                                    tint = AppColors.TextSub, modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                item.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = AppColors.TextSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            item.value,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = item.valueColor,
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
