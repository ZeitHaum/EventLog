package com.self.daymark

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.*

object Time {
    fun recordAt(day: LocalDate): Long = if (day == LocalDate.now()) System.currentTimeMillis() else start(day) + 12 * 3600000L
    fun day(epoch: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate()
    fun start(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()) = day.atStartOfDay(zone).toInstant().toEpochMilli()
    fun parse(date: String, clock: String): Long = LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(clock)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    fun clock(epoch: Long) = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    fun iso(epoch: Long) = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    fun local(epoch: Long) = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}

fun parseMoney(text: String, signed: Boolean = false): Long? {
    if (text.isBlank()) return null
    val number = try { BigDecimal(text.trim()).setScale(2, RoundingMode.UNNECESSARY) } catch (_: Exception) { throw IllegalArgumentException("金额须为数字，最多两位小数") }
    require(signed || number.signum() >= 0) { "金额不能小于零" }
    require(number.abs() <= BigDecimal("10000000000")) { "金额过大" }
    return number.movePointRight(2).longValueExact()
}
fun money(cents: Long?): String = cents?.let { BigDecimal.valueOf(it, 2).setScale(2).toPlainString() } ?: "—"
fun minutesText(minutes: Long?): String = when {
    minutes == null -> "—"
    minutes < 60 -> "${minutes}分"
    minutes % 60 == 0L -> "${minutes / 60}小时"
    else -> "${minutes / 60}时${minutes % 60}分"
}
fun parseMinutes(text: String): Long? {
    if (text.isBlank()) return null
    val value = text.trim().toLongOrNull()
    require(value != null && value in 0..44640) { "时长须为 0–44640 的整数分钟" }
    return value
}

data class DailySummary(val date: LocalDate, val expense: Long, val income: Long, val adjustment: Long, val activityMinutes: Long, val sleepMinutes: Long, val unknownDurations: Int, val unknownCosts: Int, val eventCount: Int, val sleepConfirmed: Boolean, val anyData: Boolean) {
    val net get() = income - expense + adjustment
}

object Analytics {
    fun recordedDates(data: LedgerData): List<LocalDate> {
        val dates = sortedSetOf<LocalDate>()
        fun include(start: Long, end: Long?) {
            var day = Time.day(start)
            val last = Time.day(if (end != null && end > start) end - 1 else start)
            while (day <= last) { dates.add(day); day = day.plusDays(1) }
        }
        data.activeEvents.forEach { include(it.startAt, it.endAt) }
        data.cash.forEach { dates.add(Time.day(it.occurredAt)) }
        data.sleeps.forEach { s ->
            dates.add(LocalDate.parse(s.day))
            if (s.status == "timed") include(s.startAt!!, s.endAt)
        }
        data.balances.forEach { dates.add(Time.day(it.asOf)) }
        return dates.toList()
    }
    fun unionMinutes(intervals: List<Pair<Long, Long>>, from: Long, to: Long): Long {
        val clipped = intervals.map { max(from, it.first) to min(to, it.second) }.filter { it.second > it.first }.sortedBy { it.first }
        var total = 0L
        var left = 0L
        var right = 0L
        clipped.forEachIndexed { i, (a, b) ->
            if (i == 0) { left = a; right = b }
            else if (a <= right) right = max(right, b)
            else { total += right - left; left = a; right = b }
        }
        if (clipped.isNotEmpty()) total += right - left
        return total / 60000
    }
    fun daily(data: LedgerData, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DailySummary {
        val from = Time.start(date, zone); val to = Time.start(date.plusDays(1), zone)
        val events = data.activeEvents.filter { it.status == "done" }
        val started = events.filter { it.startAt in from until to }
        val transactions = data.cash.filter { it.occurredAt in from until to }
        val sleep = data.sleeps.filter { it.status == "timed" }.map { it.startAt!! to it.endAt!! }
        // Legacy intervals retain their union semantics; new duration-only records belong to their selected date.
        val activity = unionMinutes(events.mapNotNull { e -> e.endAt?.let { e.startAt to it } }, from, to) + started.sumOf { it.actualMinutes ?: 0L }
        val sleepMinutes = unionMinutes(sleep, from, to)
        val confirmed = data.sleeps.any { it.day == date.toString() }
        return DailySummary(date, -transactions.filter { it.kind == "expense" }.sumOf { it.cents }, transactions.filter { it.kind == "income" }.sumOf { it.cents }, transactions.filter { it.kind == "adjustment" }.sumOf { it.cents }, activity, sleepMinutes, started.count { it.durationMinutes == null }, started.count { !it.costKnown }, started.size, confirmed, started.isNotEmpty() || transactions.isNotEmpty() || sleepMinutes > 0 || confirmed || activity > 0)
    }
    fun balanceAt(data: LedgerData, at: Long): Long? {
        val anchor = data.balances.filter { it.asOf <= at }.maxWithOrNull(compareBy<BalanceAnchor> { it.asOf }.thenBy { it.createdAt }) ?: return null
        return anchor.cents + data.cash.filter { it.occurredAt >= anchor.asOf && it.occurredAt <= at }.sumOf { it.cents }
    }
    fun isProspective(forecast: Forecast, event: Event): Boolean = forecast.prospective && forecast.madeAt <= event.startAt && (event.firstOutcomeAt == null || forecast.madeAt < event.firstOutcomeAt)
    fun baseline(data: LedgerData, event: Event): Forecast? = data.forecasts.filter { it.eventId == event.id && isProspective(it, event) }.minByOrNull { it.madeAt }
    data class ErrorRow(val event: Event, val forecast: Forecast, val minutesError: Long?, val costError: Long?, val feelingError: Int?)
    fun errors(data: LedgerData): List<ErrorRow> = data.activeEvents.filter { it.status == "done" }.mapNotNull { event ->
        val forecast = baseline(data, event) ?: return@mapNotNull null
        ErrorRow(event, forecast,
            if (event.durationMinutes != null && forecast.minutes != null) event.durationMinutes!! - forecast.minutes else null,
            if (data.cost(event) != null && forecast.costCents != null) data.cost(event)!! - forecast.costCents else null,
            if (event.feeling != null && forecast.expectedFeeling != null) event.feeling - forecast.expectedFeeling else null)
    }
    data class ProbabilityRow(val event: Event, val forecast: Forecast, val resolution: Resolution) { val brier get() = (forecast.probability!! / 100.0 - if (resolution.happened) 1.0 else 0.0).pow(2) }
    fun probabilityRows(data: LedgerData): List<ProbabilityRow> = data.activeEvents.mapNotNull { event ->
        val f = data.forecasts.filter { it.eventId == event.id && it.probability != null && isProspective(it, event) }.minByOrNull { it.madeAt } ?: return@mapNotNull null
        val r = data.resolutions.find { it.forecastId == f.id && it.resolvedAt >= f.madeAt } ?: return@mapNotNull null
        ProbabilityRow(event, f, r)
    }
    fun calendarDays(month: YearMonth): List<LocalDate?> = List(month.atDay(1).dayOfWeek.value - 1) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
}
