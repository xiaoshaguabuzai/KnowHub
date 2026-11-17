package com.example.knowhub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Glyph {
    Chat, Book, Sliders, Send, Plus, Back, Search, Doc, Sparkle, Close, Refresh, ArrowRight, More, ChevronDown,
    Eye, EyeOff, Mic, Camera, Paperclip,
}

/**
 * 轻量线性图标：用 Canvas 画 24x24 网格路径，避免引入额外的图标依赖，
 * 同时保证四端渲染一致（不依赖 emoji 字体）。
 */
@Composable
fun GlyphIcon(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = Color.Unspecified,
    strokeWidth: Dp = 1.7.dp,
) {
    val color = if (tint == Color.Unspecified) LocalContentColor.current else tint
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        fun o(x: Float, y: Float) = Offset(x / 24f * s, y / 24f * s)
        fun len(v: Float) = v / 24f * s
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (glyph) {
            Glyph.Chat -> {
                drawRoundRect(
                    color = color,
                    topLeft = o(3f, 4f),
                    size = Size(len(18f), len(13f)),
                    cornerRadius = CornerRadius(len(4f), len(4f)),
                    style = stroke,
                )
                drawPath(
                    path = Path().apply {
                        moveTo(len(8f), len(17f))
                        lineTo(len(9f), len(21f))
                        lineTo(len(13f), len(17f))
                    },
                    color = color,
                    style = stroke,
                )
            }

            Glyph.Book -> {
                drawPath(
                    path = Path().apply {
                        moveTo(len(3f), len(6.5f))
                        cubicTo(len(6f), len(4.8f), len(9f), len(4.8f), len(12f), len(6.5f))
                        cubicTo(len(15f), len(4.8f), len(18f), len(4.8f), len(21f), len(6.5f))
                        lineTo(len(21f), len(17.5f))
                        cubicTo(len(18f), len(15.8f), len(15f), len(15.8f), len(12f), len(17.5f))
                        cubicTo(len(9f), len(15.8f), len(6f), len(15.8f), len(3f), len(17.5f))
                        close()
                    },
                    color = color,
                    style = stroke,
                )
                drawLine(color, o(12f, 6.5f), o(12f, 17.5f), strokeWidth.toPx(), StrokeCap.Round)
            }

            Glyph.Sliders -> {
                for (y in floatArrayOf(6f, 12f, 18f)) {
                    drawLine(color, o(4f, y), o(20f, y), strokeWidth.toPx(), StrokeCap.Round)
                }
                drawCircle(color, len(2.1f), o(9f, 6f))
                drawCircle(color, len(2.1f), o(15f, 12f))
                drawCircle(color, len(2.1f), o(8f, 18f))
            }

            Glyph.Send -> {
                drawLine(color, o(12f, 19.5f), o(12f, 5f), strokeWidth.toPx(), StrokeCap.Round)
                drawPath(
                    path = Path().apply {
                        moveTo(len(6f), len(11f))
                        lineTo(len(12f), len(5f))
                        lineTo(len(18f), len(11f))
                    },
                    color = color,
                    style = stroke,
                )
            }

            Glyph.Plus -> {
                drawLine(color, o(12f, 5f), o(12f, 19f), strokeWidth.toPx(), StrokeCap.Round)
                drawLine(color, o(5f, 12f), o(19f, 12f), strokeWidth.toPx(), StrokeCap.Round)
            }

            Glyph.Back -> drawPath(
                path = Path().apply {
                    moveTo(len(14.5f), len(5.5f))
                    lineTo(len(7.5f), len(12f))
                    lineTo(len(14.5f), len(18.5f))
                },
                color = color,
                style = stroke,
            )

            Glyph.Search -> {
                drawCircle(color, len(6f), o(10.5f, 10.5f), style = stroke)
                drawLine(color, o(15f, 15f), o(20f, 20f), strokeWidth.toPx(), StrokeCap.Round)
            }

            Glyph.Doc -> {
                drawPath(
                    path = Path().apply {
                        moveTo(len(6f), len(3f))
                        lineTo(len(14f), len(3f))
                        lineTo(len(19f), len(8f))
                        lineTo(len(19f), len(20f))
                        quadraticBezierTo(len(19f), len(21f), len(18f), len(21f))
                        lineTo(len(6f), len(21f))
                        quadraticBezierTo(len(5f), len(21f), len(5f), len(20f))
                        lineTo(len(5f), len(4f))
                        quadraticBezierTo(len(5f), len(3f), len(6f), len(3f))
                        close()
                    },
                    color = color,
                    style = stroke,
                )
                drawPath(
                    path = Path().apply {
                        moveTo(len(14f), len(3f))
                        lineTo(len(14f), len(8f))
                        lineTo(len(19f), len(8f))
                    },
                    color = color,
                    style = stroke,
                )
            }

            Glyph.Sparkle -> drawPath(
                path = Path().apply {
                    moveTo(len(12f), len(2.5f))
                    lineTo(len(14.3f), len(9.7f))
                    lineTo(len(21.5f), len(12f))
                    lineTo(len(14.3f), len(14.3f))
                    lineTo(len(12f), len(21.5f))
                    lineTo(len(9.7f), len(14.3f))
                    lineTo(len(2.5f), len(12f))
                    lineTo(len(9.7f), len(9.7f))
                    close()
                },
                color = color,
            )

            Glyph.Close -> {
                drawLine(color, o(6f, 6f), o(18f, 18f), strokeWidth.toPx(), StrokeCap.Round)
                drawLine(color, o(18f, 6f), o(6f, 18f), strokeWidth.toPx(), StrokeCap.Round)
            }

            Glyph.Refresh -> {
                drawArc(
                    color = color,
                    startAngle = -35f,
                    sweepAngle = 275f,
                    useCenter = false,
                    topLeft = o(4.5f, 4.5f),
                    size = Size(len(15f), len(15f)),
                    style = stroke,
                )
                drawPath(
                    path = Path().apply {
                        moveTo(len(14.5f), len(4.6f))
                        lineTo(len(19.2f), len(5.6f))
                        lineTo(len(18.2f), len(10.3f))
                    },
                    color = color,
                    style = stroke,
                )
            }

            Glyph.ArrowRight -> drawPath(
                path = Path().apply {
                    moveTo(len(9f), len(6f))
                    lineTo(len(15f), len(12f))
                    lineTo(len(9f), len(18f))
                },
                color = color,
                style = stroke,
            )

            Glyph.More -> {
                drawCircle(color, len(1.6f), o(6f, 12f))
                drawCircle(color, len(1.6f), o(12f, 12f))
                drawCircle(color, len(1.6f), o(18f, 12f))
            }

            Glyph.ChevronDown -> drawPath(
                path = Path().apply {
                    moveTo(len(6f), len(9.5f))
                    lineTo(len(12f), len(15.5f))
                    lineTo(len(18f), len(9.5f))
                },
                color = color,
                style = stroke,
            )

            Glyph.Eye -> {
                drawPath(
                    path = Path().apply {
                        moveTo(len(2f), len(12f))
                        quadraticBezierTo(len(12f), len(4f), len(22f), len(12f))
                        quadraticBezierTo(len(12f), len(20f), len(2f), len(12f))
                        close()
                    },
                    color = color,
                    style = stroke,
                )
                drawCircle(color, len(3f), o(12f, 12f), style = stroke)
            }

            Glyph.EyeOff -> {
                drawPath(
                    path = Path().apply {
                        moveTo(len(2f), len(12f))
                        quadraticBezierTo(len(12f), len(4f), len(22f), len(12f))
                        quadraticBezierTo(len(12f), len(20f), len(2f), len(12f))
                        close()
                    },
                    color = color,
                    style = stroke,
                )
                drawCircle(color, len(2.6f), o(12f, 12f), style = stroke)
                drawLine(color, o(4f, 20f), o(20f, 4f), strokeWidth.toPx(), StrokeCap.Round)
            }

            Glyph.Mic -> {
                drawRoundRect(
                    color = color,
                    topLeft = o(9f, 3f),
                    size = Size(len(6f), len(11f)),
                    cornerRadius = CornerRadius(len(3f), len(3f)),
                    style = stroke,
                )
                drawArc(
                    color = color,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = o(6f, 9f),
                    size = Size(len(12f), len(10f)),
                    style = stroke,
                )
                drawLine(color, o(12f, 19f), o(12f, 22f), strokeWidth.toPx(), StrokeCap.Round)
                drawLine(color, o(8f, 22f), o(16f, 22f), strokeWidth.toPx(), StrokeCap.Round)
            }

            Glyph.Camera -> {
                drawRoundRect(
                    color = color,
                    topLeft = o(3f, 7f),
                    size = Size(len(18f), len(13f)),
                    cornerRadius = CornerRadius(len(3f), len(3f)),
                    style = stroke,
                )
                drawPath(
                    path = Path().apply {
                        moveTo(len(8f), len(7f))
                        lineTo(len(9.6f), len(4f))
                        lineTo(len(14.4f), len(4f))
                        lineTo(len(16f), len(7f))
                    },
                    color = color,
                    style = stroke,
                )
                drawCircle(color, len(3.4f), o(12f, 13.5f), style = stroke)
            }

            Glyph.Paperclip -> drawPath(
                path = Path().apply {
                    moveTo(len(20.5f), len(11.5f))
                    lineTo(len(12.5f), len(19.5f))
                    quadraticBezierTo(len(9f), len(23f), len(5.5f), len(19.5f))
                    quadraticBezierTo(len(2f), len(16f), len(5.5f), len(12.5f))
                    lineTo(len(14f), len(4f))
                    quadraticBezierTo(len(16.5f), len(1.5f), len(19f), len(4f))
                    quadraticBezierTo(len(21.5f), len(6.5f), len(19f), len(9f))
                    lineTo(len(11f), len(17f))
                    quadraticBezierTo(len(9.5f), len(18.5f), len(8f), len(17f))
                    quadraticBezierTo(len(6.5f), len(15.5f), len(8f), len(14f))
                    lineTo(len(15f), len(7f))
                },
                color = color,
                style = stroke,
            )
        }
    }
}
