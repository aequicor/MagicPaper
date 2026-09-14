package io.aequicor.magicpaper.domain

data class SessionAnswer(val text: String, val sources: List<SearchHit> = emptyList(), val attachments: List<Attachment> = emptyList())
