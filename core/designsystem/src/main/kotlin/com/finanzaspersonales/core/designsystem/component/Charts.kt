package com.finanzaspersonales.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.formatCop

/**
 * Gráficos dibujados con Compose (sin librerías externas). Paleta de dos series
 * azul/naranja validada para daltonismo en modo claro y oscuro; los textos usan los
 * colores de texto del tema, nunca el color de la serie.
 */
object ChartPalette {
    private val BlueLight = Color(0xFF2A78D6)
    private val OrangeLight = Color(0xFFEB6834)
    private val BlueDark = Color(0xFF3987E5)
    private val OrangeDark = Color(0xFFD95926)

    @Composable
    fun series(): List<Color> =
        if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) listOf(BlueDark, OrangeDark) else listOf(BlueLight, OrangeLight)
}

/** Valor corto para los ejes: $1,2 M, $350 mil, $900. */
fun Money.compactCop(): String {
    val pesos = centavos / 100
    val sign = if (pesos < 0) "-" else ""
    val abs = kotlin.math.abs(pesos)
    return when {
        abs >= 1_000_000 -> {
            val tenths = (abs + 50_000) / 100_000
            sign + "$" + (tenths / 10) + (if (tenths % 10 != 0L) ",${tenths % 10}" else "") + " M"
        }
        abs >= 1_000 -> "$sign$${(abs + 500) / 1_000} mil"
        else -> "$sign$$abs"
    }
}

data class BarGroup(val label: String, val values: List<Money>)

/**
 * Barras agrupadas por mes. Admite valores negativos (línea base en cero). Con
 * [signedColors], una sola serie se colorea por signo (positivo / negativo).
 * Toca un grupo para ver sus valores exactos.
 */
