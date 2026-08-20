package com.humblesolutions.aromex.navigation

/**
 * Top-level app destinations. An enum (implicitly `Serializable`) so it can be
 * held in `rememberSaveable` — that's what keeps the current screen across
 * configuration changes (rotation / theme switch) instead of snapping back to
 * Home. The entities feature has its own nested NavHost.
 */
enum class Route { Splash, Login, Home, Entities, Inventory, Sales, SalesHistory, CommissionRules }
