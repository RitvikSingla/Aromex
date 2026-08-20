package com.humblesolutions.aromex.model

/**
 * Thrown when a sale transaction fails because of **write contention** rather than a lost
 * unit — e.g. two cashiers commit the same unit at the same instant and Firestore's
 * transaction retries are exhausted ("too many retries"), or both sales starve each other
 * (livelock). Unlike [AlreadySoldException], no unit is known to be gone: a re-check found
 * every unit still in stock, so the commit simply never landed and **nothing was sold**. The
 * cashier should just try again.
 *
 * Declared here for the shared contract; it is thrown by the platform repository
 * implementations after re-reading stock, so the UI can show a calm "please try again"
 * instead of a raw backend exception.
 */
class SaleContentionException :
    RuntimeException("Sale could not be completed due to concurrent activity; please try again.")
