package com.self.daymark

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.io.*
import java.time.*
import java.util.zip.*

data class Backup(val schemaVersion: Int = 4, val app: String = "Daymark", val exportedAt: String = Instant.now().toString(), val zone: String = ZoneId.systemDefault().id, val data: LedgerData)

object Export {
    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create()
    fun json(data: LedgerData) = gson.toJson(Backup(data = data))
    fun fromJson(text: String): LedgerData {
        val tree = JsonParser.parseString(text).asJsonObject
        val version = tree.get("schemaVersion").asInt
        require(version in 1..4 && tree.get("app").asString == "Daymark") { "不是支持的事记备份（版本 1–4）" }
        if (version < 4) tree.getAsJsonObject("data").add("photos", JsonArray())
        if (version < 3) tree.getAsJsonObject("data").add("plans", JsonArray())
        val backup = gson.fromJson(tree, Backup::class.java)
        var data = if (backup.schemaVersion == 1) ExperienceScale.upgradeLegacy(backup.data) else backup.data
        if (version < 3) data = data.copy(plans = data.activeEvents.filter { it.status == "planned" }.map { e ->
            val local = Instant.ofEpochMilli(e.startAt).atZone(ZoneId.systemDefault())
            Plan(id = "legacy-${e.id}", title = e.title, notes = e.notes, day = local.toLocalDate().toString(),
                startMinute = local.hour * 60 + local.minute, expectedMinutes = data.latestForecast(e.id)?.minutes,
                createdAt = e.createdAt, updatedAt = e.updatedAt)
        })
        validate(data)
        return data
    }
    fun validate(data: LedgerData) {
        require(data.events.size + data.cash.size + data.forecasts.size + data.plans.size <= 100000) { "单次恢复记录过多" }
        uniquePlans(data)
        PhotoPolicy.validateLinks(data)
        fun unique(ids: List<String>) = require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) { "备份包含重复或缺失的编号" }
        unique(data.events.map { it.id }); unique(data.cash.map { it.id }); unique(data.sleeps.map { it.day }); unique(data.forecasts.map { it.id })
        unique(data.resolutions.map { it.forecastId }); unique(data.balances.map { it.id }); unique(data.checks.map { it.day })
        data.events.forEach { it.validate() }; data.cash.forEach { it.validate() }; data.sleeps.forEach { it.validate() }; data.forecasts.forEach { it.validate() }
        val ids = data.events.map { it.id }.toSet(); val fids = data.forecasts.map { it.id }.toSet()
        require(data.cash.all { it.eventId == null || it.eventId in ids } && data.forecasts.all { it.eventId in ids } && data.resolutions.all { it.forecastId in fids }) { "备份中的关联记录不完整" }
        require(data.cash.filter { it.eventId != null }.groupBy { it.eventId }.all { it.value.size == 1 }) { "事件费用记录重复" }
        data.activeEvents.forEach { e -> require(e.costKnown == data.cash.any { it.eventId == e.id }) { "事件费用状态与流水不一致" } }
        data.balances.forEach { require(kotlin.math.abs(it.cents.toDouble()) <= 1e12) { "余额超出范围" } }
        data.checks.forEach { LocalDate.parse(it.day); require(data.sleeps.any { s -> s.day == it.day }) { "已确认日期缺少睡眠确认" } }
    }
    private fun uniquePlans(data: LedgerData) {
        require(data.plans.map { it.id }.distinct().size == data.plans.size) { "计划编号重复" }
        val links = data.plans.mapNotNull { it.eventId }
        require(links.distinct().size == links.size) { "计划关联记录重复" }
        data.plans.forEach { p ->
            p.validate()
            if (p.eventId != null) {
                val event = data.events.find { it.id == p.eventId }
                require(event != null && event.status == "done") { "计划的实际记录缺失" }
                require((p.completedAt != null) == (event.deletedAt == null)) { "计划完成状态不一致" }
                require(p.completedAt == null || p.savedCostCents == null) { "计划费用状态不一致" }
            }
        }
    }
    fun csv(headers: List<String>, rows: List<List<Any?>>): String {
        fun cell(value: Any?): String {
            if (value == null) return ""
            if (value is Number || value is Boolean) return value.toString()
            var text = value.toString()
            if (text.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || text.startsWith('\t') || text.startsWith('\r')) text = "'" + text
            return "\"" + text.replace("\"", "\"\"") + "\""
        }
        return "\uFEFF" + (listOf(headers) + rows).joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",") { cell(it) } }
    }
    fun writeZip(output: OutputStream, data: LedgerData, photoInput: ((Photo)->InputStream)? = null) {
        validate(data)
        require(data.photos.isEmpty() || photoInput != null) { "完整备份必须包含图片文件" }
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            var total=0L
            fun put(name: String, text: String) {
                val bytes=text.toByteArray(Charsets.UTF_8);total+=bytes.size
                require(bytes.size<=20_000_000 && total<=1024L*1024*1024){"备份过大，尚未完整导出"}
                zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()
            }
            put("records.json", json(data))
            put("photos.csv",csv(listOf("id","owner_type","owner_id","file","mime_type","bytes","sha256","created_at"),data.photos.map{listOf(it.id,it.ownerType,it.ownerId,"images/${it.fileName}",it.mimeType,it.bytes,it.sha256,Time.iso(it.createdAt))}))
            data.photos.distinctBy{it.fileName}.forEach{p->
                total+=p.bytes;require(total<=1024L*1024*1024){"备份超过 1 GB，尚未完整导出"}
                zip.putNextEntry(ZipEntry("images/${p.fileName}"))
                photoInput!!(p).use{input->
                    val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192);var size=0L
                    while(true){val n=input.read(buffer);if(n<0)break;size+=n;require(size<=p.bytes){"图片大小与记录不符"};digest.update(buffer,0,n);zip.write(buffer,0,n)}
                    require(size==p.bytes && digest.digest().joinToString(""){"%02x".format(it)}==p.sha256){"图片文件已损坏，导出未完成"}
                };zip.closeEntry()
            }
            put("plans.csv", csv(listOf("id","title","notes","day","period","start_minute","expected_minutes","event_id","completed_at","saved_cost_cents","created_at","updated_at","deleted_at"), data.plans.map { listOf(it.id,it.title,it.notes,it.day,it.period,it.startMinute,it.expectedMinutes,it.eventId,it.completedAt?.let(Time::iso),it.savedCostCents,Time.iso(it.createdAt),Time.iso(it.updatedAt),it.deletedAt?.let(Time::iso)) }))
            put("plan_durations.csv", csv(listOf("plan_id","event_id","expected_minutes","actual_minutes","actual_minus_expected_minutes"), data.activePlans.filter { it.completedAt != null }.map { p -> val actual=data.events.find { it.id == p.eventId }?.durationMinutes; listOf(p.id,p.eventId,p.expectedMinutes,actual,if(actual!=null&&p.expectedMinutes!=null)actual-p.expectedMinutes else null) }))
            put("events.csv", csv(listOf("id","title","category","start_at","end_at","status","description","experience","feeling","worth","cost_known","actual_cost_cents","created_at","updated_at","first_outcome_at","deleted_at","actual_minutes","duration_minutes"), data.events.map { e -> listOf(e.id,e.title,e.category,Time.iso(e.startAt),e.endAt?.let(Time::iso),e.status,e.description,e.experience,e.feeling,e.worth,e.costKnown,data.cost(e),Time.iso(e.createdAt),Time.iso(e.updatedAt),e.firstOutcomeAt?.let(Time::iso),e.deletedAt?.let(Time::iso),e.actualMinutes,e.durationMinutes) }))
            put("cash.csv", csv(listOf("id","event_id","title","signed_cents","kind","occurred_at","created_at","updated_at"), data.cash.map { listOf(it.id,it.eventId,it.title,it.cents,it.kind,Time.iso(it.occurredAt),Time.iso(it.createdAt),Time.iso(it.updatedAt)) }))
            put("sleep.csv", csv(listOf("wake_day","status","start_at","end_at","note","updated_at"), data.sleeps.map { listOf(it.day,it.status,it.startAt?.let(Time::iso),it.endAt?.let(Time::iso),it.note,Time.iso(it.updatedAt)) }))
            put("forecasts.csv", csv(listOf("id","event_id","made_at","expected_minutes","expected_cost_cents","expected_feeling","question","probability_percent","deadline_at","basis","prospective"), data.forecasts.map { listOf(it.id,it.eventId,Time.iso(it.madeAt),it.minutes,it.costCents,it.expectedFeeling,it.question,it.probability,it.deadlineAt?.let(Time::iso),it.basis,it.prospective) }))
            put("resolutions.csv", csv(listOf("forecast_id","happened","resolved_at"), data.resolutions.map { listOf(it.forecastId,it.happened,Time.iso(it.resolvedAt)) }))
            put("balance_anchors.csv", csv(listOf("id","as_of","balance_cents","note","created_at"), data.balances.map { listOf(it.id,Time.iso(it.asOf),it.cents,it.note,Time.iso(it.createdAt)) }))
            put("day_checks.csv", csv(listOf("day","checked_at"), data.checks.map { listOf(it.day,Time.iso(it.checkedAt)) }))
            val errors = Analytics.errors(data)
            put("prediction_errors.csv", csv(listOf("event_id","forecast_id","category","actual_minus_expected_minutes","actual_minus_expected_cents","actual_minus_expected_feeling"), errors.map { listOf(it.event.id,it.forecast.id,it.event.category,it.minutesError,it.costError,it.feelingError) }))
            val dates = Analytics.recordedDates(data)
            put("daily_summary.csv", csv(listOf("date","has_recorded_data","expense_cents","income_cents","adjustment_cents","activity_minutes","sleep_minutes_union","unknown_durations","unknown_event_costs","sleep_confirmed","balance_cents"), dates.map { d ->
                val s=Analytics.daily(data,d); listOf(d,s.anyData,s.expense,s.income,s.adjustment,s.activityMinutes,s.sleepMinutes,s.unknownDurations,s.unknownCosts,s.sleepConfirmed,Analytics.balanceAt(data,Time.start(d.plusDays(1))-1))
            }))
            put("README.txt", "事记 EventLog 导出格式 4\n金额为人民币分，时长为分钟。日期时间为带时区偏移的 ISO 8601。空白/null=未知；金额和时长的0=明确零。feeling、worth、expected_feeling评分：-2非常不值，-1不值，0中性，1值得，2非常值得；null为未评分。旧版格式1的1–5分导入时减3转换，格式2不再转换。events 为事件，cash 是唯一资金流水来源，不能将事件费用重复相加。睡眠以起床日确认，日汇总按自然日裁切；旧活动区间取并集；新记录的 actual_minutes 按 start_at 所属日期相加，不能推断重叠。plans 为计划；预计耗时不参与实际统计，plan_durations 对比预计与实际耗时。日汇总仅列有记录的日期。资金余额从最新余额起点（该时刻之前的余额）加上之后流水估算，不代表完整净资产。预测保留全部版本；误差按每个事件首份有效事前估计计算，正值代表低估。CSV 防止文本公式执行，原文见 records.json。删除事件保留事件与预测历史，但其关联流水会删除。photos.csv 关联图片与记录，原图位于 images/；包含图片时必须用完整 ZIP 恢复，只有 JSON 不含图片本体。恢复前会逐张校验大小与 SHA-256。无图片的旧 JSON 仍可恢复。\n")
        }
    }
    fun readBackup(input: InputStream, photoFolder: File? = null): LedgerData {
        val stream = BufferedInputStream(input)
        stream.mark(4); val a = stream.read(); val b = stream.read(); stream.reset()
        fun readLimited(source: InputStream): String {
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = source.read(buffer); if (n < 0) break; require(out.size() + n <= 20_000_000) { "备份内容超过 20 MB" }; out.write(buffer,0,n) }
            return out.toString("UTF-8").removePrefix("\uFEFF")
        }
        if (a == 80 && b == 75) {
            ZipInputStream(stream).use { zip ->
                val seen=mutableSetOf<String>();val images=mutableSetOf<String>()
                var data:LedgerData?=null;var total=0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(seen.add(entry.name) && seen.size <= 100030) { "备份文件结构异常或包含重复文件" }
                    if(entry.isDirectory)continue
                    if(entry.name=="records.json") {
                        val json=readLimited(zip);total+=json.toByteArray(Charsets.UTF_8).size;data=fromJson(json)
                    } else {
                        val isImage=entry.name.startsWith("images/")
                        val file=if(isImage){
                            require(entry.name.matches(Regex("images/[a-f0-9]{64}\\.(jpg|png|webp|gif|heic|bmp)"))){"图片路径无效"}
                            require(photoFolder!=null){"带图片备份需要完整恢复"}
                            photoFolder.mkdirs();val name=entry.name.removePrefix("images/");images.add(name);File(photoFolder,name)
                        }else null
                        val output=file?.outputStream()
                        try{
                            var size=0L;val buffer=ByteArray(8192)
                            while(true){val n=zip.read(buffer);if(n<0)break;size+=n;total+=n
                                require(size<=PhotoPolicy.MAX_BYTES && total<=1024L*1024*1024){"备份过大：单个文件上限 20 MB，整包上限 1 GB"}
                                output?.write(buffer,0,n)
                            }
                        }finally{output?.close()}
                    }
                }
                val result=data ?: error("ZIP 中没有 records.json")
                require(total<=1024L*1024*1024){"备份超过 1 GB"}
                require(images==result.photos.map{it.fileName}.toSet()){"备份中的图片不完整或不匹配"}
                result.photos.distinctBy{it.fileName}.forEach{PhotoPolicy.validateFile(it,photoFolder!!)}
                return result
            }
        }
        return stream.use { fromJson(readLimited(it)).also{data->require(data.photos.isEmpty()){ "此 JSON 不含图片文件，请使用完整 ZIP 恢复" }} }
    }
}
