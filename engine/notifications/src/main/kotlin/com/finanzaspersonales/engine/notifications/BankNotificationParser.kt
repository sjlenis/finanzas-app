package com.finanzaspersonales.engine.notifications

import com.finanzaspersonales.core.model.Money
import java.text.Normalizer

enum class Bank(val displayName: String) { NEQUI("Nequi"), BANCOLOMBIA("Bancolombia") }

/** Lo que dice la notificación que pasó. La app lo convierte en un movimiento pendiente. */
enum class DetectedKind(val label: String, val inflow: Boolean) {
    PURCHASE("Compra", inflow = false),
    PAYMENT("Pago", inflow = false),
    TRANSFER_OUT("Envío", inflow = false),
    TRANSFER_IN("Dinero recibido", inflow = true),
    WITHDRAWAL("Retiro", inflow = false),
    FEE("Comisión", inflow = false),
}

enum class Confidence { HIGH, LOW }

data class DetectedMovement(
    val bank: Bank,
    val kind: DetectedKind,
    val amount: Money,
    /** Comercio, para compras y pagos. Sin números de cuenta ni de tarjeta. */
    val merchant: String?,
    /** Persona que envió o recibió, para transferencias. */
    val counterparty: String?,
    val confidence: Confidence,
) {
    val description: String
        get() = when (kind) {
            DetectedKind.PURCHASE, DetectedKind.PAYMENT -> merchant ?: "${kind.label} ${bank.displayName}"
            DetectedKind.TRANSFER_OUT -> counterparty?.let { "Envío a $it" } ?: "Envío desde ${bank.displayName}"
            DetectedKind.TRANSFER_IN -> counterparty?.let { "Recibido de $it" } ?: "Dinero recibido en ${bank.displayName}"
            DetectedKind.WITHDRAWAL -> "Retiro ${bank.displayName}"
            DetectedKind.FEE -> "Comisión ${bank.displayName}"
        }
}

enum class IgnoreReason {
    /** No es de Nequi ni de Bancolombia. */
    NOT_BANK,
    /** Códigos, claves o tokens: se descartan sin guardar nada. */
    SECURITY,
    PROMOTION,
    NO_AMOUNT,
    UNKNOWN_FORMAT,
}

sealed interface NotificationResult {
    data class Detected(val movement: DetectedMovement) : NotificationResult
    data class Ignored(val reason: IgnoreReason, val bank: Bank?) : NotificationResult
}

/**
 * Lee notificaciones de Nequi y Bancolombia (de sus apps o de sus SMS). Es tolerante
 * a cambios de redacción: busca un valor con "$" y palabras típicas ("compraste",
 * "recibiste", "retiro"...). Nunca decide solo: todo lo detectado queda pendiente
 * de confirmación en la app.
 */
object BankNotificationParser {

    /** Apps de los bancos. Si un banco cambia su paquete, basta con añadirlo aquí. */
    val NEQUI_PACKAGES = setOf("com.nequi.MobileApp")
    val BANCOLOMBIA_PACKAGES = setOf("com.todo1.mobile")

    /** Apps de SMS: solo se leen mensajes que mencionan a Nequi o Bancolombia. */
    val SMS_PACKAGES = setOf(
        "com.samsung.android.messaging",
        "com.google.android.apps.messaging",
        "com.android.mms",
    )

    val ALL_PACKAGES: Set<String> get() = NEQUI_PACKAGES + BANCOLOMBIA_PACKAGES + SMS_PACKAGES

    fun bankOf(packageName: String, title: String?, text: String): Bank? {
        if (packageName in NEQUI_PACKAGES) return Bank.NEQUI
        if (packageName in BANCOLOMBIA_PACKAGES) return Bank.BANCOLOMBIA
        if (packageName !in SMS_PACKAGES) return null
        val all = normalize("${title.orEmpty()} $text")
        return when {
            Regex("\\bbancolombia\\b").containsMatchIn(all) -> Bank.BANCOLOMBIA
            Regex("\\bnequi\\b").containsMatchIn(all) -> Bank.NEQUI
            else -> null
        }
    }

