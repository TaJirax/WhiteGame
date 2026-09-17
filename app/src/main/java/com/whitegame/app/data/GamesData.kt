package com.whitegame.app.data

import com.whitegame.app.model.GameInfo

object GamesData {
    fun fallback(): List<GameInfo> = listOf(
        GameInfo(
            "mlbb", "Mobile Legends", "MOBA",
            listOf("mlbb.mob.com", "api.mobilelegends.com"),
            listOf(443), listOf(5000),
            listOf("SG", "ID"), "Critical <60ms"
        ),
        GameInfo(
            "freefire", "Free Fire", "Battle Royale",
            listOf("ff.garena.com"),
            listOf(443), listOf(10012),
            listOf("SG", "TH"), "Critical <70ms"
        )
    )
}
