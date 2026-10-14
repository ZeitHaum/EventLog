package com.self.daymark

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.*
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
val categories = listOf("工作", "学习", "生活", "社交", "运动", "休息", "其他")

@Entity(tableName = "events", indices = [Index("startAt")])
data class Event(
    @PrimaryKey val id: String = newId(),
    val title: String,
    val category: String = "生活",
    val startAt: Long,
    val endAt: Long? = null,
    val status: String = "done",
    val description: String = "",
    val experience: String = "",
    val feeling: Int? = null,
    val worth: Int? = null,
    val costKnown: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val firstOutcomeAt: Long? = null,
    val deletedAt: Long? = null
) {
    fun validate() {
        require(id.isNotBlank() && title.isNotBlank() && title.length <= 200) { "请填写事件名称（不超过 200 字）" }
        require(category in categories && status in listOf("planned", "done", "cancelled")) { "事件类型无效" }
        require(endAt == null || endAt >= startAt) { "结束时间不能早于开始时间" }
        require(endAt == null || endAt - startAt <= 31L * 86400000) { "单次事件请控制在 31 天内" }
        require(feeling == null || feeling in ExperienceScale.values) { "体验评分应为 −2 到 2" }
        require(worth == null || worth in ExperienceScale.values) { "回顾评分应为 −2 到 2" }
        require(status == "done" || (endAt == null && !costKnown)) { "计划或取消的事件不能填入实际时间、费用" }
    }
}

@Entity(tableName = "cash", indices = [Index("eventId"), Index("occurredAt")])
data class CashEntry(
    @PrimaryKey val id: String = newId(),
    val eventId: String? = null,
    val title: String,
    val cents: Long,
    val kind: String,
    val occurredAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
) {
    fun validate() {
        require(id.isNotBlank() && title.isNotBlank()) { "请填写收支名称" }
        require(kind in listOf("expense", "income", "adjustment")) { "收支类型无效" }
        require(kotlin.math.abs(cents.toDouble()) <= 1e12) { "金额超出支持范围" }
        require(kind != "expense" || cents <= 0) { "支出金额方向错误" }
        require(kind != "income" || cents >= 0) { "收入金额方向错误" }
        require(eventId == null || kind == "expense") { "关联事件的流水应为支出" }
    }
}

@Entity(tableName = "sleep")
data class SleepEntry(
    @PrimaryKey val day: String,
    val status: String,
    val startAt: Long? = null,
    val endAt: Long? = null,
    val note: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun validate() {
        LocalDate.parse(day)
        require(status in listOf("timed", "none", "unknown")) { "睡眠状态无效" }
        if (status == "timed") {
            require(startAt != null && endAt != null && endAt > startAt) { "请填写有效的入睡和起床时间" }
            require(endAt - startAt <= 48L * 3600000) { "睡眠区间不能超过 48 小时" }
        } else require(startAt == null && endAt == null) { "未记时间的睡眠确认不应包含区间" }
    }
}

@Entity(tableName = "forecasts", indices = [Index("eventId")])
data class Forecast(
    @PrimaryKey val id: String = newId(),
    val eventId: String,
    val madeAt: Long = System.currentTimeMillis(),
    val minutes: Long? = null,
    val costCents: Long? = null,
    val expectedFeeling: Int? = null,
    val question: String = "",
    val probability: Int? = null,
    val deadlineAt: Long? = null,
    val basis: String = "",
    val prospective: Boolean = false
) {
    fun validate() {
        require(id.isNotBlank() && eventId.isNotBlank()) { "预测关联无效" }
        require(minutes == null || minutes in 0..44640) { "预计时长须为 0–44640 分钟" }
        require(costCents == null || costCents in 0..1000000000000) { "预计金额无效" }
        require(expectedFeeling == null || expectedFeeling in ExperienceScale.values) { "预计体验应为 −2 到 2" }
        require(probability == null || (probability in 0..100 && question.isNotBlank() && deadlineAt != null)) { "概率预测需要概率、判定标准和截止时间" }
        require(!prospective || probability == null || deadlineAt!! > madeAt) { "事前预测的截止时间必须在未来" }
    }
    fun sameInputs(other: Forecast) = minutes == other.minutes && costCents == other.costCents &&
        expectedFeeling == other.expectedFeeling && question == other.question && probability == other.probability &&
        deadlineAt == other.deadlineAt && basis == other.basis
}

