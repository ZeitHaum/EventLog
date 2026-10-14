package com.self.daymark

import android.view.WindowManager
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import java.time.*

@Composable fun EditorFrame(title:String,onDismiss:()->Unit,onSave:()->Unit,content:@Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val view=LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                WindowCompat.getInsetsController(window,view).apply {
                    isAppearanceLightStatusBars=true
                    isAppearanceLightNavigationBars=true
                }
            }
        }
        Surface(Modifier.fillMaxSize(),color=CanvasColor) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onDismiss){Text("取消")}; Title(title); Button(onSave){Text("保存")}
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outline.copy(alpha=.3f))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
            }
        }
    }
}

@Composable fun EventEditor(source:Event?,actualCost:Long?,latest:Forecast?,day:LocalDate,onDismiss:()->Unit,onSave:(Event,Long?,Forecast?)->Unit,onDelete:(Event)->Unit) {
    val key=source?.id ?: "new"
    val id=rememberSaveable(key){source?.id ?: newId()}
    var title by rememberSaveable(key){mutableStateOf(source?.title ?: "")}
    var date by rememberSaveable(key){mutableStateOf(source?.startAt?.let{Time.day(it).toString()} ?: minOf(day,LocalDate.now()).toString())}
    var duration by rememberSaveable(key){mutableStateOf(source?.durationMinutes?.toString() ?: "")}
    var cost by rememberSaveable(key){mutableStateOf(actualCost?.let(::money) ?: "")}
    var notes by rememberSaveable(key){mutableStateOf(source?.notes ?: "")}
    var feeling by rememberSaveable(key){mutableStateOf(source?.feeling)}
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    EditorFrame(if(source==null)"记一件事" else "编辑记录",onDismiss,{
        try {
            val selected=LocalDate.parse(date)
            require(selected<=LocalDate.now()){"请选择已经发生的日期"}
            val minutes=parseMinutes(duration)
            val paid=parseMoney(cost)
            // Preserve original interval/cash date when only editing text or money.
            val keepInterval=source!=null && Time.day(source.startAt)==selected && source.durationMinutes==minutes
            val base=source ?: Event(id=id,title=title.trim(),startAt=Time.recordAt(selected))
            val event=base.copy(title=title.trim(),status="done",description=notes.trim(),experience="",feeling=feeling,
                startAt=if(Time.day(base.startAt)==selected)base.startAt else Time.recordAt(selected),endAt=if(keepInterval)base.endAt else null,
                actualMinutes=if(keepInterval)base.actualMinutes else minutes,costKnown=paid!=null)
            event.validate();onSave(event,paid,null)
        } catch(e:Exception){error=e.message ?: "请检查填写内容"}
    }) {
        error?.let{Text(it,color=Clay)}
        Input("事情名称 *",title,{title=it})
        DateControl(LocalDate.parse(date),{date=it.toString()})
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Input("实际耗时 / 分钟",duration,{duration=it},Modifier.weight(1f),true)
            Input("支出 / 元",cost,{cost=it},Modifier.weight(1f),true)
        }
        Input("做了什么，有什么体验？（选填）",notes,{notes=it},lines=4)
        Rating("当时体验",feeling,{feeling=it})
        if(source!=null)TextButton({deleting=true}){Text("删除记录",color=Clay)}
    }
    if(deleting&&source!=null)ConfirmDialog("删除记录？","关联支出也会移除。如果来自计划，该计划会回到待办。",{deleting=false},{onDelete(source)})
}

