package com.humblesolutions.aromex.money

import com.humblesolutions.aromex.model.HlSyncStatus
import com.humblesolutions.aromex.model.MoneyAccountRef
import com.humblesolutions.aromex.model.MoneyDirection
import com.humblesolutions.aromex.model.MoneyEntry
import com.humblesolutions.aromex.model.MoneyEntryInput
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.Permissions
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.MoneyEntryRepository
import com.humblesolutions.aromex.usecase.MoneyEntryError
import com.humblesolutions.aromex.usecase.ObserveMoneyEntriesUseCase
import com.humblesolutions.aromex.usecase.RecordMoneyEntryUseCase
import com.humblesolutions.aromex.usecase.ReverseMoneyEntryUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeMoneyEntryRepository : MoneyEntryRepository {
    val recorded = mutableListOf<MoneyEntryInput>()
    val reversed = mutableListOf<String>()

    override suspend fun recordEntry(input: MoneyEntryInput): String {
        recorded += input
        return "entry-${recorded.size}"
    }

    override fun observeRecentEntries(limit: Int): Flow<List<MoneyEntry>> = flowOf(emptyList())

    override suspend fun reverseEntry(entryId: String): String {
        reversed += entryId
        return "reversal-of-$entryId"
    }
}

private fun session(transactions: PermissionLevel) = UserSession(
    uid = "u1",
    email = "u@test",
    displayName = "U",
    role = UserRole.MEMBER,
    permissions = Permissions(transactions = transactions),
    companyId = "co1",
    hlCompanyId = "c1",
    currency = "CAD",
    isActive = true,
)

private val cash = MoneyAccountRef.Cash
private val bank = MoneyAccountRef.Bank
private val rajesh = MoneyAccountRef.Party("ent-rajesh")
private val priya = MoneyAccountRef.Party("ent-priya")

private fun input(
    from: MoneyAccountRef = rajesh,
    to: MoneyAccountRef = cash,
    amount: String = "500.00",
    note: String? = null,
) = MoneyEntryInput(from = from, to = to, amount = amount, note = note, entryDate = 1_700_000_000_000)

class MoneyAccountRefTest {

    @Test
    fun wireRoundTrip_forEveryKind() {
        assertEquals(cash, MoneyAccountRef.fromWire("CASH", null))
        assertEquals(bank, MoneyAccountRef.fromWire("BANK", null))
        assertEquals(rajesh, MoneyAccountRef.fromWire("PARTY", "ent-rajesh"))
        assertEquals("PARTY", rajesh.kind)
        assertEquals("ent-rajesh", rajesh.entityIdOrNull)
    }

    /** A PARTY with no id would otherwise become an entry pointing at nothing — better skipped. */
    @Test
    fun malformedWire_returnsNull_ratherThanAnAccountPointingNowhere() {
        assertNull(MoneyAccountRef.fromWire("PARTY", null))
        assertNull(MoneyAccountRef.fromWire("PARTY", "  "))
        assertNull(MoneyAccountRef.fromWire("WALLET", "x"))
        assertNull(MoneyAccountRef.fromWire(null, null))
    }

    @Test
    fun ownAccounts_areDistinguishedFromParties() {
        assertTrue(cash.isOwnAccount)
        assertTrue(bank.isOwnAccount)
        assertFalse(rajesh.isOwnAccount)
        assertNull(cash.entityIdOrNull)
    }
}

class RecordMoneyEntryUseCaseTest {

    @Test
    fun records_aPartyPayingIntoCash() = runTest {
        val repo = FakeMoneyEntryRepository()
        val id = RecordMoneyEntryUseCase(repo).execute(session(PermissionLevel.MANAGE), input())

        assertEquals("entry-1", id)
        assertEquals(1, repo.recorded.size)
        assertEquals(rajesh, repo.recorded[0].from)
        assertEquals(cash, repo.recorded[0].to)
        assertEquals("500.00", repo.recorded[0].amount)
    }

