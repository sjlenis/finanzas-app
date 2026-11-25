package com.finanzaspersonales.core.data

import com.finanzaspersonales.core.common.BogotaTime
import com.finanzaspersonales.core.model.AccountType
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.formatCop
import com.finanzaspersonales.engine.finance.RateType
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Clock
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lo que trae un archivo de configuración. Todos los valores en pesos son enteros
 * (sin decimales) y se convierten a centavos; los dólares admiten decimales.
 */
data class ConfigPlan(
    val month: YearMonth,
    val accounts: List<AccountItem>,
    val emergencyFund: Money?,
    val usdRate: Money?,
    val fixed: List<FixedItem>,
    val debts: List<DebtItem>,
) {
    data class AccountItem(val name: String, val type: AccountType, val balance: Money)
    data class FixedItem(
        val name: String,
        val isIncome: Boolean,
        val amount: Money,
        val currency: PayCurrency,
        val day: Int,
        val category: String?,
        val account: String?,
        val doneThisMonth: Boolean,
    )
    data class DebtItem(
        val name: String,
        val creditor: String?,
        val total: Money,
        val balance: Money,
        val installment: Money,
        val day: Int?,
        val frequency: PaymentFrequency,
        val paidThisMonth: Boolean,
    )

    /** Resumen legible de lo que se hará, para mostrarlo antes de aplicar. */
    fun summary(): List<String> = buildList {
        accounts.forEach { add("Cuenta ${it.name}: saldo ${it.balance.formatCop()}") }
        emergencyFund?.let { add("Fondo de emergencia: ${it.formatCop()} apartados") }
        usdRate?.let { add("Tasa del dólar: ${it.formatCop()}") }
        fixed.forEach {
            val value = if (it.currency == PayCurrency.USD) it.amount.formatUsd() else it.amount.formatCop()
            add(
                (if (it.isIncome) "Ingreso fijo " else "Pago fijo ") + "${it.name}: $value el día ${it.day}" +
                    if (it.doneThisMonth) (if (it.isIncome) " (ya recibido este mes)" else " (ya pagado este mes)") else "",
            )
        }
        debts.forEach {
            add("Deuda ${it.name}: debes ${it.balance.formatCop()}, cuota ${it.installment.formatCop()}" + if (it.paidThisMonth) " (pagada este mes)" else "")
        }
    }

    companion object {
        /** Lee el archivo. Lanza un error con un mensaje claro si algo no cuadra. */
        fun parse(json: String): ConfigPlan {
            val o = runCatching { JSONObject(json) }.getOrElse { throw IllegalArgumentException("El archivo no es una configuración válida") }
            require(o.optString("formato") == "finanzas-config") { "El archivo no es una configuración de esta app" }
            require(o.optInt("version") == 1) { "Versión de archivo no compatible" }
            val month = runCatching { YearMonth.parse(o.getString("mes")) }.getOrElse { throw IllegalArgumentException("Falta el mes (por ejemplo 2026-11)") }
            return ConfigPlan(
                month = month,
                accounts = o.optJSONArray("cuentas").objects().map { a ->
                    AccountItem(
                        name = a.text("nombre"),
                        type = runCatching { AccountType.valueOf(a.optString("tipo", "OTHER")) }.getOrDefault(AccountType.OTHER),
                        balance = a.pesos("saldo"),
                    )
                },
                emergencyFund = if (o.has("fondoEmergencia")) o.pesos("fondoEmergencia") else null,
                usdRate = if (o.has("tasaDolar")) o.pesos("tasaDolar").also { require(it.centavos > 0) { "La tasa del dólar debe ser mayor que cero" } } else null,
                fixed = o.optJSONArray("fijos").objects().map { f ->
                    val currency = if (f.optString("moneda", "COP") == "USD") PayCurrency.USD else PayCurrency.COP
                    FixedItem(
                        name = f.text("nombre"),
                        isIncome = when (f.optString("tipo")) {
                            "INGRESO" -> true
                            "PAGO" -> false
                            else -> throw IllegalArgumentException("Tipo inválido en ${f.optString("nombre")}: usa INGRESO o PAGO")
                        },
                        amount = if (currency == PayCurrency.USD) f.dollars("valor") else f.pesos("valor"),
                        currency = currency,
                        day = f.getInt("dia").also { require(it in 1..31) { "Día inválido en ${f.optString("nombre")}" } },
                        category = f.optString("categoria").ifBlank { null },
                        account = f.optString("cuenta").ifBlank { null },
                        doneThisMonth = f.optBoolean("yaPasoEsteMes"),
                    ).also { require(it.amount.centavos > 0) { "El valor de ${it.name} debe ser mayor que cero" } }
                },
                debts = o.optJSONArray("deudas").objects().map { d ->
                    DebtItem(
                        name = d.text("nombre"),
                        creditor = d.optString("entidad").ifBlank { null },
                        total = d.pesos("total"),
                        balance = d.pesos("saldo"),
                        installment = d.pesos("cuota"),
                        day = if (d.has("dia")) d.getInt("dia") else null,
                        frequency = if (d.optString("frecuencia") == "BIWEEKLY") PaymentFrequency.BIWEEKLY else PaymentFrequency.MONTHLY,
                        paidThisMonth = d.optBoolean("pagadaEsteMes"),
                    )
                },
            )
        }

        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

        private fun JSONObject.text(key: String): String =
            optString(key).trim().takeIf { it.isNotEmpty() }?.take(40) ?: throw IllegalArgumentException("Falta \"$key\"")

        /** Pesos enteros, sin decimales. */
        private fun JSONObject.pesos(key: String): Money {
            val v = runCatching { BigDecimal(get(key).toString()) }.getOrElse { throw IllegalArgumentException("Valor inválido en \"$key\"") }
            require(v.signum() >= 0) { "\"$key\" no puede ser negativo" }
            return Money(v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact())
        }

        /** Dólares, con hasta dos decimales: se guardan en centavos de dólar. */
        private fun JSONObject.dollars(key: String): Money = pesos(key)
    }
}

