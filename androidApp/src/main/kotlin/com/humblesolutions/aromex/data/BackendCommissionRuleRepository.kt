package com.humblesolutions.aromex.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.ktx.firestore
import com.humblesolutions.aromex.model.CommissionRule
import com.humblesolutions.aromex.model.CommissionRuleInput
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.RateKind
import com.humblesolutions.aromex.repository.CommissionRuleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Firestore-backed [CommissionRuleRepository] — `commissionRules/{ruleId}` standing arrangements
 * (ticket #97). Firebase-only (a rule touches no Humble Ledger). Every write is reached only
 * through the shared commission-rule use cases, which gate on `role == ADMIN`; the mobile
 * Firestore rules are the backstop.
 */
class BackendCommissionRuleRepository(
    private val context: Context,
    private val config: FirebaseClientConfig,
) : CommissionRuleRepository {

    private val app get() = FirebaseAppFactory.get(context, config)
    private val db get() = Firebase.firestore(app)
    private fun currentUid(): String? = FirebaseAuth.getInstance(app).currentUser?.uid

    override fun observeRules(includeInactive: Boolean): Flow<List<CommissionRule>> = callbackFlow {
        val query = if (includeInactive) {
            db.collection(COLLECTION)
        } else {
            db.collection(COLLECTION).whereEqualTo("isActive", true)
        }
        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot?.documents?.mapNotNull { it.toRule() }.orEmpty())
        }
        awaitClose { registration.remove() }
    }

    override suspend fun saveRule(input: CommissionRuleInput): String = withContext(Dispatchers.IO) {
        val editing = input.ruleId.isNotBlank()
        val ref = if (editing) db.collection(COLLECTION).document(input.ruleId)
        else db.collection(COLLECTION).document()
        // Editing keeps the same doc so earned commissions keep pointing at a live rule; a
        // create seeds createdBy/createdAt, an edit only bumps updatedAt.
        val base = mutableMapOf<String, Any?>(
            "ruleId" to ref.id,
            "locationAttributeId" to input.locationAttributeId,
            "payeeEntityId" to input.payeeEntityId,
            "rateKind" to input.rateKind.name,
            "rate" to input.rate, // decimal String — never a Double
            "isActive" to input.isActive,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        if (!editing) {
            base["createdBy"] = currentUid()
            base["createdAt"] = FieldValue.serverTimestamp()
        }
        ref.set(base, SetOptions.merge()).await()
        ref.id
    }

    override suspend fun archiveRule(ruleId: String): Unit = withContext(Dispatchers.IO) {
        db.collection(COLLECTION).document(ruleId)
            .update(mapOf("isActive" to false, "updatedAt" to FieldValue.serverTimestamp()))
            .await()
    }

    private fun DocumentSnapshot.toRule(): CommissionRule? {
        if (!exists()) return null
        val rateKind = RateKind.fromWire(getString("rateKind") ?: "") ?: return null
        return CommissionRule(
            ruleId = getString("ruleId") ?: id,
            locationAttributeId = getString("locationAttributeId").orEmpty(),
            payeeEntityId = getString("payeeEntityId").orEmpty(),
            rateKind = rateKind,
            rate = getString("rate") ?: "0",
            isActive = getBoolean("isActive") ?: true,
            createdBy = getString("createdBy"),
            createdAt = getTimestamp("createdAt")?.toDate()?.time,
            updatedAt = getTimestamp("updatedAt")?.toDate()?.time,
        )
    }

    private companion object {
        const val COLLECTION = "commissionRules"
    }
}
