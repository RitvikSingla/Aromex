package com.humblesolutions.aromex.model

/**
 * Thrown by a use case when the signed-in user lacks the required capability scope.
 * Permissions are enforced in shared app logic (PRD §7.2) — this is the real gate on
 * Desktop, whose Admin-SDK access bypasses Firestore rules.
 */
class PermissionDeniedException(val feature: String) :
    RuntimeException("Missing '$feature' permission")
