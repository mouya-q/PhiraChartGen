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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val ez = Color(0xFF319A68)
    val hd = Color(0xFF347EF4)
    val inn = Color(0xFFD94C59)
    val at = Color(0xFF8064B8)
    val ok = Color(0xFF319A68)
    val warn = Color(0xFFD69524)
    val bad = Color(0xFFD94C59)
    val pageLight = Color(0xFFF5F6F8)
    val pageDark = Color(0xFF101114)
    val surfaceLight = Color(0xFFFFFFFF)
    val surfaceDark = Color(0xFF1B1D22)
    val lineLight = Color(0xFFE8EAEF)
    val lineDark = Color(0xFF30333A)
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
fun Summary(text: String, modifier: Modifier = Modifier, align: TextAlign? = null) {
    Txt(text, modifier, MiuixTheme.textStyles.footnote1, MiuixTheme.colorScheme.onSurfaceVariantSummary, align)
}

@Composable
fun PageTitle(
    title: String,
    subtitle: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp, top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(
                title,
                Modifier.weight(1f),
                style = MiuixTheme.textStyles.title1.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (action != null) Row(content = action)
        }
        if (subtitle != null) Summary(subtitle)
    }
}

@Composable
fun PageBackground(dark: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(if (dark) Palette.pageDark else Palette.pageLight))
}

@Composable
fun PButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
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
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Txt(title, style = MiuixTheme.textStyles.body1)
            if (summary != null) Summary(summary)
        }
        Spacer(Modifier.width(14.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun PSliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    summary: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(title, Modifier.weight(1f), MiuixTheme.textStyles.body1)
            Txt(valueText, color = MiuixTheme.colorScheme.primary)
        }
        if (summary != null) {
            Spacer(Modifier.height(3.dp))
            Summary(summary)
        }
        Spacer(Modifier.height(9.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
fun PField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    TextField(
        value = value,
        onValueChange = onChange,
        label = label,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Txt(
            title,
            Modifier.padding(start = 24.dp, top = 22.dp, end = 20.dp, bottom = 8.dp),
            MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.SemiBold),
            MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Card(Modifier.padding(horizontal = 14.dp).fillMaxWidth()) {
            Column { content() }
        }
    }
}

@Composable
fun SettingsDivider() {
    Box(
        Modifier.fillMaxWidth().padding(start = 18.dp).height(1.dp)
            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
    )
}

@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(shape)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.055f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, label) in options) {
            val active = value == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (active) MiuixTheme.colorScheme.surface else Color.Transparent)
                    .then(if (active) Modifier.border(1.dp, Palette.hd.copy(alpha = 0.12f), RoundedCornerShape(11.dp)) else Modifier)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(
                    label,
                    style = MiuixTheme.textStyles.body2.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
fun SurfaceCard(dark: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val surface = if (dark) Palette.surfaceDark else Palette.surfaceLight
    val line = if (dark) Palette.lineDark else Palette.lineLight
    Box(
        modifier.clip(shape).background(surface).border(BorderStroke(1.dp, line.copy(alpha = 0.8f)), shape),
    ) { content() }
}

@Composable
fun Chip(text: String, color: Color, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier.clip(shape)
            .background(color.copy(alpha = if (selected) 0.14f else 0.07f))
            .border(1.dp, color.copy(alpha = if (selected) 0.32f else 0.12f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(
            text,
            style = MiuixTheme.textStyles.body2.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = color,
        )
    }
}

@Composable
fun Banner(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(text, color = color, style = MiuixTheme.textStyles.body2)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    cancel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
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
