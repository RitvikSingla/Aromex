package com.humblesolutions.aromex.model

enum class PermissionLevel { MANAGE, VIEW, NONE }

data class Permissions(
    val sales: PermissionLevel = PermissionLevel.NONE,
    val purchases: PermissionLevel = PermissionLevel.NONE,
    val inventory: PermissionLevel = PermissionLevel.NONE,
    val transactions: PermissionLevel = PermissionLevel.NONE,
    val profiles: PermissionLevel = PermissionLevel.NONE,
    val balances: PermissionLevel = PermissionLevel.NONE,
    val reports: PermissionLevel = PermissionLevel.NONE,
    val statistics: PermissionLevel = PermissionLevel.NONE,
    val histories: PermissionLevel = PermissionLevel.NONE,
    val ledgers: PermissionLevel = PermissionLevel.NONE,
    val settings: PermissionLevel = PermissionLevel.NONE,
    val userMgmt: Boolean = false,
)
