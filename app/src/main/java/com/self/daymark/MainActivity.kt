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
    var sleepOpen by rememberSaveable { mutableStateOf(false) }
    var cashOpen by rememberSaveable { mutableStateOf(false) }
    var cashId by rememberSaveable { mutableStateOf<String?>(null) }
    var balanceOpen by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snack.showSnackbar(it) } }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let { uri -> vm.export(uri) } }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::readRestore) }
    val recoveryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { uri -> vm.export(uri,true) } }
    val day=LocalDate.parse(selectedDay)
    fun edit(event: Event) { editId=event.id; newEvent=false }
    Scaffold(containerColor=CanvasColor,snackbarHost={SnackbarHost(snack)},bottomBar={
        DaymarkNavigation(tab,{tab=it},{newEvent=true;editId=null})
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when(tab) {
                0 -> TodayScreen(data,day,{selectedDay=it.toString()},::edit,{sleepOpen=true},{cashId=null;cashOpen=true},{vm.closeDay(day)}, { cash -> if(cash.eventId!=null) data.activeEvents.find { it.id==cash.eventId }?.let(::edit) else {cashId=cash.id;cashOpen=true} })
                1 -> CalendarScreen(data,day,{selectedDay=it.toString();tab=0})
                2 -> TrendsScreen(data,{balanceOpen=true})
                3 -> ForecastScreen(data,::edit,vm::resolve)
                4 -> DataScreen(data,{exportLauncher.launch("EventLog-${LocalDate.now()}.zip")},{restoreLauncher.launch(arrayOf("application/zip","application/json","application/octet-stream"))},{balanceOpen=true},{cashId=null;cashOpen=true},vm.hasRecoveryBackup(),{recoveryLauncher.launch("EventLog-恢复前备份.json")})
            }
        }
    }
    if(newEvent||editId!=null) {
        val source=data.events.find { it.id==editId }
        EventEditor(source,source?.let { data.cost(it) },source?.let { data.latestForecast(it.id) },day,
            onDismiss={newEvent=false;editId=null},onSave={e,c,f->vm.saveEvent(e,c,f){newEvent=false;editId=null}},onDelete={e->vm.deleteEvent(e){editId=null}})
    }
    if(sleepOpen) SleepEditor(day,data.sleeps.find { it.day==selectedDay },{sleepOpen=false},{vm.saveSleep(it){sleepOpen=false}})
    if(cashOpen) CashEditor(data.cash.find { it.id==cashId },day,{cashOpen=false},{vm.saveCash(it){cashOpen=false}},{vm.deleteCash(it){cashOpen=false}})
    if(balanceOpen) BalanceEditor(data.balances.isEmpty(),{balanceOpen=false},{vm.saveBalance(it){balanceOpen=false}})
    if(restoring!=null) ConfirmDialog("恢复备份？","备份含 ${restoring!!.activeEvents.size} 个事件、${restoring!!.cash.size} 笔流水。将替换当前数据；恢复前会自动保留一份本地备份，可在数据页导出。",{vm.pendingRestore.value=null},{vm.restore()})
}