    fun parse(packageName: String, title: String?, text: String): NotificationResult {
        val bank = bankOf(packageName, title, text) ?: return NotificationResult.Ignored(IgnoreReason.NOT_BANK, null)
        val original = "${title.orEmpty()}. $text"
        val n = normalize(original)
        if (isSecurity(n)) return NotificationResult.Ignored(IgnoreReason.SECURITY, bank)
        val kind = detectKind(n)
        val amount = findAmount(original)
        if (kind == null) {
            val promo = Regex("\\b(gana|ganate|descuento|promo|promocion|aprovecha|oferta|beneficio|cashback|sorteo)\\b").containsMatchIn(n)
            return NotificationResult.Ignored(if (promo) IgnoreReason.PROMOTION else IgnoreReason.UNKNOWN_FORMAT, bank)
        }
        if (amount == null) return NotificationResult.Ignored(IgnoreReason.NO_AMOUNT, bank)
        val merchant = if (kind == DetectedKind.PURCHASE || kind == DetectedKind.PAYMENT) findMerchant(text) else null
        val counterparty = when (kind) {
            DetectedKind.TRANSFER_IN -> findSender(text)
            DetectedKind.TRANSFER_OUT -> findRecipient(text)
            else -> null
        }
        // Alta confianza: viene de la app del banco o el SMS empieza nombrando al banco.
        val fromBankSource = packageName !in SMS_PACKAGES || normalize(text).startsWith(normalize(bank.displayName)) ||
            normalize(title.orEmpty()).contains(normalize(bank.displayName))
        return NotificationResult.Detected(
            DetectedMovement(
                bank = bank,
                kind = kind,
                amount = amount,
                merchant = merchant,
                counterparty = counterparty,
                confidence = if (fromBankSource) Confidence.HIGH else Confidence.LOW,
            ),
        )
    }

    private fun isSecurity(n: String): Boolean =
        Regex("\\b(codigo|clave|otp|contrasena|token|no (lo |la )?compartas|verificacion|dinamica)\\b").containsMatchIn(n)

    private fun detectKind(n: String): DetectedKind? = when {
        Regex("\\b(cuota de manejo|comision|4 ?x ?1000|gmf)\\b").containsMatchIn(n) -> DetectedKind.FEE
        Regex("\\b(retiro|retiraste|sacaste|retiro en cajero)\\b").containsMatchIn(n) -> DetectedKind.WITHDRAWAL
        Regex("\\b(recibiste|te llego|te llegaron|te envio|te enviaron|te consigno|te consignaron|consignacion|abono|transferencia recibida|te transfirio|te transfirieron|recibio)\\b")
            .containsMatchIn(n) -> DetectedKind.TRANSFER_IN
        Regex("\\b(transferiste|enviaste|transferencia a|transferencia por|envio de plata|le enviaste)\\b").containsMatchIn(n) -> DetectedKind.TRANSFER_OUT
        Regex("\\b(compra|compraste)\\b").containsMatchIn(n) -> DetectedKind.PURCHASE
        Regex("\\b(pagaste|pago de|pago a|pago por|pago en|debito|pse)\\b").containsMatchIn(n) -> DetectedKind.PAYMENT
        else -> null
    }

    private val amountRegex = Regex("(?:\\$|\\bcop\\s?)\\s?([0-9][0-9.,]*[0-9]|[0-9])", RegexOption.IGNORE_CASE)

    /** Primer valor con "$" que no sea un saldo ("Tu saldo es $..."). */
    fun findAmount(text: String): Money? {
        for (m in amountRegex.findAll(text)) {
            val before = normalize(text.substring(maxOf(0, m.range.first - 25), m.range.first))
            if (Regex("\\b(saldo|disponible|cupo)\\b").containsMatchIn(before)) continue
            parseCop(m.groupValues[1])?.let { return it }
        }
        return null
    }