/**
 * Carga una configuración sin borrar nada: actualiza lo que ya existe con el mismo nombre
 * y crea lo que falta. Los saldos se cuadran con una conciliación (ajuste que no es
 * ingreso ni gasto) y lo "ya pagado/recibido este mes" se marca sin mover ninguna cuenta,
 * porque el saldo de hoy ya lo incluye.
 */
@Singleton
class ConfigImporter @Inject constructor(
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    private val planning: PlanningRepository,
    private val preferences: UserPreferences,
    private val clock: Clock,
) {
    suspend fun apply(plan: ConfigPlan): List<String> {
        val done = mutableListOf<String>()
        val today = BogotaTime.today(clock)

        // Cuentas
        val existing = accounts.observeAccounts(includeArchived = true).first()
        for (item in plan.accounts) {
            val match = existing.firstOrNull { it.name.equals(item.name, ignoreCase = true) }
            if (match == null) {
                accounts.create(item.name, item.type, null, item.balance)
                done += "Creé la cuenta ${item.name} con ${item.balance.formatCop()}"
            } else {
                if (match.isArchived) accounts.setArchived(match.id, false)
                val diff = accounts.reconcile(match.id, item.balance)
                done += if (diff.isZero) "${match.name} ya estaba en ${item.balance.formatCop()}" else "Cuadré ${match.name} en ${item.balance.formatCop()}"
            }
        }
        val accountList = accounts.observeAccounts().first()

        // Fondo de emergencia: queda exactamente en el valor indicado.
        plan.emergencyFund?.let { amount ->
            val id = planning.emergencyFundId()
            planning.emptyGoal(id)
            if (amount.centavos > 0) planning.contribute(id, amount, "Configuración importada")
            done += "Fondo de emergencia: ${amount.formatCop()} apartados"
        }

        plan.usdRate?.let { preferences.setUsdRate(it); done += "Tasa del dólar: ${it.formatCop()}" }

        // Pagos e ingresos fijos
        val categoryList = categories.observeAll().first()
        val obligations = planning.observeObligations().first()
        val paid = planning.observePaidObligations(plan.month).first()
        for (f in plan.fixed) {
            val current = obligations.firstOrNull { it.name.equals(f.name, ignoreCase = true) && it.isIncome == f.isIncome }
            val id = planning.saveObligation(
                id = current?.id,
                name = f.name,
                amount = f.amount,
                dayOfMonth = f.day,
                isIncome = f.isIncome,
                categoryId = f.category?.let { name -> categoryList.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id } ?: current?.categoryId,
                accountId = f.account?.let { name -> accountList.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id } ?: current?.accountId,
                active = true,
                currency = f.currency,
            )
            if (f.doneThisMonth && id !in paid) planning.markObligationPaid(id, plan.month, accountId = null)
            done += (if (current == null) "Agregué " else "Actualicé ") + f.name + if (f.doneThisMonth) " (marcado este mes)" else ""
        }

        // Deudas
        val debts = planning.observeDebts().first()
        val paymentCounts = planning.observeDebtPaymentCounts(plan.month).first()
        for (d in plan.debts) {
            val current = debts.firstOrNull { it.name.equals(d.name, ignoreCase = true) }
            val id = planning.saveDebt(
                id = current?.id,
                name = d.name,
                creditor = d.creditor ?: current?.creditor,
                initial = d.total,
                balance = d.balance,
                rateBasisPoints = current?.rateBasisPoints ?: 0,
                rateType = current?.rateType ?: RateType.NONE,
                installment = d.installment,
                dueDay = d.day,
                remainingInstallments = current?.remainingInstallments,
                notes = current?.notes,
                frequency = d.frequency,
            )
            // Se registra el pago del mes sin mover ninguna cuenta y dejando el saldo indicado.
            if (d.paidThisMonth && (paymentCounts[id] ?: 0) == 0 && d.installment.centavos > 0 && plan.month == YearMonth.from(today)) {
                planning.registerDebtPayment(id, d.installment, accountId = null, newBalance = d.balance, date = today)
            }
            done += (if (current == null) "Agregué la deuda " else "Actualicé la deuda ") + d.name
        }
        return done
    }
}
