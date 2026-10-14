package com.self.daymark

object ExperienceScale {
    val values = -2..2
    fun label(value: Int): String = when (value) {
        -2 -> "非常不值"
        -1 -> "不值"
        0 -> "中性"
        1 -> "值得"
        2 -> "非常值得"
        else -> error("体验评分应为 −2 到 2")
    }
    fun text(value: Int) = "$value · ${label(value)}"
    fun fromLegacy(value: Int?): Int? = value?.let {
        require(it in 1..5) { "旧版备份的体验评分应为 1–5" }
        it - 3
    }
    fun upgradeLegacy(data: LedgerData) = data.copy(
        events = data.events.map { it.copy(feeling=fromLegacy(it.feeling), worth=fromLegacy(it.worth)) },
        forecasts = data.forecasts.map { it.copy(expectedFeeling=fromLegacy(it.expectedFeeling)) }
    )
}