    /**
     * "45.900,00", "50,000", "1.250.000" o "20000" a centavos. Si el último separador
     * tiene 2 dígitos detrás son centavos; si tiene 3, separa miles.
     */
    fun parseCop(raw: String): Money? {
        val s = raw.trim().trimEnd('.', ',')
        if (s.isEmpty()) return null
        val lastSep = s.indexOfLast { it == '.' || it == ',' }
        val (intPart, cents) = if (lastSep >= 0 && s.length - lastSep - 1 == 2) {
            s.substring(0, lastSep) to s.substring(lastSep + 1).toLong()
        } else if (lastSep >= 0 && s.length - lastSep - 1 != 3) {
            return null
        } else {
            s to 0L
        }
        val digits = intPart.filter { it.isDigit() }
        if (digits.isEmpty() || digits.length > 13) return null
        // Los grupos de miles deben tener 3 dígitos ("1.2.3" no es un valor).
        val groups = intPart.split('.', ',')
        if (groups.size > 1 && groups.drop(1).any { it.length != 3 }) return null
        val centavos = Math.addExact(Math.multiplyExact(digits.toLong(), 100L), cents)
        return Money(centavos).takeIf { it.centavos > 0 }
    }

    private const val NAME = "([A-Za-zÁÉÍÓÚÑáéíóúñ0-9&'][A-Za-zÁÉÍÓÚÑáéíóúñ0-9&'. -]{1,40}?)"
    private const val STOP = "(?=\\s+(?:el|a las|con|desde|por|hoy|en tu|a tu)\\b|\\s+\\d{1,2}[:/]|\\s*[.,;:!]\\s|\\s*[.,;:!]?$)"

    private fun findMerchant(text: String): String? =
        Regex("\\ben\\s+$NAME$STOP", RegexOption.IGNORE_CASE).findAll(text)
            .map { clean(it.groupValues[1]) }
            .firstOrNull { it != null && !Regex("^(tu|la|su)\\s+(cuenta|tarjeta|nequi)", RegexOption.IGNORE_CASE).containsMatchIn(it) }

    private fun findSender(text: String): String? {
        // Nequi: "NOMBRE te envió $..."; bancos: "... de NOMBRE".
        Regex("^(?:.*?[.!]\\s*)?$NAME\\s+te\\s+(?:envi[oó]|transfiri[oó]|consign[oó])", RegexOption.IGNORE_CASE).find(text)
            ?.let { clean(it.groupValues[1]) }?.let { return it }
        return Regex("\\bde\\s+$NAME$STOP", RegexOption.IGNORE_CASE).findAll(text)
            .map { clean(it.groupValues[1]) }
            .firstOrNull { it != null && !it.contains("cuenta", ignoreCase = true) && !it.startsWith("$") }
    }

    private fun findRecipient(text: String): String? =
        Regex("\\ba\\s+$NAME$STOP", RegexOption.IGNORE_CASE).findAll(text)
            .map { clean(it.groupValues[1]) }
            .firstOrNull { it != null && !Regex("\\b(cuenta|tarjeta|llave)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }

    /** Quita números de cuenta o tarjeta y deja un texto corto y legible. */
    private fun clean(raw: String): String? {
        val s = raw.replace(Regex("\\*+\\s?\\d+"), "").replace(Regex("\\d{4,}"), "").replace(Regex("\\s+"), " ").trim(' ', '.', '-')
        if (s.length < 2 || s.none { it.isLetter() }) return null
        val words = s.split(' ').take(5).joinToString(" ")
        return if (words == words.uppercase()) {
            words.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        } else {
            words.replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Versión para el modo de muestras: tapa números de cuenta, tarjeta y teléfono
     * (4 o más dígitos seguidos, o "*1234"). Los valores con "$" se conservan.
     */
    fun mask(text: String): String = text
        .replace(Regex("\\*\\s?\\d+"), "*••••")
        .replace(Regex("(?<![\\$\\d.,])\\d{4,}(?![\\d])")) { "•".repeat(4) }

    internal fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('ñ', 'n')
            .replace(Regex("\\s+"), " ")
            .trim()
}
