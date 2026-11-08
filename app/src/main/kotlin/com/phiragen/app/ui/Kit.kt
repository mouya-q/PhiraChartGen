package com.phiragen.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import com.phiragen.app.data.ThemeMode

/*
 * 对 Miuix 组件的薄封装. 全部界面只通过这里使用 Miuix,
 * 升级 Miuix 版本时的接口变动只需要改这一个文件
 */

@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val controller = remember(mode) {
        ThemeController(
            when (mode) {
                ThemeMode.SYSTEM -> ColorSchemeMode.System
                ThemeMode.LIGHT -> ColorSchemeMode.Light
                ThemeMode.DARK -> ColorSchemeMode.Dark
            },
        )
    }
    MiuixTheme(controller = controller, content = content)
}

@Composable
fun isDarkNow(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

object Palette {
    val ez = Color(0xFF3FB950)
    val hd = Color(0xFF3482FF)
    val inn = Color(0xFFE5484D)
    val at = Color(0xFF8E7CC3)
    val ok = Color(0xFF3FB950)
    val warn = Color(0xFFE5A100)
    val bad = Color(0xFFE5484D)
}

@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MiuixTheme.textStyles.body2,
    color: Color = MiuixTheme.colorScheme.onSurface,
    align: TextAlign? = null,
) {
    Text(text = text, modifier = modifier, style = style, color = color, textAlign = align)
}

@Composable
fun Summary(text: String, modifier: Modifier = Modifier) {
    Txt(text, modifier, MiuixTheme.textStyles.footnote1, MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
fun PButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
    ) {
        Text(
            text = text,
            color = if (primary) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun PTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(text = text, onClick = onClick, modifier = modifier)
}

@Composable
fun PTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = title,
        navigationIcon = { if (onBack != null) PTextButton("返回", onBack) },
        actions = actions,
    )
}

@Composable
fun PScaffold(topBar: @Composable () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = topBar, content = content)
}

@Composable
fun PSwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, style = MiuixTheme.textStyles.body1)
            if (summary != null) Summary(summary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun PSliderRow(title: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, summary: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(title, Modifier.weight(1f), MiuixTheme.textStyles.body1)
            Txt(valueText, color = MiuixTheme.colorScheme.primary)
        }
        if (summary != null) Summary(summary)
        Spacer(Modifier.height(6.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
fun PField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, secret: Boolean = false) {
    TextField(
        value = value,
        onValueChange = onChange,
        label = label,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Txt(
        title,
        Modifier.padding(start = 28.dp, top = 18.dp, bottom = 6.dp),
        MiuixTheme.textStyles.footnote1,
        MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
    Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) { Column { content() } }
}

/** 分段选择, 用于主题, 格式等少量互斥选项 */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) {
            PButton(label, { onSelect(value) }, Modifier.weight(1f), primary = value == selected)
        }
    }
}

// ------------------------------------------------------------ 玻璃效果

/** 以封面为底的高斯模糊背景, 低于 Android 12 时 blur 不生效, 退化为压暗的封面 */
@Composable
fun GlassBackdrop(cover: ImageBitmap?, enabled: Boolean, dark: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        if (cover != null) {
            androidx.compose.foundation.Image(
                bitmap = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (enabled) Modifier.blur(48.dp) else Modifier),
            )
            Box(Modifier.fillMaxSize().background(if (dark) Color(0xB0000000) else Color(0xB0FFFFFF)))
        } else {
            val a = if (dark) Color(0xFF16233A) else Color(0xFFDCE8FF)
            val b = if (dark) Color(0xFF000000) else Color(0xFFF7F7F7)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(a, b))))
        }
    }
}

/** 半透明磨砂卡片: 浅色渐变填充加细高光描边 */
@Composable
fun GlassCard(dark: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val fill = if (dark) listOf(Color(0x33FFFFFF), Color(0x14FFFFFF)) else listOf(Color(0xCCFFFFFF), Color(0x99FFFFFF))
    val edge = if (dark) Color(0x40FFFFFF) else Color(0x80FFFFFF)
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(fill))
            .border(BorderStroke(1.dp, edge), shape),
    ) { content() }
}

@Composable
fun Chip(text: String, color: Color, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) color else color.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, style = MiuixTheme.textStyles.body2, color = if (selected) Color.White else color)
    }
}

@Composable
fun Banner(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.14f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(text, color = color, style = MiuixTheme.textStyles.body2)
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, cancel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Txt(title, style = MiuixTheme.textStyles.title3)
                Spacer(Modifier.height(10.dp))
                Txt(body, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PButton(cancel, onDismiss, Modifier.weight(1f), primary = false)
                    PButton(confirm, onConfirm, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun Dot(color: Color) {
    Box(Modifier.width(8.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
}