@Composable fun PlanEditor(source:Plan?,day:LocalDate?,onDismiss:()->Unit,onSave:(Plan)->Unit,onDelete:(Plan)->Unit) {
    val key=source?.id ?: "new-plan"
    val id=rememberSaveable(key){source?.id ?: newId()}
    var title by rememberSaveable(key){mutableStateOf(source?.title ?: "")}
    var notes by rememberSaveable(key){mutableStateOf(source?.notes ?: "")}
    var date by rememberSaveable(key){mutableStateOf(source?.day ?: day?.toString())}
    var period by rememberSaveable(key){mutableStateOf(source?.period ?: "any")}
    var clock by rememberSaveable(key){mutableStateOf(source?.startMinute?.let{"%02d:%02d".format(it/60,it%60)})}
    var minutes by rememberSaveable(key){mutableStateOf(source?.expectedMinutes?.toString() ?: "")}
    var error by remember{mutableStateOf<String?>(null)}
    var deleting by remember{mutableStateOf(false)}
    EditorFrame(if(source==null)"新建计划" else "编辑计划",onDismiss,{
        try {
            val time=clock?.let(LocalTime::parse)
            val p=(source ?: Plan(id=id,title=title)).copy(title=title.trim(),notes=notes.trim(),day=date,
                period=if(date==null)"any" else period,startMinute=if(date==null)null else time?.let{it.hour*60+it.minute},expectedMinutes=parseMinutes(minutes))
            p.validate();onSave(p)
        }catch(e:Exception){error=e.message}
    }) {
        error?.let{Text(it,color=Clay)}
        Input("事项名称 *",title,{title=it})
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(date!=null)DateControl(LocalDate.parse(date),{date=it.toString()},Modifier.weight(1f))
            else TextButton({date=LocalDate.now().toString()}){Text("＋ 安排日期")}
            if(date!=null)TextButton({date=null;clock=null;period="any"}){Text("待安排")}
        }
        if(date!=null) {
            Choices(listOf("不限时段","上午","下午","晚上"),periodLabel(period)){period=periodValue(it);clock=null}
            Row(verticalAlignment=Alignment.CenterVertically) {
                if(clock==null)TextButton({clock="09:00"}){Text("＋ 固定开始时间")}
                else { ClockControl(clock!!,{clock=it},label="开始");TextButton({clock=null}){Text("移除时间")} }
            }
        }
        Input("预计耗时 / 分钟（选填）",minutes,{minutes=it},numeric=true)
        Input("备注（选填）",notes,{notes=it},lines=3)
        if(source!=null)TextButton({deleting=true}){Text("删除计划",color=Clay)}
    }
    if(deleting&&source!=null)ConfirmDialog("删除计划？","该事项会从待办中移除。",{deleting=false},{onDelete(source)})
}

@Composable fun SleepEditor(day:LocalDate,source:SleepEntry?,onDismiss:()->Unit,onSave:(SleepEntry)->Unit) {
    var status by rememberSaveable{mutableStateOf(source?.status ?: "timed")}
    var startDay by rememberSaveable{mutableStateOf(source?.startAt?.let{Time.day(it).toString()} ?: day.minusDays(1).toString())}
    var startClock by rememberSaveable{mutableStateOf(source?.startAt?.let(Time::clock) ?: "23:00")}
    var endClock by rememberSaveable{mutableStateOf(source?.endAt?.let(Time::clock) ?: "07:00")}
    var note by rememberSaveable{mutableStateOf(source?.note ?: "")}
    var error by remember{mutableStateOf<String?>(null)}
    EditorFrame("确认睡眠",onDismiss,{
        try {
            val s=SleepEntry(day.toString(),status,if(status=="timed")Time.parse(startDay,startClock) else null,if(status=="timed")Time.parse(day.toString(),endClock) else null,note)
            s.validate();require(day<=LocalDate.now()){ "不能提前确认未来睡眠" };require(s.endAt==null||s.endAt<=System.currentTimeMillis()){ "起床时间不能在未来" };onSave(s)
        } catch(e:Exception){error=e.message}
    }) {
        Title("$day 的睡眠")

        Choices(listOf("记录时间","没有睡眠","时间记不清"),when(status){"none"->"没有睡眠";"unknown"->"时间记不清";else->"记录时间"}){status=when(it){"没有睡眠"->"none";"时间记不清"->"unknown";else->"timed"}}
        if(status=="timed") {
            Panel { Title("入睡");Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){DateControl(LocalDate.parse(startDay),{startDay=it.toString()},Modifier.weight(1f),label="");ClockControl(startClock,{startClock=it},label="")}
                Title("起床");Label(day.toString());ClockControl(endClock,{endClock=it},label="") }
        }
        Input("备注（选填）",note,{note=it},lines=2)

        error?.let{Text(it,color=Clay)}
    }
}

