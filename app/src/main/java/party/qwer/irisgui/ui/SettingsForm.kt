package party.qwer.irisgui.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import party.qwer.irisgui.AppColors

/**
 * SettingsForm — 탭 화면 공용 폼 부품.
 *
 * ConfigScreen / NonRootDashboardScreen / AdbConfigScreen 이 각각 반복하던
 * `OutlinedTextFieldDefaults.colors(...)` + shape 블록을 하나로 수렴시킨다.
 * 색은 AppColors 디자인 시스템 고정.
 */

/** 공용 OutlinedTextField 색 정의. */
@Composable
fun irisFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AppColors.PrimaryAccent,
    unfocusedBorderColor = AppColors.TextSub.copy(alpha = 0.3f),
    focusedTextColor = AppColors.TextMain,
    unfocusedTextColor = AppColors.TextMain,
    focusedLabelColor = AppColors.PrimaryAccent,
    unfocusedLabelColor = AppColors.TextSub,
    cursorColor = AppColors.PrimaryAccent
)

/** 공용 설정 입력 필드. `trailing` 은 저장 버튼 등 우측 요소를 넣을 때 사용. */
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
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default
    if (trailing != null) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            leadingIcon = leadingIcon,
            trailingIcon = trailing,
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = irisFieldColors()
        )
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            leadingIcon = leadingIcon,
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = irisFieldColors()
        )
    }
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (leadingIcon != null || trailing != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    leadingIcon?.let { it() }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextMain
                    )
                    if (trailing != null) {
                        Spacer(modifier = Modifier.weight(1f))
                        trailing()
                    }
                }
            } else {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextMain
                )
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
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent, contentColor = AppColors.TextMain),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = modifier
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
    }
}