    @Test
    fun records_everyDirectionTheScreenOffers() = runTest {
        val repo = FakeMoneyEntryRepository()
        val useCase = RecordMoneyEntryUseCase(repo)
        val s = session(PermissionLevel.MANAGE)

        useCase.execute(s, input(from = rajesh, to = cash))   // they paid us
        useCase.execute(s, input(from = bank, to = rajesh))   // we paid/lent them
        useCase.execute(s, input(from = rajesh, to = priya))  // one party settles another's balance
        useCase.execute(s, input(from = cash, to = bank))     // deposit

        assertEquals(4, repo.recorded.size)
    }

    @Test
    fun rejects_movingMoneyToTheSameAccount() = runTest {
        val repo = FakeMoneyEntryRepository()
        assertFailsWith<IllegalArgumentException> {
            RecordMoneyEntryUseCase(repo).execute(session(PermissionLevel.MANAGE), input(from = cash, to = cash))
        }
        assertFailsWith<IllegalArgumentException> {
            RecordMoneyEntryUseCase(repo).execute(session(PermissionLevel.MANAGE), input(from = rajesh, to = rajesh))
        }
        assertTrue(repo.recorded.isEmpty())
    }

    @Test
    fun rejects_zeroNegativeAndNonNumericAmounts() = runTest {
        val repo = FakeMoneyEntryRepository()
        val s = session(PermissionLevel.MANAGE)
        listOf("0", "0.00", "-5", "abc", "", "  ").forEach { bad ->
            assertFailsWith<IllegalArgumentException>("amount '$bad' should be rejected") {
                RecordMoneyEntryUseCase(repo).execute(s, input(amount = bad))
            }
        }
        assertTrue(repo.recorded.isEmpty())
    }

    @Test
    fun rejects_anOverlongNote() = runTest {
        val repo = FakeMoneyEntryRepository()
        assertFailsWith<IllegalArgumentException> {
            RecordMoneyEntryUseCase(repo).execute(
                session(PermissionLevel.MANAGE),
                input(note = "x".repeat(RecordMoneyEntryUseCase.MAX_NOTE_LENGTH + 1)),
            )
        }
    }

    @Test
    fun requiresManage_viewIsNotEnoughToMoveMoney() = runTest {
        val repo = FakeMoneyEntryRepository()
        assertFailsWith<PermissionDeniedException> {
            RecordMoneyEntryUseCase(repo).execute(session(PermissionLevel.VIEW), input())
        }
        assertFailsWith<PermissionDeniedException> {
            RecordMoneyEntryUseCase(repo).execute(session(PermissionLevel.NONE), input())
        }
        assertTrue(repo.recorded.isEmpty())
    }

    /** The form gates its Save button on this, so it must agree with what execute() enforces. */
    @Test
    fun validationError_mirrorsWhatExecuteRejects() {
        assertEquals(MoneyEntryError.SameAccount, RecordMoneyEntryUseCase.validationError(input(from = cash, to = cash)))
        assertEquals(MoneyEntryError.InvalidAmount, RecordMoneyEntryUseCase.validationError(input(amount = "0")))
        assertEquals(
            MoneyEntryError.NoteTooLong,
            RecordMoneyEntryUseCase.validationError(input(note = "x".repeat(501))),
        )
        assertNull(RecordMoneyEntryUseCase.validationError(input()))
    }
}

class ReverseMoneyEntryUseCaseTest {

    private fun entry(
        status: HlSyncStatus = HlSyncStatus.SYNCED,
        reversedBy: String? = null,
        reverses: String? = null,
    ) = MoneyEntry(
        entryId = "e1",
        from = rajesh,
        to = cash,
        amount = "500.00",
        entryDate = 1_700_000_000_000,
        syncStatus = status,
        reversedByEntryId = reversedBy,
        reversesEntryId = reverses,
    )

    @Test
    fun reverses_aSettledEntry() = runTest {
        val repo = FakeMoneyEntryRepository()
        val id = ReverseMoneyEntryUseCase(repo).execute(session(PermissionLevel.MANAGE), entry())

        assertEquals("reversal-of-e1", id)
        assertEquals(listOf("e1"), repo.reversed)
    }

