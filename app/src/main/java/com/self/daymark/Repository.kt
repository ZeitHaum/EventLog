package com.self.daymark

import androidx.room.withTransaction
import java.time.LocalDate

class LedgerRepository(private val db: LedgerDatabase) {
    val dao get() = db.dao()

    suspend fun savePlan(input: Plan) = db.withTransaction {
        val old = dao.plans().find { it.id == input.id }
        require(old?.completedAt == null && old?.deletedAt == null) { "已完成事项请编辑对应记录" }
        val plan = input.copy(eventId = old?.eventId, completedAt = null, savedCostCents = old?.savedCostCents,
            createdAt = old?.createdAt ?: input.createdAt, updatedAt = System.currentTimeMillis(), deletedAt = null)
        plan.validate(); dao.save(plan)
    }

    suspend fun completePlan(id: String, actualMinutes: Long? = null): String = db.withTransaction {
        val plan = dao.plans().first { it.id == id }
        require(plan.deletedAt == null) { "事项已删除" }
        if (plan.completedAt != null) return@withTransaction plan.eventId!!
        require(actualMinutes == null || actualMinutes in 0..44640) { "实际耗时无效" }
        val now = System.currentTimeMillis()
        val old = dao.events().find { it.id == plan.eventId }
        val event = old?.copy(deletedAt = null, updatedAt = now) ?: Event(id = newId(), title = plan.title,
            startAt = now, description = plan.notes, actualMinutes = actualMinutes, firstOutcomeAt = now)
        saveEvent(event, plan.savedCostCents, null)
        dao.save(plan.copy(eventId = event.id, completedAt = now, updatedAt = now, savedCostCents = null))
        event.id
    }

    suspend fun undoPlan(id: String) = db.withTransaction {
        val plan = dao.plans().first { it.id == id }
        if (plan.completedAt == null) return@withTransaction
        val data = dao.dump()
        val event = data.events.first { it.id == plan.eventId }
        val now = System.currentTimeMillis()
        dao.save(plan.copy(completedAt = null, savedCostCents = data.cost(event), updatedAt = now))
        dao.save(event.copy(deletedAt = now, updatedAt = now))
        dao.deleteEventCash(event.id)
        invalidate(event.startAt, event.endAt)
    }

