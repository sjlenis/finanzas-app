package com.finanzaspersonales.core.database

import androidx.room.TypeConverter
import com.finanzaspersonales.core.model.AccountType
import com.finanzaspersonales.core.model.CategoryClass
import com.finanzaspersonales.core.model.TransactionKind
import com.finanzaspersonales.core.model.TransactionOrigin
import com.finanzaspersonales.core.model.TransactionStatus

/** Los enums se guardan por nombre, no por posición, para que reordenarlos no corrompa datos. */
internal class Converters {
    @TypeConverter fun accountType(v: AccountType): String = v.name
    @TypeConverter fun accountType(v: String): AccountType = AccountType.valueOf(v)
    @TypeConverter fun categoryClass(v: CategoryClass): String = v.name
    @TypeConverter fun categoryClass(v: String): CategoryClass = CategoryClass.valueOf(v)
    @TypeConverter fun kind(v: TransactionKind): String = v.name
    @TypeConverter fun kind(v: String): TransactionKind = TransactionKind.valueOf(v)
    @TypeConverter fun origin(v: TransactionOrigin): String = v.name
    @TypeConverter fun origin(v: String): TransactionOrigin = TransactionOrigin.valueOf(v)
    @TypeConverter fun status(v: TransactionStatus): String = v.name
    @TypeConverter fun status(v: String): TransactionStatus = TransactionStatus.valueOf(v)
}
