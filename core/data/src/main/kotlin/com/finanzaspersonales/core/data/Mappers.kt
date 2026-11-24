package com.finanzaspersonales.core.data

import com.finanzaspersonales.core.database.dao.AccountWithBalance
import com.finanzaspersonales.core.database.dao.TransactionRow
import com.finanzaspersonales.core.database.entity.CategoryEntity
import com.finanzaspersonales.core.model.Account
import com.finanzaspersonales.core.model.Category
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.Transaction
import java.time.Instant

internal fun AccountWithBalance.toModel() = Account(
    id = account.id,
    name = account.name,
    type = account.type,
    institution = account.institution,
    initialBalance = Money(account.initialBalanceCentavos),
    balance = Money(balanceCentavos),
    isLiquid = account.isLiquid,
    isArchived = account.isArchived,
)

internal fun CategoryEntity.toModel() = Category(
    id = id,
    name = name,
    parentId = parentId,
    categoryClass = categoryClass,
    isEssential = isEssential,
    isSystem = isSystem,
)

internal fun TransactionRow.toModel() = Transaction(
    id = transaction.id,
    accountId = transaction.accountId,
    accountName = accountName,
    amount = Money(transaction.amountCentavos),
    kind = transaction.kind,
    categoryId = transaction.categoryId,
    categoryName = categoryName,
    description = transaction.description,
    occurredAt = Instant.ofEpochMilli(transaction.occurredAtEpochMs),
    origin = transaction.origin,
    status = transaction.status,
    transferId = transaction.transferId,
    notes = transaction.notes,
)
