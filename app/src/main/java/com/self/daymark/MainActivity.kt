package com.self.daymark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { DaymarkTheme { DaymarkApp() } } }
}

@Composable fun DaymarkApp(vm: DaymarkViewModel = viewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val restoring by vm.pendingRestore.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedDay by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var newEvent by rememberSaveable { mutableStateOf(false) }
    var planId by rememberSaveable { mutableStateOf<String?>(null) }
    var planOpen by rememberSaveable { mutableStateOf(false) }
    var planDay by rememberSaveable { mutableStateOf<String?>(LocalDate.now().toString()) }
    var completedId by rememberSaveable { mutableStateOf<String?>(null) }
    var sleepOpen by rememberSaveable { mutableStateOf(false) }
    var cashOpen by rememberSaveable { mutableStateOf(false) }
    var cashId by rememberSaveable { mutableStateOf<String?>(null) }
    var balanceOpen by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    CompletionFeedback(completedId,snack,{editId=it},{completedId=null})
    LaunchedEffect(vm) { vm.messages.collect { snack.showSnackbar(it) } }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let { uri -> vm.export(uri) } }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::readRestore) }
    val recoveryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { it?.let { uri -> vm.export(uri,true) } }
    val day=LocalDate.parse(selectedDay)
    fun edit(event: Event) { editId=event.id; newEvent=false }
    Scaffold(containerColor=CanvasColor,snackbarHost={SnackbarHost(snack)},bottomBar={
        DaymarkNavigation(tab,{tab=it},{if(tab==1){planId=null;planDay=selectedDay;planOpen=true}else{newEvent=true;editId=null}})
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when(tab) {
                0 -> TodayScreen(data,day,{selectedDay=it.toString()},::edit,{sleepOpen=true},{cashId=null;cashOpen=true},{vm.closeDay(day)}, { cash -> if(cash.eventId!=null) data.activeEvents.find { it.id==cash.eventId }?.let(::edit) else {cashId=cash.id;cashOpen=true} })
                1 -> PlansScreen(data,day,{selectedDay=it.toString();planDay=it.toString()},
                    {p->planId=p.id;planDay=p.day;planOpen=true},
                    {p->vm.completePlan(p){completedId=it}},vm::undoPlan,
                    {p->data.events.find{it.id==p.eventId}?.let(::edit)},
                    {d->planDay=d?.toString();planId=null;planOpen=true})
                2 -> CalendarScreen(data,day,{selectedDay=it.toString();tab=0})
                3 -> TrendsScreen(data,{balanceOpen=true})
                4 -> DataScreen(data,{exportLauncher.launch("EventLog-${LocalDate.now()}.zip")},{restoreLauncher.launch(arrayOf("application/zip","application/json","application/octet-stream"))},{balanceOpen=true},{cashId=null;cashOpen=true},vm.hasRecoveryBackup(),{recoveryLauncher.launch(vm.recoveryFileName())})
            }
        }
    }
    if(newEvent||editId!=null) {
        val source=data.events.find { it.id==editId }
        EventEditor(source,source?.let { data.cost(it) },source?.let { data.latestForecast(it.id) },day,
            onDismiss={newEvent=false;editId=null},onSave={e,c,f,p->vm.saveEvent(e,c,f,p){newEvent=false;editId=null}},onDelete={e->vm.deleteEvent(e){editId=null}},sourcePhotos=data.photosFor("event",editId))
    }
    if(planOpen) PlanEditor(data.plans.find{it.id==planId},planDay?.let(LocalDate::parse),{planOpen=false},
        {p->vm.savePlan(p){planOpen=false}},{p->vm.deletePlan(p){planOpen=false}})
    if(sleepOpen) SleepEditor(day,data.sleeps.find { it.day==selectedDay },{sleepOpen=false},{vm.saveSleep(it){sleepOpen=false}})
    if(cashOpen) CashEditor(data.cash.find { it.id==cashId },day,{cashOpen=false},{c,p->vm.saveCash(c,p){cashOpen=false}},{vm.deleteCash(it){cashOpen=false}},data.photosFor("cash",cashId))
    if(balanceOpen) BalanceEditor(data.balances.isEmpty(),{balanceOpen=false},{vm.saveBalance(it){balanceOpen=false}})
    if(restoring!=null) ConfirmDialog("恢复备份？","备份含 ${restoring!!.activeEvents.size} 个事件、${restoring!!.cash.size} 笔流水、${restoring!!.activePlans.size} 个计划。将替换当前数据；恢复前会自动保留一份本地备份，可在数据页导出。",{vm.cancelRestore()},{vm.restore()})
}

@Composable fun CompletionFeedback(id:String?,snack:SnackbarHostState,onEdit:(String)->Unit,onDismiss:()->Unit) {
    LaunchedEffect(id) {
        if(id!=null) {
            if(snack.showSnackbar("✓ 又完成了一件事",actionLabel="补充记录",withDismissAction=true,duration=SnackbarDuration.Short)==SnackbarResult.ActionPerformed)onEdit(id)
            onDismiss()
        }
    }
}
