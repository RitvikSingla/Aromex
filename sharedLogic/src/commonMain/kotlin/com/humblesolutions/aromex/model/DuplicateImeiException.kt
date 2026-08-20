package com.humblesolutions.aromex.model

/**
 * Thrown when adding a unit whose IMEI is already held by an **in-stock** unit — the
 * race-safe, in-stock-only uniqueness guarantee (Brief #41 PO decisions #1/#2). A
 * sold/archived unit with the same IMEI does not block a re-add (its `imeiIndex` entry
 * was released).
 *
 * Declared here for the shared contract; it is thrown by the platform Firestore
 * **transaction** implementations (T3/T4), not from shared code. [imeis] lists the
 * offending IMEIs so the UI can point at the right field(s).
 */
class DuplicateImeiException(val imeis: List<String>) :
    RuntimeException("IMEI already in stock: ${imeis.joinToString()}")
