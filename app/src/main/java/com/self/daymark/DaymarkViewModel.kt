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
    private val photoFiles = PhotoFiles(app)
    private var pendingPhotos:File?=null
    private val beforeRestore get() = File(getApplication<Application>().filesDir, "before-restore.zip").let{if(it.exists())it else File(getApplication<Application>().filesDir,"before-restore.json")}
    fun recoveryFileName() = "EventLog-恢复前备份.${beforeRestore.extension}"
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
    fun saveEvent(e: Event, cost: Long?, forecast: Forecast?, photos: List<Photo>? = null, onSaved: () -> Unit) = run(onSuccess=onSaved) { photos?.let{photoFiles.validate(it)}; repository.saveEvent(e,cost,forecast,photos) }
    fun deleteEvent(e: Event, onSaved: () -> Unit) = run("已移除记录及关联支出",onSaved) { repository.deleteEvent(e) }
    fun savePlan(p: Plan, onSaved: () -> Unit) = run(onSuccess=onSaved) { repository.savePlan(p) }
    fun completePlan(p: Plan, onSaved: (String) -> Unit) {
        var eventId = ""
        run("", { onSaved(eventId) }) { eventId = repository.completePlan(p.id) }
    }
    fun undoPlan(p: Plan) = run("已撤销完成，事项回到待办") { repository.undoPlan(p.id) }
    fun deletePlan(p: Plan, onSaved: () -> Unit) = run("已删除计划", onSaved) { repository.deletePlan(p.id) }
    fun saveSleep(s: SleepEntry, onSaved: () -> Unit) = run("睡眠情况已确认",onSaved) { repository.saveSleep(s) }
    fun saveCash(c: CashEntry, photos: List<Photo>? = null, onSaved: () -> Unit) = run(onSuccess=onSaved) { photos?.let{photoFiles.validate(it)}; repository.saveCash(c,photos) }
    fun deleteCash(c: CashEntry, onSaved: () -> Unit) = run("已删除流水",onSaved) { repository.deleteCash(c) }
    fun saveBalance(b: BalanceAnchor, onSaved: () -> Unit) = run("资金余额起点已保存",onSaved) { repository.saveBalance(b) }
    fun closeDay(day: java.time.LocalDate) = run("当天记录已确认") { repository.closeDay(day) }
    fun resolve(f: Forecast, result: Boolean) = run("预测结果已记录") { repository.resolve(f,result) }
    fun export(uri: Uri, recovery: Boolean = false) = run("导出完成") {
        getApplication<Application>().contentResolver.openOutputStream(uri,"wt")?.use { output ->
            if (recovery) beforeRestore.inputStream().use { it.copyTo(output) }
            else Export.writeZip(output,repository.dao.dump()){p->PhotoPolicy.validateFile(p,photoFiles.folder).inputStream()}
        } ?: error("无法写入所选文件")
    }
    fun readRestore(uri: Uri) = run("备份已检查，请确认恢复") {
        val staging=File(getApplication<Application>().cacheDir,"restore-${newId()}").apply{mkdirs()}
        try {
            val incoming = getApplication<Application>().contentResolver.openInputStream(uri)?.use { Export.readBackup(it,staging) } ?: error("无法读取备份")
            incoming.photos.distinctBy{it.fileName}.forEach{PhotoFiles.checkImage(File(staging,it.fileName))}
            pendingPhotos?.deleteRecursively();pendingPhotos=staging;pendingRestore.value=incoming
        }catch(e:Exception){staging.deleteRecursively();throw e}
    }
    fun cancelRestore() {pendingRestore.value=null;val staging=pendingPhotos;pendingPhotos=null;viewModelScope.launch(Dispatchers.IO){staging?.deleteRecursively()}}
    fun restore() {
        val incoming = pendingRestore.value ?: return
        run("恢复完成；恢复前的数据已另存本地备份") {
            val current = repository.dao.dump()
            val destination=File(getApplication<Application>().filesDir,"before-restore.zip")
            val temporary = File(destination.parentFile,"before-restore.tmp")
            temporary.outputStream().use{Export.writeZip(it,current){p->PhotoPolicy.validateFile(p,photoFiles.folder).inputStream()}}
            require(temporary.renameTo(destination)) { "恢复前备份保存失败，尚未修改数据" }
            photoFiles.install(incoming,pendingPhotos ?: error("恢复文件已失效，请重新选择备份"))
            repository.restore(incoming)
            pendingRestore.value = null
            pendingPhotos?.deleteRecursively();pendingPhotos=null
        }
    }
}