    /** Nothing has reached HL yet, so there is no transaction to mirror. */
    @Test
    fun refuses_anEntryThatHasNotSettled() = runTest {
        val repo = FakeMoneyEntryRepository()
        listOf(HlSyncStatus.PENDING, HlSyncStatus.FAILED).forEach { status ->
            assertFailsWith<IllegalArgumentException> {
                ReverseMoneyEntryUseCase(repo).execute(session(PermissionLevel.MANAGE), entry(status = status))
            }
        }
        assertTrue(repo.reversed.isEmpty())
    }

    /** Double-reversing would re-post the money — the books would move twice for one mistake. */
    @Test
    fun refuses_anEntryAlreadyReversed() = runTest {
        val repo = FakeMoneyEntryRepository()
        assertFailsWith<IllegalArgumentException> {
            ReverseMoneyEntryUseCase(repo).execute(session(PermissionLevel.MANAGE), entry(reversedBy = "e2"))
        }
        assertTrue(repo.reversed.isEmpty())
    }

    /** HL itself rejects reversing a reversal; fail here with a readable message instead. */
    @Test
    fun refuses_reversingAReversal() = runTest {
        val repo = FakeMoneyEntryRepository()
        assertFailsWith<IllegalArgumentException> {
            ReverseMoneyEntryUseCase(repo).execute(session(PermissionLevel.MANAGE), entry(reverses = "e0"))
        }
        assertTrue(repo.reversed.isEmpty())
    }

    @Test
    fun requiresManage() = runTest {
        val repo = FakeMoneyEntryRepository()
        assertFailsWith<PermissionDeniedException> {
            ReverseMoneyEntryUseCase(repo).execute(session(PermissionLevel.VIEW), entry())
        }
    }

    @Test
    fun canReverse_reflectsEveryGuard() {
        assertTrue(entry().canReverse)
        assertFalse(entry(status = HlSyncStatus.PENDING).canReverse)
        assertFalse(entry(reversedBy = "e2").canReverse)
        assertFalse(entry(reverses = "e0").canReverse)
    }
}

class ObserveMoneyEntriesUseCaseTest {

    @Test
    fun viewIsEnoughToRead_butNoneIsNot() = runTest {
        val repo = FakeMoneyEntryRepository()
        ObserveMoneyEntriesUseCase(repo).execute(session(PermissionLevel.VIEW))
        ObserveMoneyEntriesUseCase(repo).execute(session(PermissionLevel.MANAGE))
        assertFailsWith<PermissionDeniedException> {
            ObserveMoneyEntriesUseCase(repo).execute(session(PermissionLevel.NONE))
        }
    }
}

class MoneyDirectionTest {

    private fun entry(from: MoneyAccountRef, to: MoneyAccountRef) = MoneyEntry(
        entryId = "e", from = from, to = to, amount = "100.00", entryDate = 1L,
    )

    /** Green/red across the whole app hangs off this: in, out, or moved-nothing. */
    @Test
    fun direction_readsFromTheBusinessPointOfView() {
        // A party paid us — money in.
        assertEquals(MoneyDirection.IN, entry(rajesh, cash).direction)
        assertEquals(MoneyDirection.IN, entry(rajesh, bank).direction)
        // We paid a party — money out.
        assertEquals(MoneyDirection.OUT, entry(cash, rajesh).direction)
        assertEquals(MoneyDirection.OUT, entry(bank, rajesh).direction)
    }

    /**
     * A transfer changes nothing overall, so it is neither. Colouring cash→bank green would claim
     * income that never happened, and one party settling another's account is their business, not
     * a gain or loss for the shop.
     */
    @Test
    fun transfers_areNeitherInNorOut() {
        assertEquals(MoneyDirection.INTERNAL, entry(cash, bank).direction)
        assertEquals(MoneyDirection.INTERNAL, entry(bank, cash).direction)
        assertEquals(MoneyDirection.INTERNAL, entry(rajesh, priya).direction)
    }

    /** Both halves of a reversal disappear from the day-to-day views. */
    @Test
    fun isCancelled_coversBothHalvesOfAReversal() {
        val plain = entry(rajesh, cash)
        assertFalse(plain.isCancelled)
        assertTrue(plain.copy(reversedByEntryId = "r1").isCancelled)   // the original
        assertTrue(plain.copy(reversesEntryId = "e0").isCancelled)     // the mirror
    }
}
