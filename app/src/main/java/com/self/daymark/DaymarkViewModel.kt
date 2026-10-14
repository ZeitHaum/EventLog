package com.self.daymark

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

class DaymarkViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = LedgerRepository(LedgerDatabase.get(app))
    val data = repository.dao.changes().map { repository.dao.dump() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LedgerData())
    val busy = MutableStateFlow(false)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val pendingRestore = MutableStateFlow<LedgerData?>(null)
    private val beforeRestore get() = File(getApplication<Application>().filesDir, "before-restore.json")
    fun hasRecoveryBackup() = beforeRestore.exists()
    fun run(message: String = "已保存", onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() }; onSuccess(); if(message.isNotBlank())messages.emit(message) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { messages.emit(e.message ?: "操作失败，请重试") }
            finally { busy.value = false }
        }
    }
    fun saveEvent(e: Event, cost: Long?, forecast: Forecast?, onSaved: () -> Unit) = run(onSuccess=onSaved) { repository.saveEvent(e,cost,forecast) }
    fun deleteEvent(e: Event, onSaved: () -> Unit) = run("已移除记录及关联支出",onSaved) { repository.deleteEvent(e) }
    fun savePlan(p: Plan, onSaved: () -> Unit) = run(onSuccess=onSaved) { repository.savePlan(p) }
    fun completePlan(p: Plan, onSaved: (String) -> Unit) {
        var eventId = ""
        run("", { onSaved(eventId) }) { eventId = repository.completePlan(p.id) }
    }
    fun undoPlan(p: Plan) = run("已撤销完成，事项回到待办") { repository.undoPlan(p.id) }
    fun deletePlan(p: Plan, onSaved: () -> Unit) = run("已删除计划", onSaved) { repository.deletePlan(p.id) }
    fun saveSleep(s: SleepEntry, onSaved: () -> Unit) = run("睡眠情况已确认",onSaved) { repository.saveSleep(s) }
    fun saveCash(c: CashEntry, onSaved: () -> Unit) = run(onSuccess=onSaved) { repository.saveCash(c) }
    fun deleteCash(c: CashEntry, onSaved: () -> Unit) = run("已删除流水",onSaved) { repository.deleteCash(c) }
    fun saveBalance(b: BalanceAnchor, onSaved: () -> Unit) = run("资金余额起点已保存",onSaved) { repository.saveBalance(b) }
    fun closeDay(day: java.time.LocalDate) = run("当天记录已确认") { repository.closeDay(day) }
    fun resolve(f: Forecast, result: Boolean) = run("预测结果已记录") { repository.resolve(f,result) }
    fun export(uri: Uri, recovery: Boolean = false) = run("导出完成") {
        getApplication<Application>().contentResolver.openOutputStream(uri,"wt")?.use { output ->
            if (recovery) beforeRestore.inputStream().use { it.copyTo(output) }
            else Export.writeZip(output,repository.dao.dump())
        } ?: error("无法写入所选文件")
    }
    fun readRestore(uri: Uri) = run("备份已检查，请确认恢复") {
        val incoming = getApplication<Application>().contentResolver.openInputStream(uri)?.use { Export.readBackup(it) } ?: error("无法读取备份")
        pendingRestore.value = incoming
    }
    fun restore() {
        val incoming = pendingRestore.value ?: return
        run("恢复完成；恢复前的数据已另存本地备份") {
            val current = repository.dao.dump()
            val temporary = File(beforeRestore.parentFile,"before-restore.tmp")
            temporary.writeText(Export.json(current),Charsets.UTF_8)
            require(temporary.renameTo(beforeRestore)) { "恢复前备份保存失败，尚未修改数据" }
            repository.restore(incoming)
            pendingRestore.value = null
        }
    }
}
