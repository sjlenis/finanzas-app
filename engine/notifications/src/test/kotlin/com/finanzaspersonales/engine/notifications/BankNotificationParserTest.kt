package com.finanzaspersonales.engine.notifications

import com.finanzaspersonales.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los textos de prueba son redacciones típicas, escritas a mano y con datos falsos;
 * no son notificaciones reales. Cuando haya muestras reales se añadirán aquí.
 */
class BankNotificationParserTest {

    private val nequi = "com.nequi.MobileApp"
    private val bancolombia = "com.todo1.mobile"
    private val sms = "com.samsung.android.messaging"

    private fun detected(pkg: String, title: String?, text: String): DetectedMovement {
        val r = BankNotificationParser.parse(pkg, title, text)
        assertTrue("Se esperaba un movimiento y fue $r", r is NotificationResult.Detected)
        return (r as NotificationResult.Detected).movement
    }

    private fun ignored(pkg: String, title: String?, text: String): IgnoreReason {
        val r = BankNotificationParser.parse(pkg, title, text)
        assertTrue("Se esperaba ignorar y fue $r", r is NotificationResult.Ignored)
        return (r as NotificationResult.Ignored).reason
    }

    @Test fun formatosDeValor() {
        assertEquals(Money(4_590_000), BankNotificationParser.parseCop("45.900,00"))
        assertEquals(Money.ofPesos(50_000), BankNotificationParser.parseCop("50,000"))
        assertEquals(Money.ofPesos(50_000), BankNotificationParser.parseCop("50,000.00"))
        assertEquals(Money.ofPesos(1_250_000), BankNotificationParser.parseCop("1.250.000"))
        assertEquals(Money.ofPesos(20_000), BankNotificationParser.parseCop("20000"))
        assertEquals(Money(1_550), BankNotificationParser.parseCop("15,50"))
        assertNull(BankNotificationParser.parseCop("1.2.3"))
        assertNull(BankNotificationParser.parseCop("0"))
    }

    @Test fun compraBancolombiaPorSms() {
        val m = detected(sms, "85540", "Bancolombia le informa Compra por \$45.900,00 en EXITO COLINA 14:32. 24/11/2026 T.Cred *1234. Inquietudes al 6045109095")
        assertEquals(Bank.BANCOLOMBIA, m.bank)
        assertEquals(DetectedKind.PURCHASE, m.kind)
        assertEquals(Money(4_590_000), m.amount)
        assertEquals("Exito Colina", m.merchant)
        assertEquals(Confidence.HIGH, m.confidence)
    }

    @Test fun pagoNequiEnComercio() {
        val m = detected(nequi, "Nequi", "Pagaste \$12.500 en Tienda D1 con tu Nequi.")
        assertEquals(DetectedKind.PAYMENT, m.kind)
        assertEquals(Money.ofPesos(12_500), m.amount)
        assertEquals("Tienda D1", m.merchant)
    }

    @Test fun dineroRecibidoEnNequi() {
        val m = detected(nequi, "¡Te llegó plata!", "Ana Gómez te envió \$50.000.")
        assertEquals(DetectedKind.TRANSFER_IN, m.kind)
        assertEquals("Ana Gómez", m.counterparty)
        assertTrue(m.kind.inflow)
    }

    @Test fun transferenciaRecibidaBancolombia() {
        val m = detected(bancolombia, "Bancolombia", "Recibiste una transferencia por \$300.000 de PEDRO PEREZ en tu cuenta *9876.")
        assertEquals(DetectedKind.TRANSFER_IN, m.kind)
        assertEquals(Money.ofPesos(300_000), m.amount)
        assertEquals("Pedro Perez", m.counterparty)
    }

    @Test fun envioNequi() {
        val m = detected(nequi, null, "Enviaste \$20.000 a Carlos Ruiz.")
        assertEquals(DetectedKind.TRANSFER_OUT, m.kind)
        assertEquals("Carlos Ruiz", m.counterparty)
        assertEquals("Envío a Carlos Ruiz", m.description)
    }

    @Test fun retiroCajero() {
        val m = detected(sms, null, "Bancolombia le informa Retiro por \$200.000,00 en ATM CENTRO 10:15. 24/11/2026 T.Deb *1234.")
        assertEquals(DetectedKind.WITHDRAWAL, m.kind)
        assertEquals(Money.ofPesos(200_000), m.amount)
    }

    @Test fun cuotaDeManejo() {
        val m = detected(bancolombia, null, "Se debitó \$18.990 por cuota de manejo de tu tarjeta *1234.")
        assertEquals(DetectedKind.FEE, m.kind)
    }

    @Test fun noTomaElSaldoComoValor() {
        val m = detected(nequi, null, "Tu saldo disponible es \$80.000. Pagaste \$5.000 en Café Juan Valdez.")
        assertEquals(Money.ofPesos(5_000), m.amount)
    }

    @Test fun codigosDeSeguridadSeDescartan() {
        assertEquals(IgnoreReason.SECURITY, ignored(sms, null, "Bancolombia: tu clave dinámica es 123456. No la compartas con nadie."))
        assertEquals(IgnoreReason.SECURITY, ignored(nequi, null, "Tu código de verificación es 4821"))
    }

    @Test fun promocionesSeIgnoran() =
        assertEquals(IgnoreReason.PROMOTION, ignored(nequi, null, "¡Aprovecha! Gana hasta 20% de descuento en tus compras"))

    @Test fun otrasAppsYSmsSinBancoSeIgnoran() {
        assertEquals(IgnoreReason.NOT_BANK, ignored("com.whatever.app", null, "Compraste \$10.000"))
        assertEquals(IgnoreReason.NOT_BANK, ignored(sms, "Mamá", "Compré pan por \$5.000"))
    }

    @Test fun smsQueSoloMencionaAlBancoEsBajaConfianza() {
        val m = detected(sms, "Juan", "Te pagué con Nequi, compra por \$30.000")
        assertEquals(Confidence.LOW, m.confidence)
    }

    @Test fun mascaraTapaNumerosPeroNoValores() {
        val masked = BankNotificationParser.mask("Compra por \$45.900,00 T.Cred *1234 cuenta 12345678 tel 6045109095")
        assertTrue(masked, masked.contains("\$45.900,00"))
        assertFalse(masked, masked.contains("1234"))
        assertFalse(masked, masked.contains("12345678"))
        assertFalse(masked, masked.contains("6045109095"))
    }
}
