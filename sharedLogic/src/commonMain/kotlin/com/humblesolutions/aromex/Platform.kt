package com.humblesolutions.aromex

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform