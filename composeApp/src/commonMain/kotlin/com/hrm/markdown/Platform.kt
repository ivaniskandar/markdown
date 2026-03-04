package com.hrm.markdown

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform