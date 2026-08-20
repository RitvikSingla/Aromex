package com.humblesolutions.aromex.model

/**
 * A party Aromex buys from and/or sells to — one unified profile backed by exactly
 * one Humble Ledger customer account, so buying and selling net into a single
 * balance. Roles are non-binding labels ([EntityRole]).
 *
 * This is the **read** model. Note there is deliberately **no balance field** —
 * the net balance is read live from HL ([EntityBalance]) and never stored here.
 * [id] doubles as the HL `externalId`. Contact/profile data lives in Firebase;
 * [hlCustomerId]/[hlAccountId]/[syncStatus] track the HL dual-write.
 */
data class Entity(
    val id: String = "",
    val name: String = "",
    val phones: List<String> = emptyList(),
    val email: String? = null,
    val address: String? = null,
    val roles: Set<EntityRole> = emptySet(),
    val notes: String? = null,
    /**
     * Optional tax/GST number (ticket #106) — a business buyer's, printed on their invoice's
     * Bill-To box ("GST/HST No: …") so they can claim their input tax credit. Optional everywhere;
     * absent → no invoice line. Snapshotted onto the sale at checkout, so editing it later never
     * changes an already-issued invoice.
     */
    val taxNumber: String? = null,
    /** The reserved "Walk-in Customer" — cannot be archived or renamed. */
    val isWalkIn: Boolean = false,
    /** False when soft-archived (we never hard-delete a party with history). */
    val isActive: Boolean = true,
    val hlCustomerId: String? = null,
    val hlAccountId: String? = null,
    val syncStatus: HlSyncStatus = HlSyncStatus.PENDING,
)