@Composable
fun BarChart(
    groups: List<BarGroup>,
    seriesNames: List<String>,
    modifier: Modifier = Modifier,
    colors: List<Color> = ChartPalette.series(),
    signedColors: Boolean = false,
    height: Dp = 180.dp,
) {
    if (groups.isEmpty()) return
    var selected by remember(groups.size) { mutableIntStateOf(groups.lastIndex) }
    val all = groups.flatMap { it.values.map { v -> v.centavos } }
    val max = maxOf(0L, all.maxOrNull() ?: 0L)
    val min = minOf(0L, all.minOrNull() ?: 0L)
    val span = (max - min).takeIf { it > 0 } ?: 1L
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val selectedStyle = labelStyle.copy(color = MaterialTheme.colorScheme.onSurface)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val baseline = MaterialTheme.colorScheme.outline
    val highlight = MaterialTheme.colorScheme.surfaceVariant
    val summary = groups.joinToString("; ") { g -> g.label + ": " + g.values.mapIndexed { i, v -> "${seriesNames.getOrElse(i) { "" }} ${v.formatCop()}" }.joinToString(", ") }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (signedColors) Legend(listOf("Positivo" to colors[0], "Negativo" to colors[1]))
        else if (seriesNames.size >= 2) Legend(seriesNames.zip(colors))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = summary }
                .pointerInput(groups) {
                    detectTapGestures { offset ->
                        val plotLeft = 44.dp.toPx()
                        val w = (size.width - plotLeft) / groups.size
                        selected = ((offset.x - plotLeft) / w).toInt().coerceIn(0, groups.lastIndex)
                    }
                },
        ) {
            val plotLeft = 44.dp.toPx()
            val plotBottom = size.height - 18.dp.toPx()
            val plotHeight = plotBottom - 4.dp.toPx()
            fun y(v: Long) = plotBottom - (v - min).toFloat() / span * plotHeight
            val groupW = (size.width - plotLeft) / groups.size
            // Selección: banda suave detrás del grupo elegido.
            drawRect(highlight.copy(alpha = 0.5f), Offset(plotLeft + groupW * selected, 0f), Size(groupW, plotBottom))
            // Rejilla discreta: máximo, mitad y mínimo (o cero).
            listOf(max, (max + min) / 2, min).distinct().forEach { v ->
                val yy = y(v)
                drawLine(grid, Offset(plotLeft, yy), Offset(size.width, yy), strokeWidth = 1f)
                val text = textMeasurer.measure(Money(v).compactCop(), labelStyle)
                drawText(text, topLeft = Offset(plotLeft - text.size.width - 4.dp.toPx(), yy - text.size.height / 2))
            }
            drawLine(baseline, Offset(plotLeft, y(0)), Offset(size.width, y(0)), strokeWidth = 1.dp.toPx())
            val n = groups.first().values.size.coerceAtLeast(1)
            val gap = 2.dp.toPx()
            val barW = ((groupW * 0.64f) - gap * (n - 1)) / n
            groups.forEachIndexed { gi, g ->
                val start = plotLeft + groupW * gi + groupW * 0.18f
                g.values.forEachIndexed { si, v ->
                    val color = if (signedColors) (if (v.centavos >= 0) colors[0] else colors[1]) else colors[si % colors.size]
                    drawBar(start + si * (barW + gap), barW, y(0), y(v.centavos), color, 4.dp.toPx())
                }
                val label = textMeasurer.measure(g.label, if (gi == selected) selectedStyle else labelStyle)
                drawText(label, topLeft = Offset(plotLeft + groupW * gi + (groupW - label.size.width) / 2, plotBottom + 2.dp.toPx()))
            }
        }
        val g = groups[selected]
        Text(
            g.label + ": " + g.values.mapIndexed { i, v -> "${seriesNames.getOrElse(i) { "" }} ${v.formatCop()}".trim() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Barra con el extremo de datos redondeado y la base recta sobre la línea cero. */
private fun DrawScope.drawBar(x: Float, w: Float, zeroY: Float, valueY: Float, color: Color, radius: Float) {
    if (kotlin.math.abs(valueY - zeroY) < 0.5f) return
    val top = minOf(zeroY, valueY)
    val bottom = maxOf(zeroY, valueY)
    val r = CornerRadius(minOf(radius, (bottom - top) / 2, w / 2))
    val up = valueY < zeroY
    val rect = RoundRect(
        left = x, top = top, right = x + w, bottom = bottom,
        topLeftCornerRadius = if (up) r else CornerRadius.Zero,
        topRightCornerRadius = if (up) r else CornerRadius.Zero,
        bottomRightCornerRadius = if (up) CornerRadius.Zero else r,
        bottomLeftCornerRadius = if (up) CornerRadius.Zero else r,
    )
    drawPath(Path().apply { addRoundRect(rect) }, color)
}

/** Línea de una sola serie (evolución). Toca un punto para ver su valor. */
@Composable
fun LineChart(
    points: List<Pair<String, Money>>,
    seriesName: String,
    modifier: Modifier = Modifier,
    color: Color = ChartPalette.series()[0],
    height: Dp = 160.dp,
) {
    if (points.isEmpty()) return
    var selected by remember(points.size) { mutableIntStateOf(points.lastIndex) }
    val values = points.map { it.second.centavos }
    val max = maxOf(0L, values.max())
    val min = minOf(0L, values.min())
    val span = (max - min).takeIf { it > 0 } ?: 1L
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val selectedStyle = labelStyle.copy(color = MaterialTheme.colorScheme.onSurface)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface
    val summary = "$seriesName: " + points.joinToString("; ") { "${it.first} ${it.second.formatCop()}" }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = summary }
                .pointerInput(points) {
                    detectTapGestures { offset ->
                        val plotLeft = 44.dp.toPx()
                        val step = (size.width - plotLeft) / points.size
                        selected = ((offset.x - plotLeft) / step).toInt().coerceIn(0, points.lastIndex)
                    }
                },
        ) {
            val plotLeft = 44.dp.toPx()
            val plotBottom = size.height - 18.dp.toPx()
            val plotTop = 8.dp.toPx()
            val plotHeight = plotBottom - plotTop
            fun y(v: Long) = plotBottom - (v - min).toFloat() / span * plotHeight
            val step = (size.width - plotLeft) / points.size
            fun x(i: Int) = plotLeft + step * i + step / 2
            listOf(max, (max + min) / 2, min).distinct().forEach { v ->
                val yy = y(v)
                drawLine(grid, Offset(plotLeft, yy), Offset(size.width, yy), strokeWidth = 1f)
                val text = textMeasurer.measure(Money(v).compactCop(), labelStyle)
                drawText(text, topLeft = Offset(plotLeft - text.size.width - 4.dp.toPx(), yy - text.size.height / 2))
            }
            drawLine(grid, Offset(x(selected), plotTop), Offset(x(selected), plotBottom), strokeWidth = 1.dp.toPx())
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(i), y(p.second.centavos)) else path.lineTo(x(i), y(p.second.centavos)) }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
            points.forEachIndexed { i, p ->
                val c = Offset(x(i), y(p.second.centavos))
                val r = if (i == selected) 6.dp.toPx() else 4.dp.toPx()
                drawCircle(surface, radius = r + 2.dp.toPx(), center = c)
                drawCircle(color, radius = r, center = c)
                val label = textMeasurer.measure(p.first, if (i == selected) selectedStyle else labelStyle)
                drawText(label, topLeft = Offset(x(i) - label.size.width / 2, plotBottom + 2.dp.toPx()))
            }
        }
        val p = points[selected]
        Text("${p.first}: ${p.second.formatCop()}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun Legend(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (name, color) ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
                Text(name, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Vista de tabla para leer los valores exactos (alternativa accesible al gráfico). */
@Composable
fun ChartTable(header: List<String>, rows: List<List<String>>) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Ocultar tabla" else "Ver tabla") }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TableRow(header, MaterialTheme.typography.labelMedium)
        HorizontalDivider()
        rows.forEach { TableRow(it, MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun TableRow(cells: List<String>, style: TextStyle) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        cells.forEachIndexed { i, c ->
            Text(
                c, style = style, modifier = Modifier.weight(if (i == 0) 1.2f else 1f),
                textAlign = if (i == 0) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}
