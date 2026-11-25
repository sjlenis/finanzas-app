package com.finanzaspersonales.feature.planning

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.common.BogotaTime
import com.finanzaspersonales.core.common.instantForDate
import com.finanzaspersonales.core.data.AccountRepository
import com.finanzaspersonales.core.data.CategoryRepository
import com.finanzaspersonales.core.data.Debt
import com.finanzaspersonales.core.data.DebtStatus
import com.finanzaspersonales.core.data.PlanningRepository
import com.finanzaspersonales.core.data.TransactionRepository
import com.finanzaspersonales.core.model.Account
import com.finanzaspersonales.core.model.Category
import com.finanzaspersonales.core.model.CategoryClass
import com.finanzaspersonales.core.model.ManualEntryType
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.TransactionOrigin
import com.finanzaspersonales.core.model.formatCop
import com.finanzaspersonales.engine.finance.Amortization
import com.finanzaspersonales.engine.finance.RateType
import com.finanzaspersonales.engine.finance.Rates
import com.finanzaspersonales.engine.finance.principal
import com.finanzaspersonales.engine.nlp.DocRate
import com.finanzaspersonales.engine.nlp.DocumentReader
import com.finanzaspersonales.engine.nlp.DocumentResult
import com.finanzaspersonales.engine.nlp.DraftKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Movimiento leído de un comprobante o factura, listo para revisar. */
data class MovementForm(
    val isIncome: Boolean,
    val amountDigits: String,
    val description: String,
    val categoryId: Long?,
    val accountId: Long?,
    val date: LocalDate,
)

