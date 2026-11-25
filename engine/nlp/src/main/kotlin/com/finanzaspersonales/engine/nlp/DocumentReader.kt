package com.finanzaspersonales.engine.nlp

import com.finanzaspersonales.core.model.Money
import java.time.LocalDate
import java.time.Month

/** Una línea de texto reconocida en una foto, con su posición (en píxeles). */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

enum class DocRate { MONTHLY, EFFECTIVE_ANNUAL }

/** Lo que se pudo leer de una foto. Los campos null no aparecían o no se entendieron. */
sealed interface DocumentResult {
    /** Información de un crédito o tarjeta: saldo, cuota, tasa, fechas. */
    data class Debt(
        val name: String,
        val balance: Money?,
        val installment: Money?,
        val dueDay: Int?,
        val rateBasisPoints: Int?,
        val rateType: DocRate?,
        val paidInstallments: Int?,
        val totalInstallments: Int?,
        val insurance: Money?,
        val currentInterest: Money?,
        val disbursedOn: LocalDate?,
        /** Número del crédito enmascarado: solo los últimos 4 dígitos. */
        val maskedNumber: String?,
    ) : DocumentResult {
        val remainingInstallments: Int? get() = if (paidInstallments != null && totalInstallments != null) (totalInstallments - paidInstallments).coerceAtLeast(0) else null
    }

    /** Comprobante, factura o recibo: un gasto o un ingreso. */
    data class Movement(val draft: EntryDraft) : DocumentResult

    data class Unknown(val reason: String) : DocumentResult
}

/**
 * Lee el texto de una foto (capturas de apps bancarias, comprobantes, facturas) con
 * reglas locales. Primero arma filas uniendo etiquetas y valores que están a la misma
 * altura ("Fecha de pago | 22 de cada mes") y luego busca los campos conocidos.
 */
object DocumentReader {

