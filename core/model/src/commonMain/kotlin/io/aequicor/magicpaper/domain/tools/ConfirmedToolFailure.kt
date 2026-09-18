package io.aequicor.magicpaper.domain.tools

/**
 * The external owner proved that this operation failed. Network failures and cancelled
 * awaiters must never implement this marker: their external outcome may still be unknown.
 */
interface ConfirmedToolFailure
