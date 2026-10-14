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
    val id=remember(key){source?.id ?: newId()}
    var title by rememberSaveable(key){mutableStateOf(source?.title ?: "")}
    var category by rememberSaveable(key){mutableStateOf(source?.category ?: "生活")}
    var status by rememberSaveable(key){mutableStateOf(source?.status ?: if(day>LocalDate.now())"planned" else "done")}
    val initialAt=source?.endAt ?: source?.startAt ?: if(day==LocalDate.now())System.currentTimeMillis() else Time.start(day)+12*3600000
    var date by rememberSaveable(key){mutableStateOf(Time.day(initialAt).toString())}
    var clock by rememberSaveable(key){mutableStateOf(Time.clock(initialAt))}
    var duration by rememberSaveable(key){mutableStateOf(source?.endAt?.let{((it-source.startAt)/60000).toString()} ?: "")}
    var cost by rememberSaveable(key){mutableStateOf(actualCost?.let(::money) ?: "")}
    var description by rememberSaveable(key){mutableStateOf(source?.description ?: "")}
    var experience by rememberSaveable(key){mutableStateOf(source?.experience ?: "")}
    var feeling by rememberSaveable(key){mutableStateOf(source?.feeling)}
    var worth by rememberSaveable(key){mutableStateOf(source?.worth)}
    var expanded by rememberSaveable(key){mutableStateOf(latest!=null||status=="planned")}
    var expMinutes by rememberSaveable(key){mutableStateOf(latest?.minutes?.toString() ?: "")}
    var expCost by rememberSaveable(key){mutableStateOf(latest?.costCents?.let(::money) ?: "")}
    var expFeeling by rememberSaveable(key){mutableStateOf(latest?.expectedFeeling)}
    var probabilityOpen by rememberSaveable(key){mutableStateOf(latest?.probability!=null)}
    var question by rememberSaveable(key){mutableStateOf(latest?.question ?: "")}
    var probability by rememberSaveable(key){mutableStateOf(latest?.probability?.toString() ?: "")}
    var deadlineDay by rememberSaveable(key){mutableStateOf(latest?.deadlineAt?.let{Time.day(it).toString()} ?: day.plusDays(7).toString())}
    var deadlineClock by rememberSaveable(key){mutableStateOf(latest?.deadlineAt?.let(Time::clock) ?: "23:59")}
    var basis by rememberSaveable(key){mutableStateOf(latest?.basis ?: "")}
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    fun save() {
        try {
            val at=Time.parse(date,clock)
            val minutes=if(status=="done")parseMinutes(duration) else null
            val paid=if(status=="done")parseMoney(cost) else null
            val now=System.currentTimeMillis()
            val event=Event(id,title.trim(),category,if(minutes!=null)at-minutes*60000 else at,if(minutes!=null)at else null,status,
                description.trim(),experience.trim(),if(status=="done")feeling else null,if(status=="done")worth else null,paid!=null,
                source?.createdAt ?: now,now,source?.firstOutcomeAt)
            event.validate()
            require(status!="done"||at<=now+60000){"未来发生的事情请保存为计划"}
            val estimatedMinutes=parseMinutes(expMinutes)
            val estimatedCost=parseMoney(expCost)
            val prob=if(probability.isBlank())null else probability.toIntOrNull().also{require(it!=null&&it in 0..100){"概率请填 0–100 的整数"}}
            val deadline=if(prob!=null)Time.parse(deadlineDay,deadlineClock) else null
            val f=if(estimatedMinutes!=null||estimatedCost!=null||expFeeling!=null||prob!=null||question.isNotBlank()) Forecast(eventId=id,minutes=estimatedMinutes,costCents=estimatedCost,expectedFeeling=expFeeling,question=question.trim(),probability=prob,deadlineAt=deadline,basis=basis.trim(),prospective=status=="planned"&&event.startAt>now&&event.firstOutcomeAt==null) else null
            f?.validate()
            onSave(event,paid,f)
        } catch(e:Exception) { error=e.message ?: "请检查填写内容" }
    }
    EditorFrame(if(source==null)"记一件事" else "编辑事件",onDismiss,::save) {
        error?.let { Text(it,color=Clay) }
        Input("事情名称 *",title,{title=it})
        Choices(listOf("已完成","计划中","已取消"),when(status){"planned"->"计划中";"cancelled"->"已取消";else->"已完成"}) { label ->
            if(label=="已完成"&&status=="planned") { date=LocalDate.now().toString();clock=Time.clock(System.currentTimeMillis()) }
            status=when(label){"计划中"->"planned";"已取消"->"cancelled";else->"done"}
            if(status=="planned")expanded=true
        }
        Choices(categories,category,{category=it})
        Panel {
            Title(if(status=="done")"实际发生" else "安排时间")
            Label(if(status=="done")"填写结束时刻与用时，自动推算开始时间；时长留空时只记录发生时刻。" else "填写预计开始时刻。开始前保存的预估才会进入事前误差分析。")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { DateControl(LocalDate.parse(date),{date=it.toString()},Modifier.weight(1f),label="");ClockControl(clock,{clock=it},label=if(status=="done")"结束" else "开始") }
            if(status=="done") {
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { Input("用时 / 分钟",duration,{duration=it},Modifier.weight(1f),true);Input("支出 / 元",cost,{cost=it},Modifier.weight(1f),true) }
                Label("空白表示未知；填 0 表示明确没有花费。")
                Rating("当时体验",feeling,{feeling=it})
                Input("体验到了什么？（选填）",experience,{experience=it},lines=2)
            }
            Input("做了什么 / 备注（选填）",description,{description=it},lines=2)
        }
        TextButton({expanded=!expanded}){Text(if(expanded)"收起预估与预测 ↑" else "＋ 添加预估与预测（选填）")}
        if(expanded) Panel {
            Title("我原本的判断")
            Label(if(status=="planned")"预估会保留版本。现在已开始或过去发生的事件，会标记为回顾记录。" else "现在补写的判断属于回顾，不计入事前预测成绩；已有事前版本始终保留。")
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { Input("预计分钟",expMinutes,{expMinutes=it},Modifier.weight(1f),true);Input("预计支出 / 元",expCost,{expCost=it},Modifier.weight(1f),true) }
            Rating("期待的体验",expFeeling,{expFeeling=it})
            TextButton({probabilityOpen=!probabilityOpen}){Text(if(probabilityOpen)"收起概率问题" else "＋ 记录一个可验证的预测")}
            if(probabilityOpen) {
                Input("可验证的问题 / 完成标准",question,{question=it},lines=2)
                Input("发生的概率 / %",probability,{probability=it},numeric=true)
                Label("结果截止时间")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { DateControl(LocalDate.parse(deadlineDay),{deadlineDay=it.toString()},Modifier.weight(1f),label="");ClockControl(deadlineClock,{deadlineClock=it},label="") }
                Input("判断依据（选填）",basis,{basis=it},lines=2)
            }
        }
        if(status=="done") Panel { Rating("回头看，是否值得",worth,{worth=it});Label("可以隔天再补，记录事后对是否值得的判断。") }
        if(source!=null) TextButton({deleting=true}){Text("删除这件事",color=Clay)}
        Spacer(Modifier.height(30.dp))
    }
    if(deleting&&source!=null)ConfirmDialog("删除事件？","事件将从日历和统计中移除，关联支出同时删除；事件和预测历史仍可在导出中查看。",{deleting=false},{onDelete(source)})
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
        Eyebrow("DAILY CHECK / 每日确认");Title("$day 的睡眠")
        Label("按起床日期确认。以下时间只是可编辑的起点，保存后才成为记录。")
        Choices(listOf("记录时间","没有睡眠","时间记不清"),when(status){"none"->"没有睡眠";"unknown"->"时间记不清";else->"记录时间"}){status=when(it){"没有睡眠"->"none";"时间记不清"->"unknown";else->"timed"}}
        if(status=="timed") {
            Panel { Title("入睡");Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){DateControl(LocalDate.parse(startDay),{startDay=it.toString()},Modifier.weight(1f),label="");ClockControl(startClock,{startClock=it},label="")}
                Title("起床");Label(day.toString());ClockControl(endClock,{endClock=it},label="") }
        }
        Input("备注（选填）",note,{note=it},lines=2)
        Label("记录主观入睡与起床时间；不根据手机使用情况推断睡眠。睡眠不涉及金钱。")
        error?.let{Text(it,color=Clay)}
    }
}