    /** Une las líneas que están a la misma altura, de izquierda a derecha. */
    fun rows(lines: List<OcrLine>): List<String> {
        val sorted = lines.filter { it.text.isNotBlank() }.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in sorted) {
            val row = rows.lastOrNull()
            val ref = row?.first()
            if (row != null && ref != null && kotlin.math.abs(line.centerY - ref.centerY) <= minOf(line.height, ref.height) * 6 / 10) row += line
            else rows += mutableListOf(line)
        }
        return rows.map { r -> r.sortedBy { it.left }.joinToString(" | ") { it.text.trim() } }
    }

    fun read(lines: List<OcrLine>, today: LocalDate, categoryNames: List<String> = emptyList()): DocumentResult {
        val rows = rows(lines)
        if (rows.isEmpty()) return DocumentResult.Unknown("No encontré texto en la foto. Prueba con una imagen más nítida.")
        val norm = rows.map { normalize(it) }
        val all = norm.joinToString("\n")
        val debtScore = listOf("credito", "prestamo", "debes", "capital", "cuotas", "tasa", "fecha de pago", "pago minimo", "saldo", "desembolso", "tarjeta de credito")
            .count { all.contains(it) }
        val movementWords = listOf("comprobante", "transferencia", "enviaste", "envio exitoso", "pagaste", "factura", "recibo", "total", "recibiste", "te enviaron", "referencia", "compra")
            .count { all.contains(it) }
        return when {
            debtScore >= 3 && debtScore >= movementWords -> readDebt(rows, norm)
            else -> readMovement(rows, norm, today, categoryNames)
                ?: DocumentResult.Unknown("No reconocí el tipo de documento ni encontré un valor. Prueba con una captura donde se vea el valor completo.")
        }
    }

    // ------------------------------------------------------------------ deudas

    private fun readDebt(rows: List<String>, norm: List<String>): DocumentResult.Debt {
        fun valueRow(vararg labels: String): Int? = labels.firstNotNullOfOrNull { label -> norm.indexOfFirst { it.contains(label) }.takeIf { it >= 0 } }
        fun amountAt(vararg labels: String): Money? = labels.firstNotNullOfOrNull { label ->
            norm.indices.filter { norm[it].contains(label) }.firstNotNullOfOrNull { i -> amounts(rows[i]).firstOrNull() ?: rows.getOrNull(i + 1)?.let { next -> amounts(next).firstOrNull()?.takeIf { labelOnly(norm[i]) } } }
        }
        val balance = amountAt("debes", "saldo total", "saldo a la fecha", "saldo pendiente", "deuda total", "capital vigente", "saldo")
        val installment = amountAt("valor cuota", "valor de la cuota", "cuota mensual", "valor a pagar", "pago minimo", "pago total del mes")
        val dueDay = valueRow("fecha de pago", "fecha limite", "dia de pago", "paga antes")?.let { dayFrom(norm[it]) }
        val (rate, rateType) = valueRow("tasa")?.let { rateFrom(norm[it]) } ?: (null to null)
        val (paid, total) = norm.firstNotNullOfOrNull { r ->
            if (!r.contains("cuota")) return@firstNotNullOfOrNull null
            Regex("(\\d{1,3})\\s*de\\s*(\\d{1,3})").find(r)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        } ?: (null to null)
        val insurance = amountAt("seguro")
        val interest = amountAt("intereses corrientes", "interes corriente")
        val disbursed = valueRow("desembolso")?.let { longDate(norm[it]) }
        val number = valueRow("no. credito", "numero de credito", "no credito", "numero del credito", "no. tarjeta", "numero de tarjeta")?.let { i ->
            Regex("\\d{4,}").findAll(rows[i]).lastOrNull()?.value?.takeLast(4)?.let { "••••$it" }
        }
        return DocumentResult.Debt(
            name = debtName(rows, norm),
            balance = balance,
            installment = installment,
            dueDay = dueDay,
            rateBasisPoints = rate,
            rateType = rateType,
            paidInstallments = paid,
            totalInstallments = total,
            insurance = insurance?.takeIf { it.centavos > 0 },
            currentInterest = interest?.takeIf { it.centavos > 0 },
            disbursedOn = disbursed,
            maskedNumber = number,
        )
    }

    /** "Crédito Bajo Monto (primer crédito)": líneas que nombran el producto, sin etiquetas ni cifras. */
    private fun debtName(rows: List<String>, norm: List<String>): String {
        val skip = listOf("info", "tu credito", "pagar", "no.", "numero", "tasa", "fecha", "cuota", "debes", "saldo", "capital", "interes", "seguro", "desembolso")
        val names = norm.indices.filter { i ->
            val r = norm[i]
            Regex("\\b(credito|prestamo|tarjeta|libranza|crediagil|libre inversion)\\b").containsMatchIn(r) &&
                skip.none { r.contains(it) } && !r.any { it.isDigit() } && !r.contains('|')
        }.map { rows[it].trim().replaceFirstChar { c -> c.uppercase() } }
        return when (names.size) {
            0 -> "Crédito"
            1 -> names[0]
            else -> "${names[1]} (${names[0].replaceFirstChar { it.lowercase() }})"
        }.take(40)
    }

    private fun labelOnly(row: String) = !row.contains('|') && amounts(row).isEmpty()

    private fun dayFrom(row: String): Int? {
        Regex("\\b(\\d{1,2})\\s*de\\s*cada\\s*mes").find(row)?.let { return it.groupValues[1].toInt().takeIf { d -> d in 1..31 } }
        Regex("\\b(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})").find(row)?.let { return it.groupValues[1].toInt().takeIf { d -> d in 1..31 } }
        longDate(row)?.let { return it.dayOfMonth }
        Regex("\\bdia\\s*(\\d{1,2})\\b").find(row)?.let { return it.groupValues[1].toInt().takeIf { d -> d in 1..31 } }
        return null
    }

    /** "2.79% e.m / 39.11% e.a": prefiere la efectiva anual; si solo hay mensual, la usa. */
    private fun rateFrom(row: String): Pair<Int?, DocRate?> {
        val found = Regex("(\\d{1,3}(?:[.,]\\d{1,4})?)\\s*%\\s*([a-z. ]{0,8})").findAll(row).mapNotNull { m ->
            val value = m.groupValues[1].replace(',', '.').toBigDecimalOrNull() ?: return@mapNotNull null
            val bp = value.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toInt()
            val suffix = m.groupValues[2].replace(" ", "").replace(".", "")
            val type = when {
                suffix.startsWith("ea") || suffix.startsWith("anual") -> DocRate.EFFECTIVE_ANNUAL
                suffix.startsWith("em") || suffix.startsWith("mv") || suffix.startsWith("mes") || suffix.startsWith("mensual") || suffix.startsWith("nmv") -> DocRate.MONTHLY
                else -> null
            }
            bp to type
        }.toList()
        val chosen = found.firstOrNull { it.second == DocRate.EFFECTIVE_ANNUAL } ?: found.firstOrNull { it.second == DocRate.MONTHLY } ?: found.firstOrNull()
        return (chosen?.first to (chosen?.second ?: chosen?.let { if (it.first >= 1_000) DocRate.EFFECTIVE_ANNUAL else DocRate.MONTHLY }))
    }

    // ------------------------------------------------------------------ comprobantes y facturas

    private fun readMovement(rows: List<String>, norm: List<String>, today: LocalDate, categoryNames: List<String>): DocumentResult.Movement? {
        val labels = listOf("total a pagar", "valor total", "valor pagado", "valor de la transferencia", "valor transferido", "cuanto", "monto", "valor", "total")
        val amount = labels.firstNotNullOfOrNull { label ->
            norm.indices.filter { norm[it].contains(label) && !norm[it].contains("subtotal") }.firstNotNullOfOrNull { i ->
                amounts(rows[i]).firstOrNull() ?: rows.getOrNull(i + 1)?.let { amounts(it).firstOrNull() }
            }
        } ?: rows.flatMap { amounts(it) }.maxByOrNull { it.centavos }
        if (amount == null || amount.centavos <= 0) return null
        val all = norm.joinToString("\n")
        val income = listOf("recibiste", "te enviaron", "te llego", "te consignaron", "abono a tu cuenta", "recibido de", "de parte de").any { all.contains(it) }
        val party = norm.indices.firstNotNullOfOrNull { i ->
            val r = norm[i]
            val key = listOf("para", "destinatario", "comercio", "establecimiento", "de parte de", "enviado por", "de").firstOrNull { r.startsWith("$it:") || r.startsWith("$it |") || r == it }
                ?: return@firstNotNullOfOrNull null
            val value = rows[i].substringAfter('|', "").substringAfter(':', rows[i].substringAfter('|', "")).trim()
                .ifEmpty { rows.getOrNull(i + 1)?.trim().orEmpty() }
            value.takeIf { it.isNotBlank() && amounts(it).isEmpty() && key.isNotEmpty() }
        }
        val bank = when {
            all.contains("nequi") -> "Nequi"
            all.contains("bancolombia") -> "Bancolombia"
            else -> null
        }
        val date = norm.firstNotNullOfOrNull { shortDate(it) ?: longDate(it) }?.takeIf { !it.isAfter(today) } ?: today
        val kind = if (income) DraftKind.INCOME else DraftKind.EXPENSE
        val description = when {
            party != null -> (if (income) "De " else "") + party.replaceFirstChar { it.uppercase() }
            all.contains("factura") || all.contains("recibo") -> rows.firstOrNull { amounts(it).isEmpty() && it.length >= 3 }?.trim() ?: "Compra"
            else -> if (income) "Ingreso" else "Pago"
        }.take(60)
        val category = CategoryGuesser.guess(normalize(description) + " " + all, kind, categoryNames)
        return DocumentResult.Movement(
            EntryDraft(kind = kind, amount = amount, categoryName = category, description = description, date = date, accountName = bank, counterparty = party),
        )
    }

    // ------------------------------------------------------------------ valores y fechas

    /** Valores en pesos de una fila: "$ 2.583.328,84", "$11.686,72", "2.500.000". Ignora porcentajes y fechas. */
    fun amounts(row: String): List<Money> {
        val clean = row.replace(Regex("\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}"), " ")
            // Centavos en letra pequeña que el lector separa: "$ 2.583.328, | 84".
            .replace(Regex(",\\s*\\|\\s*(\\d{2})\\b"), ",$1")
        return Regex("(\\$|s/?\\s)?\\s?(\\d{1,3}(?:[.]\\d{3})+|\\d{4,})(?:,(\\d{1,2}))?(?!\\s*%)(?![\\d.]|,\\d)", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .filter { m -> m.groupValues[1].isNotEmpty() || m.groupValues[2].contains('.') }
            .mapNotNull { m ->
                val pesos = m.groupValues[2].replace(".", "").toLongOrNull() ?: return@mapNotNull null
                val cents = m.groupValues[3].padEnd(2, '0').ifEmpty { "00" }.toLong()
                runCatching { Money(Math.addExact(Math.multiplyExact(pesos, 100), cents)) }.getOrNull()
            }.toList()
    }

    private val months = mapOf(
        "enero" to Month.JANUARY, "febrero" to Month.FEBRUARY, "marzo" to Month.MARCH, "abril" to Month.APRIL, "mayo" to Month.MAY,
        "junio" to Month.JUNE, "julio" to Month.JULY, "agosto" to Month.AUGUST, "septiembre" to Month.SEPTEMBER, "setiembre" to Month.SEPTEMBER,
        "octubre" to Month.OCTOBER, "noviembre" to Month.NOVEMBER, "diciembre" to Month.DECEMBER,
        "ene" to Month.JANUARY, "feb" to Month.FEBRUARY, "mar" to Month.MARCH, "abr" to Month.APRIL, "jun" to Month.JUNE, "jul" to Month.JULY,
        "ago" to Month.AUGUST, "sep" to Month.SEPTEMBER, "oct" to Month.OCTOBER, "nov" to Month.NOVEMBER, "dic" to Month.DECEMBER,
    )

    /** "26 de junio de 2026", "25 nov 2026". */
    private fun longDate(row: String): LocalDate? {
        val m = Regex("\\b(\\d{1,2})\\s*(?:de\\s*)?([a-z]{3,10})\\.?\\s*(?:de\\s*|del\\s*)?(\\d{4})\\b").find(row) ?: return null
        val month = months[m.groupValues[2]] ?: return null
        return runCatching { LocalDate.of(m.groupValues[3].toInt(), month, m.groupValues[1].toInt()) }.getOrNull()
    }

    /** "25/11/2026" o "25-11-26". */
    private fun shortDate(row: String): LocalDate? {
        val m = Regex("\\b(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})\\b").find(row) ?: return null
        val year = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
        return runCatching { LocalDate.of(year, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
    }
}