@Entity(tableName = "resolutions")
data class Resolution(@PrimaryKey val forecastId: String, val happened: Boolean, val resolvedAt: Long = System.currentTimeMillis())

@Entity(tableName = "balances")
data class BalanceAnchor(@PrimaryKey val id: String = newId(), val asOf: Long, val cents: Long, val note: String = "", val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "day_checks")
data class DayCheck(@PrimaryKey val day: String, val checkedAt: Long = System.currentTimeMillis())

data class LedgerData(
    val events: List<Event> = emptyList(), val cash: List<CashEntry> = emptyList(),
    val sleeps: List<SleepEntry> = emptyList(), val forecasts: List<Forecast> = emptyList(),
    val resolutions: List<Resolution> = emptyList(), val balances: List<BalanceAnchor> = emptyList(),
    val checks: List<DayCheck> = emptyList()
) {
    val activeEvents get() = events.filter { it.deletedAt == null }
    fun latestForecast(id: String) = forecasts.filter { it.eventId == id }.maxByOrNull { it.madeAt }
    fun cost(event: Event): Long? = if (event.costKnown && event.deletedAt == null) -cash.filter { it.eventId == event.id }.sumOf { it.cents } else null
}

@Dao
interface LedgerDao {
    @Query("SELECT (SELECT COUNT(*) FROM events) + (SELECT COUNT(*) FROM cash) + (SELECT COUNT(*) FROM sleep) + (SELECT COUNT(*) FROM forecasts) + (SELECT COUNT(*) FROM resolutions) + (SELECT COUNT(*) FROM balances) + (SELECT COUNT(*) FROM day_checks)")
    fun changes(): Flow<Int>
    @Query("SELECT * FROM events ORDER BY startAt DESC") suspend fun events(): List<Event>
    @Query("SELECT * FROM cash ORDER BY occurredAt DESC") suspend fun cash(): List<CashEntry>
    @Query("SELECT * FROM sleep ORDER BY day DESC") suspend fun sleeps(): List<SleepEntry>
    @Query("SELECT * FROM forecasts ORDER BY madeAt") suspend fun forecasts(): List<Forecast>
    @Query("SELECT * FROM resolutions") suspend fun resolutions(): List<Resolution>
    @Query("SELECT * FROM balances ORDER BY asOf") suspend fun balances(): List<BalanceAnchor>
    @Query("SELECT * FROM day_checks") suspend fun checks(): List<DayCheck>
    @Upsert suspend fun save(value: Event)
    @Upsert suspend fun save(value: CashEntry)
    @Upsert suspend fun save(value: SleepEntry)
    @Insert suspend fun save(value: Forecast)
    @Upsert suspend fun save(value: Resolution)
    @Upsert suspend fun save(value: BalanceAnchor)
    @Upsert suspend fun save(value: DayCheck)
    @Query("DELETE FROM cash WHERE id = :id") suspend fun deleteCash(id: String)
    @Query("DELETE FROM cash WHERE eventId = :id") suspend fun deleteEventCash(id: String)
    @Query("DELETE FROM day_checks WHERE day = :day") suspend fun uncheck(day: String)
    @Query("DELETE FROM events") suspend fun clearEvents()
    @Query("DELETE FROM cash") suspend fun clearCash()
    @Query("DELETE FROM sleep") suspend fun clearSleep()
    @Query("DELETE FROM forecasts") suspend fun clearForecasts()
    @Query("DELETE FROM resolutions") suspend fun clearResolutions()
    @Query("DELETE FROM balances") suspend fun clearBalances()
    @Query("DELETE FROM day_checks") suspend fun clearChecks()
    @Transaction suspend fun dump() = LedgerData(events(), cash(), sleeps(), forecasts(), resolutions(), balances(), checks())
}

@Database(entities = [Event::class, CashEntry::class, SleepEntry::class, Forecast::class, Resolution::class, BalanceAnchor::class, DayCheck::class], version = 2, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Subtracting from SQL NULL preserves unanswered ratings.
                db.execSQL("UPDATE events SET feeling = feeling - 3, worth = worth - 3")
                db.execSQL("UPDATE forecasts SET expectedFeeling = expectedFeeling - 3")
            }
        }
        @Volatile private var instance: LedgerDatabase? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, "daymark.db").addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
