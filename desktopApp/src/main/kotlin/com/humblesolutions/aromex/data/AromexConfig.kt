package com.humblesolutions.aromex.data

// Not secrets. TODO(M1-04 Phase B): switch to https:// on the gateway/HL
// hosts once the gateway moves behind a TLS terminator.
internal const val GATEWAY_BASE_URL = "http://68.183.86.89/gateway"
internal const val HL_API_BASE_URL = "http://68.183.86.89/api-server"

// Firebase Auth REST endpoints. Desktop has no Firebase client SDK so we
// authenticate directly with these HTTPS APIs. The `?key=<apiKey>` we send
// is the per-company web API key that /resolve-company already returns.
internal const val IDENTITY_TOOLKIT_BASE = "https://identitytoolkit.googleapis.com/v1"
internal const val SECURETOKEN_BASE = "https://securetoken.googleapis.com/v1"