@Composable fun CashEditor(source:CashEntry?,day:LocalDate,onDismiss:()->Unit,onSave:(CashEntry)->Unit,onDelete:(CashEntry)->Unit) {
    var title by rememberSaveable{mutableStateOf(source?.title ?: "")}
    var kind by rememberSaveable{mutableStateOf(source?.kind ?: "income")}
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
        Choices(listOf("收入","支出","余额调整"),when(kind){"expense"->"支出";"adjustment"->"余额调整";else->"收入"}){kind=when(it){"支出"->"expense";"余额调整"->"adjustment";else->"income"}}
        Input("名称 *",title,{title=it});Input(if(kind=="adjustment")"调整金额 / 元（可为负）" else "金额 / 元 *",amount,{amount=it},numeric=true)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){DateControl(LocalDate.parse(date),{date=it.toString()},Modifier.weight(1f),label="");ClockControl(clock,{clock=it},label="")}
        Label("这是独立流水。事件中已经记录的支出，不要再记一次；自己账户之间转账不改变总余额。退款可记录为收入，并注明原事件。")
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
        Title("从一个已知的数开始")
        Input("资金余额 / 元 *",amount,{amount=it},numeric=true)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){DateControl(LocalDate.parse(date),{date=it.toString()},Modifier.weight(1f),label="");ClockControl(clock,{clock=it},label="")}
        Input("范围或备注（选填）",note,{note=it},lines=2)
        Label("填写所选时刻之前的余额，该时刻及之后的流水会在此基础上累加。始终使用相同的账户范围。新的核对点会从该时刻重新计算，不改写此前历史。")
        Label("第一版追踪资金余额，不自动估值投资、不计算负债，不代表完整净资产。")
        error?.let{Text(it,color=Clay)}
    }
}