    suspend fun deletePlan(id: String) = db.withTransaction {
        val plan = dao.plans().first { it.id == id }
        require(plan.completedAt == null) { "请先撤销完成" }
        dao.save(plan.copy(deletedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun saveEvent(input: Event, cost: Long?, forecastInput: Forecast?, photos: List<Photo>? = null) = db.withTransaction {
        val now = System.currentTimeMillis()
        val data = dao.dump()
        val old = data.events.find { it.id == input.id }
        val event = input.copy(costKnown = cost != null && input.status == "done", createdAt = old?.createdAt ?: now,
            updatedAt = now, firstOutcomeAt = old?.firstOutcomeAt ?: if (input.status != "planned") now else null)
        event.validate()
        require(cost == null || cost in 0..1000000000000) { "事件支出超出支持范围" }
        require(event.status != "done" || event.startAt <= now) { "未来事件请先保存为计划" }
        require(event.endAt == null || event.endAt <= now + 60000) { "实际结束时间不能在未来" }
        dao.save(event)
        if(photos!=null)replacePhotos("event",event.id,photos)
        dao.deleteEventCash(event.id)
        if (event.costKnown) dao.save(CashEntry(id = "event-${event.id}", eventId = event.id, title = event.title, cents = -cost!!,
            kind = "expense", occurredAt = event.startAt, createdAt = old?.createdAt ?: now, updatedAt = now))
        if (forecastInput != null) {
            val last = data.latestForecast(event.id)
            if (last == null || !last.sameInputs(forecastInput)) {
                val f = forecastInput.copy(id = newId(), eventId = event.id, madeAt = now,
                    prospective = event.status == "planned" && event.startAt > now && event.firstOutcomeAt == null)
                f.validate(); dao.save(f)
            }
        }
        invalidate(event.startAt, event.endAt)
        if (old != null) invalidate(old.startAt, old.endAt)
    }

    suspend fun deleteEvent(event: Event) = db.withTransaction {
        val plan = dao.plans().find { it.eventId == event.id && it.completedAt != null }
        if (plan != null) { undoPlan(plan.id); return@withTransaction }
        dao.save(event.copy(deletedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
        dao.deleteEventCash(event.id)
        invalidate(event.startAt, event.endAt)
    }
    private suspend fun invalidate(start: Long, end: Long?) {
        var day = Time.day(start)
        val last = Time.day(end ?: start)
        while (day <= last) { dao.uncheck(day.toString()); day = day.plusDays(1) }
    }
    suspend fun saveSleep(sleep: SleepEntry) = db.withTransaction {
        sleep.validate()
        require(LocalDate.parse(sleep.day) <= LocalDate.now()) { "不能提前确认未来的睡眠" }
        require(sleep.endAt == null || sleep.endAt <= System.currentTimeMillis()) { "起床时间不能在未来" }
        require(sleep.endAt == null || Time.day(sleep.endAt).toString() == sleep.day) { "起床日期应与所确认的日期一致" }
        val old = dao.sleeps().find { it.day == sleep.day }
        old?.startAt?.let { invalidate(it, old.endAt) }
        sleep.startAt?.let { invalidate(it, sleep.endAt) }
        dao.save(sleep); dao.uncheck(sleep.day)
    }
    suspend fun saveCash(cash: CashEntry, photos: List<Photo>? = null) = db.withTransaction {
        cash.validate()
        require(cash.eventId == null) { "事件费用请从事件中修改" }
        require(cash.occurredAt <= System.currentTimeMillis()) { "流水只能记录实际已发生的收支" }
        dao.cash().find { it.id == cash.id }?.let { dao.uncheck(Time.day(it.occurredAt).toString()) }
        dao.save(cash); dao.uncheck(Time.day(cash.occurredAt).toString())
        if(photos!=null)replacePhotos("cash",cash.id,photos)
    }
    suspend fun deleteCash(cash: CashEntry) = db.withTransaction {
        require(cash.eventId == null) { "事件费用请从事件中修改" }
        dao.deleteCash(cash.id); dao.uncheck(Time.day(cash.occurredAt).toString())
        dao.deletePhotos("cash",cash.id)
    }
    suspend fun saveBalance(anchor: BalanceAnchor) {
        require(anchor.asOf <= System.currentTimeMillis()) { "余额起点不能在未来" }
        require(kotlin.math.abs(anchor.cents.toDouble()) <= 1e12) { "余额超出范围" }
        dao.save(anchor)
    }
    suspend fun closeDay(day: LocalDate) {
        require(day <= LocalDate.now()) { "不能提前确认未来日期" }
        require(dao.sleeps().any { it.day == day.toString() }) { "请先确认当天的睡眠情况" }
        dao.save(DayCheck(day.toString()))
    }
    suspend fun resolve(f: Forecast, happened: Boolean) {
        require(f.probability != null) { "这不是概率预测" }
        require(happened || System.currentTimeMillis() >= f.deadlineAt!!) { "截止前请保留未定结果，不能提前判为未发生" }
        dao.save(Resolution(f.id, happened))
    }
    suspend fun restore(data: LedgerData) = db.withTransaction {
        Export.validate(data)
        dao.clearPhotos()
        dao.clearPlans(); dao.clearChecks(); dao.clearResolutions(); dao.clearForecasts(); dao.clearCash(); dao.clearSleep(); dao.clearBalances(); dao.clearEvents()
        data.events.forEach { dao.save(it) }; data.cash.forEach { dao.save(it) }; data.sleeps.forEach { dao.save(it) }
        data.forecasts.forEach { dao.save(it) }; data.resolutions.forEach { dao.save(it) }; data.balances.forEach { dao.save(it) }; data.checks.forEach { dao.save(it) }
        data.plans.forEach { dao.save(it) }
        data.photos.forEach { dao.save(it) }
    }
    private suspend fun replacePhotos(type:String,id:String,photos:List<Photo>) {
        val selected=photos.map{it.copy(ownerType=type,ownerId=id)}
        require(selected.size<=PhotoPolicy.MAX_COUNT && selected.map{it.id}.distinct().size==selected.size) { "每条记录最多 12 张图片，不能重复" }
        selected.forEach{it.validate()}
        val existing=dao.photos()
        require(selected.none{p->existing.any{it.id==p.id && (it.ownerType!=type||it.ownerId!=id)}}) { "图片编号已属于其他记录" }
        dao.deletePhotos(type,id);selected.forEach{dao.save(it)}
    }
}
