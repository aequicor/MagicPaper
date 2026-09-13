package io.aequicor.magicpaper.di

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout

/** Per-request gateway timeouts override this transport timeout for long model responses. */
fun appHttpClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 10_000
    }
}
