package com.humblesolutions.aromex.data

/**
 * Public gateway URL. Not a secret — this is the well-known address apps use
 * for email→company resolution and HL token brokering.
 *
 * TODO(M1-04 Phase B): switch to https://<domain>/gateway once HTTPS lands.
 * When that happens, also remove the cleartext exception from
 * res/xml/network_security_config.xml.
 */
internal const val GATEWAY_BASE_URL = "http://68.183.86.89/gateway"

/**
 * Public Humble Ledger API base URL. Not a secret — published in HL's
 * MOBILE_ADMIN_API.md. The app calls HL directly with the short-lived token
 * brokered by the gateway via /hl-token; HL credentials never live on device.
 *
 * Same host as GATEWAY_BASE_URL (just a different path), so the existing
 * cleartext allowance in network_security_config.xml already covers it.
 *
 * TODO(M1-04 Phase B): switch to https://<domain>/api-server once HL is on HTTPS.
 */
internal const val HL_API_BASE_URL = "http://68.183.86.89/api-server"