@Composable fun CashEditor(source:CashEntry?,day:LocalDate,onDismiss:()->Unit,onSave:(CashEntry)->Unit,onDelete:(CashEntry)->Unit) {
    var title by rememberSaveable{mutableStateOf(source?.title ?: "")}
    var kind by rememberSaveable{mutableStateOf(source?.kind ?: "expense")}
    var amount by rememberSaveable{mutableStateOf(source?.let{money(if(it.kind=="expense")-it.cents else it.cents)} ?: "")}
    var date by rememberSaveable{mutableStateOf(source?.occurredAt?.let{Time.day(it).toString()} ?: day.toString())}
    var clock by rememberSaveable{mutableStateOf(source?.occurredAt?.let(Time::clock) ?: if(day==LocalDate.now())Time.clock(System.currentTimeMillis()) else "12:00")}
    var error by remember{mutableStateOf<String?>(null)}
    var deleting by remember{mutableStateOf(false)}
    EditorFrame("记一笔收支",onDismiss,{
        try {
            val value=parseMoney(amount,kind=="adjustment") ?: error("请填写金额")
            val at=Time.parse(date,clock);require(at<=System.currentTimeMillis()){ "只能记录已发生的收支" }
            val entry=CashEntry(source?.id ?: newId(),title=title.trim(),cents=if(kind=="expense")-value else value,kind=kind,occurredAt=at,createdAt=source?.createdAt ?: System.currentTimeMillis())
            entry.validate();onSave(entry)
        }catch(e:Exception){error=e.message}
    }) {
        Choices(listOf("支出","收入","余额调整"),when(kind){"expense"->"支出";"adjustment"->"余额调整";else->"收入"}){kind=when(it){"支出"->"expense";"余额调整"->"adjustment";else->"income"}}
        Input("名称 *",title,{title=it});Input(if(kind=="adjustment")"调整金额 / 元（可为负）" else "金额 / 元 *",amount,{amount=it},numeric=true)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){DateControl(LocalDate.parse(date),{date=it.toString()},Modifier.weight(1f),label="");ClockControl(clock,{clock=it},label="")}
        Label("事件里已记过的支出，无需重复记账。")
        if(kind=="adjustment")Label("调整用于修正漏记等差额，不计作收入或支出，会单独显示。")
        error?.let{Text(it,color=Clay)}
        if(source!=null)TextButton({deleting=true}){Text("删除流水",color=Clay)}
    }
    if(deleting&&source!=null)ConfirmDialog("删除流水？","资金曲线会重新计算。",{deleting=false},{onDelete(source)})
}

@Composable fun BalanceEditor(initial:Boolean,onDismiss:()->Unit,onSave:(BalanceAnchor)->Unit) {
    var amount by rememberSaveable{mutableStateOf("")}
    var date by rememberSaveable{mutableStateOf(LocalDate.now().toString())}
    var clock by rememberSaveable{mutableStateOf(if(initial)"00:00" else Time.clock(System.currentTimeMillis()))}
    var note by rememberSaveable{mutableStateOf("")}
    var error by remember{mutableStateOf<String?>(null)}
    EditorFrame(if(initial)"设置初始余额" else "核对资金余额",onDismiss,{
        try { val cents=parseMoney(amount,true) ?: error("请填写余额");val at=Time.parse(date,clock);require(at<=System.currentTimeMillis()){ "余额时刻不能在未来" };onSave(BalanceAnchor(asOf=at,cents=cents,note=note)) }catch(e:Exception){error=e.message}
    }) {

        Input("资金余额 / 元 *",amount,{amount=it},numeric=true)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){DateControl(LocalDate.parse(date),{date=it.toString()},Modifier.weight(1f),label="");ClockControl(clock,{clock=it},label="")}
        Input("范围或备注（选填）",note,{note=it},lines=2)
        Label("填写所选时刻之前的余额，该时刻及之后的流水会在此基础上累加。始终使用相同的账户范围。新的核对点会从该时刻重新计算，不改写此前历史。")

        error?.let{Text(it,color=Clay)}
    }
}
