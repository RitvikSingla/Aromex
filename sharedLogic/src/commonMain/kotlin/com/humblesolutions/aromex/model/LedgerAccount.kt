package com.humblesolutions.aromex.model

enum class AccountCategory {
    ASSET,
    LIABILITY,
    EQUITY;

    companion object {
        fun fromBalanceSheetKey(key: String): AccountCategory = when (key.lowercase()) {
            "assets", "asset" -> ASSET
            "liabilities", "liability" -> LIABILITY
            "equity" -> EQUITY
            else -> ASSET
        }
    }
}

enum class AccountType {
    CASH,
    BANK,
    CREDIT_CARD,
    RECEIVABLE,
    PAYABLE,
    REVENUE,
    EXPENSE,
    TAX,
    OTHER;

    companion object {
        /**
         * Infers a specific AccountType from the HL account's display name.
         * HL's chart uses names like "Cash", "Bank", "Credit Card",
         * "Accounts Receivable", "Accounts Payable", "Card Clearing".
         * Anything we don't recognise falls back to [OTHER].
         */
        fun fromName(name: String): AccountType {
            val n = name.trim().lowercase()
            return when {
                n == "cash" -> CASH
                n == "bank" || "clearing" in n -> BANK
                "credit card" in n -> CREDIT_CARD
                "receivable" in n -> RECEIVABLE
                "payable" in n -> PAYABLE
                "revenue" in n || "income" in n -> REVENUE
                "expense" in n -> EXPENSE
                "gst" in n || "pst" in n || "tax" in n -> TAX
                else -> OTHER
            }
        }
    }
}

/**
 * One row of a company's chart of accounts, with its current balance.
 * Built from HL's balance-sheet report (which is the only HL read that bundles
 * accounts + balances together).
 *
 * Money is a decimal string — never Double/Float in shared or UI code.
 */
data class LedgerAccount(
    val id: String,
    val name: String,
    val category: AccountCategory,
    val type: AccountType,
    val balance: String,
)