sealed interface ScanState {
    data object Idle : ScanState
    data object Reading : ScanState
    data class DebtFound(
        val form: DebtForm,
        /** Deuda registrada con el mismo nombre: se actualiza en vez de duplicarla. */
        val existing: Debt?,
        /** Lo que se leyó, en frases cortas para mostrar. */
        val summary: List<String>,
        val notes: List<String>,
    ) : ScanState
    data class MovementFound(val form: MovementForm, val summary: List<String>) : ScanState
    data class Failed(val message: String) : ScanState
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val reader: PhotoTextReader,
    private val planning: PlanningRepository,
    private val transactions: TransactionRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val clock: Clock,
) : ViewModel() {

    val accounts: StateFlow<List<Account>> = accounts.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories: StateFlow<List<Category>> = categories.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    fun reset() { _state.value = ScanState.Idle }

    /** Lee la imagen. [temporary] = foto tomada con la cámara: se borra apenas se lee. */
    fun read(uri: Uri, temporary: java.io.File? = null) = viewModelScope.launch {
        _state.value = ScanState.Reading
        val lines = runCatching { reader.read(uri) }
        temporary?.delete()
        val result = lines.getOrElse {
            _state.value = ScanState.Failed("No pude leer la imagen. Prueba con otra foto o una captura de pantalla.")
            return@launch
        }
        val today = BogotaTime.today(clock)
        val cats = categories.value.ifEmpty { categories.first() }
        _state.value = when (val doc = DocumentReader.read(result, today, cats.map { it.name })) {
            is DocumentResult.Debt -> debtState(doc)
            is DocumentResult.Movement -> movementState(doc, cats)
            is DocumentResult.Unknown -> ScanState.Failed(doc.reason)
        }
    }

    private suspend fun debtState(d: DocumentResult.Debt): ScanState {
        if (d.balance == null) return ScanState.Failed("Parece la información de un crédito, pero no encontré el saldo. Prueba con una captura donde se vea \"Debes\" o \"Saldo\".")
        val rateType = when (d.rateType) {
            DocRate.EFFECTIVE_ANNUAL -> RateType.EFFECTIVE_ANNUAL
            DocRate.MONTHLY -> RateType.MONTHLY
            null -> RateType.NONE
        }
        val bp = d.rateBasisPoints ?: 0
        val monthly = Rates.monthlyRate(bp, if (bp == 0) RateType.NONE else rateType)
        val remaining = d.remainingInstallments
        val notes = mutableListOf<String>()
        val installment = d.installment ?: if (remaining != null && remaining > 0) {
            notes += "La cuota no aparece en la foto: la calculé con la tasa y las $remaining cuotas que faltan" +
                (d.insurance?.let { ", más ${it.formatCop()} de seguros" } ?: "") + ". Revísala con tu entidad."
            Amortization.installment(d.balance!!, monthly, remaining) + (d.insurance ?: Money.ZERO)
        } else {
            notes += "La cuota no aparece en la foto: escríbela antes de guardar."
            null
        }
        // Monto inicial: si se sabe cuántas cuotas tenía, se estima el capital prestado.
        val initial = if (d.installment == null && installment != null && d.totalInstallments != null && remaining != null) {
            notes += "El monto prestado es una estimación a partir de las ${d.totalInstallments} cuotas."
            maxOf(d.balance!!, Amortization.principal(installment - (d.insurance ?: Money.ZERO), monthly, d.totalInstallments!!))
        } else d.balance!!
        val existing = planning.observeDebts().first().firstOrNull { it.status == DebtStatus.ACTIVE && it.name.equals(d.name, ignoreCase = true) }
        val extra = listOfNotNull(
            "Leído de una foto el ${formatDate(BogotaTime.today(clock))}.",
            d.maskedNumber?.let { "Crédito $it." },
            d.disbursedOn?.let { "Desembolso: ${formatDate(it)}." },
            d.insurance?.let { "Seguros y otros cargos: ${it.formatCop()} al mes." },
            d.currentInterest?.let { "Intereses corrientes del periodo: ${it.formatCop()}." },
        ).joinToString(" ")
        val form = DebtForm(
            name = d.name,
            creditor = existing?.creditor.orEmpty(),
            initialDigits = (existing?.initial?.takeIf { it > initial } ?: initial).toDigits(),
            balanceDigits = d.balance.toDigits(),
            rateText = if (bp == 0) "" else java.math.BigDecimal(bp).movePointLeft(2).stripTrailingZeros().toPlainString().replace('.', ','),
            rateType = rateType,
            paymentDigits = installment.toDigits(),
            dueDay = d.dueDay?.toString().orEmpty(),
            installments = remaining?.toString().orEmpty(),
            notes = extra.take(200),
        )
        val summary = listOfNotNull(
            "Nombre: ${d.name}",
            "Saldo: ${d.balance!!.formatCop()}",
            installment?.let { "Cuota: ${it.formatCop()}" + if (d.installment == null) " (estimada)" else "" },
            d.dueDay?.let { "Día de pago: $it de cada mes" },
            d.rateBasisPoints?.let { "Tasa: ${form.rateText} % ${if (rateType == RateType.MONTHLY) "mensual" else "E.A."}" },
            if (d.paidInstallments != null && d.totalInstallments != null) "Cuotas: ${d.paidInstallments} pagadas de ${d.totalInstallments}" else null,
            d.maskedNumber?.let { "Número: $it" },
        )
        return ScanState.DebtFound(form, existing, summary, notes)
    }

    private fun movementState(m: DocumentResult.Movement, cats: List<Category>): ScanState {
        val draft = m.draft
        val isIncome = draft.kind == DraftKind.INCOME
        val wanted = if (isIncome) CategoryClass.INCOME else CategoryClass.EXPENSE
        val category = draft.categoryName?.let { n -> cats.firstOrNull { it.name.equals(n, ignoreCase = true) && it.categoryClass == wanted } }
        val account = draft.accountName?.let { n -> accounts.value.firstOrNull { it.name.equals(n, ignoreCase = true) } } ?: accounts.value.firstOrNull()
        val form = MovementForm(isIncome, draft.amount?.toDigits().orEmpty(), draft.description, category?.id, account?.id, draft.date)
        return ScanState.MovementFound(form, movementSummary(form))
    }

    internal fun movementSummary(f: MovementForm): List<String> = listOfNotNull(
        "Tipo: ${if (f.isIncome) "Ingreso" else "Gasto"}",
        f.amountDigits.toLongOrNull()?.let { "Valor: ${Money.ofPesos(it).formatCop()}" },
        "Descripción: ${f.description}",
        "Fecha: ${formatDate(f.date)}",
        f.accountId?.let { id -> accounts.value.firstOrNull { it.id == id }?.let { "Cuenta: ${it.name}" } },
        f.categoryId?.let { id -> categories.value.firstOrNull { it.id == id }?.let { "Categoría: ${it.name}" } },
    )

    fun updateMovement(form: MovementForm) {
        _state.value = ScanState.MovementFound(form, movementSummary(form))
    }

    fun saveDebt(form: DebtForm, existing: Debt?, onSaved: (Long) -> Unit = {}) = viewModelScope.launch {
        runCatching {
            val rate = requireNotNull(form.rateBasisPoints) { "Revisa la tasa de interés" }
            val balance = digits(form.balanceDigits)
            val initial = digits(form.initialDigits).let { if (it < balance) balance else it }
            planning.saveDebt(
                id = existing?.id, name = form.name, creditor = form.creditor, initial = initial, balance = balance,
                rateBasisPoints = rate, rateType = if (rate == 0) RateType.NONE else form.rateType,
                installment = digits(form.paymentDigits), dueDay = form.dueDay.toIntOrNull(),
                remainingInstallments = form.installments.toIntOrNull(),
                notes = listOfNotNull(existing?.notes?.takeIf { it != form.notes }, form.notes).joinToString(" ").take(500),
                frequency = form.frequency,
            )
        }.onSuccess {
            _message.value = if (existing != null) "Actualicé la deuda «${form.name}»." else "Guardé la deuda «${form.name}». Ya aparece en Deudas y en tu calendario."
            _state.value = ScanState.Idle
            onSaved(it)
        }.onFailure { _message.value = it.message }
    }

    fun saveMovement(form: MovementForm) = viewModelScope.launch {
        runCatching {
            val amount = requireNotNull(form.amountDigits.toLongOrNull()?.takeIf { it > 0 }) { "Escribe el valor" }
            val account = requireNotNull(form.accountId) { "Elige la cuenta" }
            transactions.saveManual(
                id = null,
                type = if (form.isIncome) ManualEntryType.INCOME else ManualEntryType.EXPENSE,
                amount = Money.ofPesos(amount),
                accountId = account,
                categoryId = form.categoryId,
                description = form.description,
                occurredAt = BogotaTime.instantForDate(form.date, clock),
                notes = "Leído de una foto",
                origin = TransactionOrigin.IMPORT,
            )
        }.onSuccess {
            _message.value = "Guardado. Ya aparece en Movimientos y en tus saldos."
            _state.value = ScanState.Idle
        }.onFailure { _message.value = it.message }
    }

    private fun digits(s: String): Money = s.toLongOrNull()?.let { Money.ofPesos(it) } ?: Money.ZERO

    private fun formatDate(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", esCO))
}
