package com.example.knowhub.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.knowhub.core.model.DocumentStatus

// ------------------------------------------------------------------ 色板

val Ink: Color = Color(0xFF101828)
val Muted: Color = Color(0xFF667085)
val Faint: Color = Color(0xFF98A2B3)
val Line: Color = Color(0xFFEAECF0)
val Paper: Color = Color(0xFFF7F8FC)
val Brand: Color = Color(0xFF3056D3)
val BrandDeep: Color = Color(0xFF1D3AA8)
val BrandSoft: Color = Color(0xFFEDF1FF)
val Teal: Color = Color(0xFF0F766E)
val TealSoft: Color = Color(0xFFE7F7F4)
val Amber: Color = Color(0xFFB54708)
val Danger: Color = Color(0xFFD92D20)

private val KnowHubColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = BrandSoft,
    onPrimaryContainer = BrandDeep,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = TealSoft,
    onSecondaryContainer = Color(0xFF064E47),
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF2F4F7),
    onSurfaceVariant = Muted,
    outline = Line,
    outlineVariant = Color(0xFFF2F4F7),
    error = Danger,
    errorContainer = Color(0xFFFEF3F2),
    onErrorContainer = Color(0xFF7A271A),
)

private val BaseTypography = Typography(FontFamily.SansSerif)

private val KnowHubTypography = BaseTypography.copy(
    displaySmall = BaseTypography.displaySmall.copy(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineMedium = BaseTypography.headlineMedium.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = BaseTypography.titleLarge.copy(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontSize = 16.5.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.copy(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = BaseTypography.bodyLarge.copy(fontSize = 15.5.sp, lineHeight = 25.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(fontSize = 14.5.sp, lineHeight = 23.sp),
    bodySmall = BaseTypography.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = BaseTypography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = BaseTypography.labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
)

private val KnowHubShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun KnowHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KnowHubColors,
        typography = KnowHubTypography,
        shapes = KnowHubShapes,
        content = content,
    )
}

// ------------------------------------------------------------------ 基础组件

@Composable
fun BrandMark(size: Dp = 36.dp, corner: Dp = 11.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(Brand, Color(0xFF6B87FF)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "K",
            color = Color.White,
            fontSize = (size.value * 0.46f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    padding: Dp = 14.dp,
    background: Color = Color.White,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = background,
        border = BorderStroke(1.dp, Line),
    ) {
        Column(modifier = Modifier.padding(padding), content = content)
    }
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Line))
}

@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: Glyph? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Brand else Color.White)
            .border(1.dp, if (selected) Color.Transparent else Line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            GlyphIcon(leading, size = 14.dp, tint = if (selected) Color.White else Muted)
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            color = if (selected) Color.White else Ink,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
fun IconAction(
    glyph: Glyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Ink,
    background: Color = Color.Transparent,
) {
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, size = 18.dp, tint = tint)
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Ink)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
        action?.invoke()
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 6.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun StatusPill(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color, 5.dp)
        Spacer(Modifier.width(5.dp))
        Text(text, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun MetricCard(label: String, value: String, glyph: Glyph, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(glyph, size = 14.dp, tint = Faint)
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = Ink)
    }
}

@Composable
fun InlineBanner(text: String, isError: Boolean, onClose: () -> Unit) {
    val background = if (isError) MaterialTheme.colorScheme.errorContainer else TealSoft
    val foreground = if (isError) MaterialTheme.colorScheme.onErrorContainer else Color(0xFF064E47)
    Surface(color = background, shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, color = foreground, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) {
                Text("知道了", color = foreground, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun EmptyHint(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
    )
}

fun statusColor(status: String): Color = when (status) {
    DocumentStatus.INDEXED -> Color(0xFF15803D)
    DocumentStatus.PARSING -> Amber
    DocumentStatus.FAILED -> Danger
    else -> Muted
}

fun statusLabel(status: String): String = when (status) {
    DocumentStatus.INDEXED -> "已索引"
    DocumentStatus.PARSING -> "解析中"
    DocumentStatus.FAILED -> "失败"
    else -> status
}

/** 院内角色展示名：admin=医务处，hr=人事科，member=职工。 */
fun roleLabel(role: String): String = when (role) {
    "admin" -> "医务处"
    "hr" -> "人事科"
    "member" -> "职工"
    "patient" -> "患者"
    else -> role
}

fun formatScore(value: Double): String {
    val clamped = value.coerceIn(0.0, 0.99)
    val scaled = (clamped * 100).toInt()
    return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
}
