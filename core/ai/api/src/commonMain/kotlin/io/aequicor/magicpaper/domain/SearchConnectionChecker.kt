package io.aequicor.magicpaper.domain

enum class SearchConnection { QUERIT, GOOGLE, WIKIPEDIA, CONTENT }
data class SearchConnectionResult(val success: Boolean, val message: String)

fun interface SearchConnectionChecker {
    suspend fun check(connection: SearchConnection, settings: AppSettings): SearchConnectionResult
}
