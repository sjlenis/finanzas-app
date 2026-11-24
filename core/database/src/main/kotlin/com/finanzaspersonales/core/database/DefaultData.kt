package com.finanzaspersonales.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.finanzaspersonales.core.model.AccountType
import com.finanzaspersonales.core.model.CategoryClass

/** Datos iniciales que se crean una sola vez, al crear la base de datos. */
object DefaultData {

    data class DefaultAccount(val name: String, val type: AccountType, val institution: String?)
    data class DefaultCategory(val name: String, val categoryClass: CategoryClass, val essential: Boolean, val icon: String)

    val accounts = listOf(
        DefaultAccount("Bancolombia", AccountType.BANK, "Bancolombia"),
        DefaultAccount("Nequi", AccountType.WALLET, "Nequi"),
        DefaultAccount("Efectivo", AccountType.CASH, null),
    )

    val categories = listOf(
        DefaultCategory("Alimentación", CategoryClass.EXPENSE, essential = true, icon = "food"),
        DefaultCategory("Transporte", CategoryClass.EXPENSE, essential = true, icon = "transport"),
        DefaultCategory("Vivienda", CategoryClass.EXPENSE, essential = true, icon = "home"),
        DefaultCategory("Servicios públicos", CategoryClass.EXPENSE, essential = true, icon = "utilities"),
        DefaultCategory("Salud", CategoryClass.EXPENSE, essential = true, icon = "health"),
        DefaultCategory("Educación", CategoryClass.EXPENSE, essential = true, icon = "education"),
        DefaultCategory("Entretenimiento", CategoryClass.EXPENSE, essential = false, icon = "entertainment"),
        DefaultCategory("Compras personales", CategoryClass.EXPENSE, essential = false, icon = "shopping"),
        DefaultCategory("Ropa y accesorios", CategoryClass.EXPENSE, essential = false, icon = "clothes"),
        DefaultCategory("Suscripciones", CategoryClass.EXPENSE, essential = false, icon = "subscriptions"),
        DefaultCategory("Deudas", CategoryClass.EXPENSE, essential = true, icon = "debt"),
        DefaultCategory("Emergencias", CategoryClass.EXPENSE, essential = true, icon = "emergency"),
        DefaultCategory("Otros", CategoryClass.EXPENSE, essential = false, icon = "other"),
        DefaultCategory("Ingresos laborales", CategoryClass.INCOME, essential = false, icon = "salary"),
        DefaultCategory("Ingresos adicionales", CategoryClass.INCOME, essential = false, icon = "extra_income"),
        DefaultCategory("Préstamos", CategoryClass.NEUTRAL, essential = false, icon = "loan"),
        DefaultCategory("Transferencias", CategoryClass.NEUTRAL, essential = false, icon = "transfer"),
    )

    /** Se ejecuta dentro de la transacción de creación de la base de datos. */
    fun insertInto(db: SupportSQLiteDatabase, nowEpochMs: Long) {
        accounts.forEachIndexed { index, a ->
            db.execSQL(
                "INSERT INTO accounts (name, type, institution, last_digits, initial_balance_centavos, " +
                    "initial_balance_at, is_liquid, is_archived, sort_order) VALUES (?, ?, ?, NULL, 0, ?, 1, 0, ?)",
                arrayOf<Any?>(a.name, a.type.name, a.institution, nowEpochMs, index),
            )
        }
        categories.forEach { c ->
            db.execSQL(
                "INSERT INTO categories (name, parent_id, category_class, is_essential, icon_key, is_system) " +
                    "VALUES (?, NULL, ?, ?, ?, 1)",
                arrayOf<Any?>(c.name, c.categoryClass.name, if (c.essential) 1 else 0, c.icon),
            )
        }
    }
}
