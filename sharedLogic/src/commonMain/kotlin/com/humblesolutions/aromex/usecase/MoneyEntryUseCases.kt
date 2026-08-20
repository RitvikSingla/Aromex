package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.MoneyAccountRef
import com.humblesolutions.aromex.model.MoneyEntry
import com.humblesolutions.aromex.model.MoneyEntryInput
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.MoneyEntryRepository
import com.humblesolutions.aromex.util.Money
import kotlinx.coroutines.flow.Flow

/** Requires MANAGE on `transactions` — recording a movement changes the books. */
internal fun requireTransactionsManage(session: UserSession) {
    if (session.permissions.transactions != PermissionLevel.MANAGE) {
        throw PermissionDeniedException("transactions")
    }
}

/** Requires at least VIEW on `transactions` (reading the feed or a statement). */
internal fun requireTransactionsAccess(session: UserSession) {
    if (session.permissions.transactions == PermissionLevel.NONE) {
        throw PermissionDeniedException("transactions")
    }
}

/**
 * Records one money movement (ticket #90): a customer paying their balance down, the shop paying
 * someone back or lending, one party settling another's account, or a transfer between the shop's
 * own cash and bank.
 *
 * Validation lives here rather than in the UI so every platform gets the same rules — but the Cloud
 * Function re-checks all of it, because a client can lie and this writes to the books.
 */
class RecordMoneyEntryUseCase(
    private val repository: MoneyEntryRepository,
) {
    suspend fun execute(session: UserSession, input: MoneyEntryInput): String {
        requireTransactionsManage(session)
        validate(input)
        return repository.recordEntry(input)
    }

    companion object {
        /** Longest note we'll store — generous for a real note, short enough not to bloat the doc. */
        const val MAX_NOTE_LENGTH = 500

        /**
         * The rules, exposed so a ViewModel can gate its Save button on exactly what the use case
         * will enforce instead of maintaining a second, drifting copy.
         */
        fun validationError(input: MoneyEntryInput): MoneyEntryError? = when {
            input.from == input.to -> MoneyEntryError.SameAccount
            !Money.isValidPositiveDecimal(input.amount) -> MoneyEntryError.InvalidAmount
            (input.note?.length ?: 0) > MAX_NOTE_LENGTH -> MoneyEntryError.NoteTooLong
            else -> null
        }

        internal fun validate(input: MoneyEntryInput) {
            when (validationError(input)) {
                MoneyEntryError.SameAccount ->
                    throw IllegalArgumentException("From and To must be different accounts")
                MoneyEntryError.InvalidAmount ->
                    throw IllegalArgumentException("Amount must be a positive decimal")
                MoneyEntryError.NoteTooLong ->
                    throw IllegalArgumentException("Note is too long (max $MAX_NOTE_LENGTH)")
                null -> Unit
            }
        }
    }
}

/** Why a money entry can't be saved — one case per inline message the form shows. */
enum class MoneyEntryError { SameAccount, InvalidAmount, NoteTooLong }

/** Live feed of recent movements, newest first (ticket #90). Read access is enough. */
class ObserveMoneyEntriesUseCase(
    private val repository: MoneyEntryRepository,
) {
    fun execute(session: UserSession, limit: Int = DEFAULT_LIMIT): Flow<List<MoneyEntry>> {
        requireTransactionsAccess(session)
        return repository.observeRecentEntries(limit)
    }

    companion object {
        const val DEFAULT_LIMIT = 50
    }
}

/**
 * Corrects a mistake by posting the entry's mirror (ticket #90). Entries are never edited or
 * deleted — the original stays visible next to its reversal, which together are the audit trail.
 *
 * Refuses anything HL would refuse anyway ([MoneyEntry.canReverse]): an entry HL hasn't accepted
 * yet (there is nothing to mirror), one already reversed (double-reversal would re-post the money),
 * and a reversal itself (HL rejects reversing a reversal).
 */
class ReverseMoneyEntryUseCase(
    private val repository: MoneyEntryRepository,
) {
    suspend fun execute(session: UserSession, entry: MoneyEntry): String {
        requireTransactionsManage(session)
        require(entry.canReverse) {
            when {
                !entry.isSettled -> "This entry hasn't reached the ledger yet"
                entry.isReversed -> "This entry was already reversed"
                else -> "A reversal can't itself be reversed"
            }
        }
        return repository.reverseEntry(entry.entryId)
    }
}

/**
 * A party's statement from HL (ticket #91) — every entry against them with HL's running balance.
 *
 * Returns null when HL has no customer for this party yet (a brand-new entity whose sync hasn't
 * landed), which the UI shows as "no activity" rather than as an error.
 */
class GetAccountStatementUseCase(
    private val repository: com.humblesolutions.aromex.repository.EntityLedgerRepository,
) {
    suspend fun execute(
        session: UserSession,
        entityId: String,
        from: String? = null,
        to: String? = null,
        page: Int = 1,
    ) = run {
        requireTransactionsAccess(session)
        repository.getStatement(entityId, from, to, page)
    }
}

/** Convenience for the pickers: the shop's own accounts, in the order they should be offered. */
val OWN_MONEY_ACCOUNTS: List<MoneyAccountRef> = listOf(MoneyAccountRef.Cash, MoneyAccountRef.Bank)
