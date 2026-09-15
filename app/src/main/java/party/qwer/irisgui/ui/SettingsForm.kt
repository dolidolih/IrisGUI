package party.qwer.irisgui.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import party.qwer.irisgui.AppColors

/**
 * SettingsForm — 탭 화면 공용 폼 부품.
 *
 * ConfigScreen / NonRootDashboardScreen / AdbConfigScreen 이 각각 반복하던 색/shape 블록을 하나로 수렴시킨다.
 */

/** 공용 OutlinedTextField 색 정의. */
@Composable
fun irisFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AppColors.PrimaryAccent,
    unfocusedBorderColor = AppColors.GlassStroke,
    unfocusedContainerColor = AppColors.GlassInputBg,
    focusedContainerColor = AppColors.GlassInputBg,
    disabledContainerColor = AppColors.GlassInputBg,
    focusedTextColor = AppColors.TextMain,
    unfocusedTextColor = AppColors.TextMain,
    focusedLabelColor = AppColors.PrimaryAccent,
    unfocusedLabelColor = AppColors.TextSub,
    cursorColor = AppColors.PrimaryAccent
)

/** 공용 설정 입력 필드. */
@Composable
fun SettingsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    singleLine: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = leadingIcon,
        trailingIcon = trailing,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = irisFieldColors()
    )
}

/** 공용 설정 카드 (Title + 내용). */
@Composable
fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBg),
        border = BorderStroke(1.dp, AppColors.CardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (leadingIcon != null || trailing != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    leadingIcon?.let { it() }
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
                    if (trailing != null) {
                        Spacer(modifier = Modifier.weight(1f))
                        trailing()
                    }
                }
            } else {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
            }
            content()
        }
    }
}

/** 공용 저장 버튼. */
@Composable
fun SettingsSaveButton(onClick: () -> Unit, modifier: Modifier = Modifier, text: String = "저장") {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = modifier
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** 방 라벨: 이름+chatid. */
fun roomLabel(id: String, name: String): String = when {
    name.isBlank() -> id
    name == id -> name
    else -> "$name ($id)"
}

/**
 * 답장 테스트용 방 드롭다운.
 *
 * - 방 목록이 있으면 라벨이 붙은 read-only 드롭다운. 방 목록이 아직 비어 있으면(미수신/미조회)
 *   id를 직접 입력하는 필드로 폴백한다.
 * @param rooms (id, name) 페어
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomDropdownField(
    selectedId: String,
    rooms: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "방"
) {
    if (rooms.isEmpty()) {
        SettingsField(
            label = "$label (방 ID 직접 입력)",
            value = selectedId,
            onValueChange = onSelect,
            modifier = modifier
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val display = rooms.firstOrNull { it.first == selectedId }?.let { (id, name) -> roomLabel(id, name) } ?: selectedId
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = irisFieldColors()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(AppColors.BlockRadius),
            containerColor = AppColors.GlassFill,
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, AppColors.GlassStroke)
        ) {
            rooms.distinctBy { it.first }
                .sortedWith(compareBy({ roomLabel(it.first, it.second) }, { it.first }))
                .forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(roomLabel(id, name)) },
                        onClick = { onSelect(id); expanded = false }
                    )
                }
        }
    }
}
