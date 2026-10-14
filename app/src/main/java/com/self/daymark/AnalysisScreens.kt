package com.self.daymark

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import java.time.*
import kotlin.math.*

@Composable fun LineChart(values:List<Double?>,color:Color=PrimaryBlue,label:String) {
    val known=values.filterNotNull()
    if(known.isEmpty()) { Label("尚无可绘制的数据");return }
    val minimum=known.min();val maximum=known.max();val span=(maximum-minimum).let{if(it<.01)1.0 else it}
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Label("最高 ${"%.2f".format(maximum)}");Label("最低 ${"%.2f".format(minimum)}") }
    Canvas(Modifier.fillMaxWidth().height(155.dp).semantics{contentDescription=label}) {
        val padding=8.dp.toPx();val width=size.width-padding*2;val height=size.height-padding*2
        (0..3).forEach { n -> val y=padding+height*n/3;drawLine(Color(0xFFE2E6F0),Offset(padding,y),Offset(size.width-padding,y),1.dp.toPx()) }
        fun point(i:Int,value:Double)=Offset(padding+width*i/max(1,values.size-1),if(maximum==minimum)size.height/2 else (padding+height*(1-(value-minimum)/span)).toFloat())
        values.forEachIndexed { i,v -> if(v!=null) {
            if(i>0&&values[i-1]!=null)drawLine(color,point(i-1,values[i-1]!!),point(i,v),2.5.dp.toPx())
            if(values.size<=31||i==values.lastIndex||i==0)drawCircle(color,3.dp.toPx(),point(i,v))
        } }
    }
}

@Composable fun TrendsScreen(data:LedgerData,onBalance:()->Unit) {
    var range by rememberSaveable{mutableStateOf("周")}
    var ending by rememberSaveable{mutableStateOf(LocalDate.now().toString())}
    val end=LocalDate.parse(ending)
    val count=when(range){"月"->30;"年"->365;else->7}
    val start=end.minusDays(count-1L)
    val days=remember(start,end){(0 until count).map{start.plusDays(it.toLong())}}
    val daily=remember(data,days){days.map{Analytics.daily(data,it)}}
    val balances=remember(data,days){days.map{Analytics.balanceAt(data,min(Time.start(it.plusDays(1))-1,System.currentTimeMillis()))?.div(100.0)}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Eyebrow("PATTERNS / 看见变化");Text("把记录，放长一点看。",fontSize=26.sp,fontWeight=FontWeight.Bold) }
        item { Choices(listOf("周","月","年"),range,{range=it});DateControl(end,{ending=minOf(it,LocalDate.now()).toString()},label="截至");Label("$start — $end · 最近 $count 天") }
        item { Panel {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Title("资金余额");TextButton(onBalance){Text(if(data.balances.isEmpty())"设置起点" else "核对余额")}}
            val last=balances.lastOrNull{it!=null}
            Text(if(last==null)"先留下初始余额" else "¥ ${"%.2f".format(last)}",fontSize=30.sp,color=PrimaryBlue,fontWeight=FontWeight.Bold)
            LineChart(balances,label="资金余额趋势")
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Label(start.toString());Label(end.toString())}
            Label("从余额起点累加已记录流水。空白表示尚无起点；核对点可能造成跳变，不等同收益。")
        } }
        item { Panel {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric("¥ ${money(daily.sumOf{it.income})}","记录内收入",color=PrimaryBlue);Metric("¥ ${money(daily.sumOf{it.expense})}","记录内支出",color=Clay)}
            Label("余额调整：¥ ${money(daily.sumOf{it.adjustment})} · 未记录收支不在统计内")
        } }
        item { Panel { Title("每天的活动时间");LineChart(daily.map{if(it.anyData)it.activityMinutes/60.0 else null},Amber,"已记录活动时长趋势，小时");Label("单位：小时 · 仅已记录普通活动的区间并集，睡眠另计。") } }
        item { Panel { Title("每天的睡眠时间");LineChart(daily.map{if(it.sleepMinutes>0||data.sleeps.find{s->s.day==it.date.toString()}?.status=="none")it.sleepMinutes/60.0 else null},Color(0xFF6B7D96),"已记录睡眠时长趋势，小时");Label("按自然日拆分跨午夜睡眠；未知与漏记不填零。") } }
        item { Panel {
            Title("体验留下的线索")
            val events=data.activeEvents.filter{it.status=="done"&&Time.day(it.startAt) in start..end}
            if(events.none{it.feeling!=null})Label("给一些事件留下体验评分后，这里会按类别汇总。")
            categories.forEach { category ->
                val values=events.filter{it.category==category&&it.feeling!=null}
                if(values.isNotEmpty())Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(category);Label("${values.size} 次 · 平均 ${"%.1f".format(values.map{it.feeling!!}.average())}")}
            }
            Label("评分 −2 到 2：非常不值 → 非常值得，0 为中性。未评分不参与平均。")
            Label("这是记录中的关联与主观评价，不等同因果结论。")
        } }
    }
}

@Composable fun ForecastScreen(data:LedgerData,onEdit:(Event)->Unit,onResolve:(Forecast,Boolean)->Unit) {
    val errors=remember(data){Analytics.errors(data)}
    val rows=remember(data){Analytics.probabilityRows(data)}
    var resolving by remember{mutableStateOf<Pair<Forecast,Boolean>?>(null)}
    val predictions=data.activeEvents.mapNotNull { e ->
        val list=data.forecasts.filter{it.eventId==e.id&&it.probability!=null}.sortedBy{it.madeAt}
        (list.firstOrNull{Analytics.isProspective(it,e)} ?: list.lastOrNull())?.let{e to it}
    }.sortedBy { (_,f) -> data.resolutions.any{it.forecastId==f.id} }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Eyebrow("LEARN / 校准自己的判断");Text("原以为，与实际。",fontSize=28.sp,fontWeight=FontWeight.Bold);Label("每件事取首份有效事前预估。回顾补写保留，但不计分。") }
        item { Panel {
            Title("估算偏差")
            val times=errors.mapNotNull{it.minutesError};val costs=errors.mapNotNull{it.costError}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric(if(times.isEmpty())"—" else "%+.1f 分".format(times.average()),"时间偏差 · ${times.size} 次");Metric(if(costs.isEmpty())"—" else "%+.2f 元".format(costs.average()/100),"费用偏差 · ${costs.size} 次")}
            Label("偏差 = 实际 − 预计；正值表示低估。")
            if(times.isNotEmpty())Label("时间平均绝对误差 ${"%.1f".format(times.map{abs(it)}.average())} 分钟")
            if(costs.isNotEmpty())Label("费用平均绝对误差 ${"%.2f".format(costs.map{abs(it)}.average()/100)} 元")
            if(errors.isEmpty())Label("先为尚未开始的计划填写预估，再完成事件，就会形成可比较的样本。")
        } }
        if(errors.isNotEmpty())item{Panel{Title("按事情类型复盘");categories.forEach{category->val group=errors.filter{it.event.category==category};val times=group.mapNotNull{it.minutesError};if(times.isNotEmpty())Label("$category · ${times.size} 次 · 平均时间偏差 ${"%+.1f".format(times.average())} 分钟")}}}
        item { Panel {
            Title("概率判断")
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric(rows.size.toString(),"有效已判定预测");Metric(if(rows.isEmpty())"—" else "%.3f".format(rows.map{it.brier}.average()),"Brier 分数 · 越低越好")}
            Label("Brier = (预测概率 − 实际结果)²，发生为 1，未发生为 0。单次结果不能证明概率是否准确。")
            if(rows.isNotEmpty()) {
                HorizontalDivider()
                (0..4).forEach{bin->val group=rows.filter{min(4,it.forecast.probability!!/20)==bin};if(group.isNotEmpty())Label("${bin*20}–${if(bin==4)100 else bin*20+19}% · ${group.size} 次 · 平均预测 ${"%.0f".format(group.map{it.forecast.probability!!}.average())}% / 实际发生 ${"%.0f".format(group.count{it.resolution.happened}*100.0/group.size)}%")}
                Label("以上只是当前样本的观测，少量或相关样本不支持稳定结论。")
            }
        } }
        item { Title("待验证的判断") }
        if(predictions.isEmpty())item{EmptyCard("从一个具体问题开始","在事件的“预估与预测”里填写问题、概率和截止日期，例如：周五前能否完成一篇初稿？")}
        items(predictions,key={it.second.id}) { (event,f) ->
            val result=data.resolutions.find{it.forecastId==f.id}
            Panel {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Label(event.title);Text("${f.probability}%",color=PrimaryBlue,fontWeight=FontWeight.Bold,fontSize=23.sp)}
                Text(f.question,fontWeight=FontWeight.Medium)
                Label("截止 ${Time.local(f.deadlineAt!!)} · ${if(Analytics.isProspective(f,event))"事前预测" else "回顾记录，不计分"}")
                if(f.basis.isNotBlank())Label(f.basis)
                if(result!=null)Label("已判定：${if(result.happened)"发生" else "未发生"} · ${Time.local(result.resolvedAt)}")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({resolving=f to true}){Text(if(result==null)"已发生" else "改为发生")}
                    OutlinedButton({resolving=f to false},enabled=System.currentTimeMillis()>=f.deadlineAt){Text(if(result==null)"未发生" else "改为未发生")}
                    TextButton({onEdit(event)}){Text("事件")}
                }
                if(result==null&&System.currentTimeMillis()<f.deadlineAt)Label("截止前保留未定结果，不能提前判为未发生。")
            }
        }
        if(errors.isNotEmpty())item{Title("已完成事件的对照")}
        items(errors.takeLast(30).reversed(),key={"error-${it.event.id}"}) { row -> Panel(Modifier.clickable{onEdit(row.event)}){Text(row.event.title,fontWeight=FontWeight.SemiBold);Label("时间偏差 ${row.minutesError?.let{"%+d 分".format(it)} ?: "—"} · 费用偏差 ${row.costError?.let{"%+.2f 元".format(it/100.0)} ?: "—"}") } }
    }
    resolving?.let { (f,result) -> ConfirmDialog("确认预测结果","${f.question}\n\n按事先写下的标准，确认它${if(result)"已经发生" else "截至期限没有发生"}。",{resolving=null},{onResolve(f,result);resolving=null}) }
}

@Composable fun DataScreen(data:LedgerData,onExport:()->Unit,onImport:()->Unit,onBalance:()->Unit,onCash:()->Unit,hasRecovery:Boolean,onRecovery:()->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Eyebrow("YOURS / 数据由你保管");Text("记录可以带走。",fontSize=28.sp,fontWeight=FontWeight.Bold) }
        item { Panel {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric(data.activeEvents.size.toString(),"事件");Metric(data.cash.size.toString(),"流水");Metric(data.forecasts.size.toString(),"预估版本")}
            Label("无需账号，保存在本机。第一版不联网同步。")
        } }
        item { Panel { Title("导出与恢复");Label("ZIP 包含完整 JSON、分类 CSV 和分析说明，可在电脑离线分析。")
            Button(onExport,Modifier.fillMaxWidth()){Text("导出全部数据 · ZIP")}
            OutlinedButton(onImport,Modifier.fillMaxWidth()){Text("从 ZIP / JSON 恢复")}
            if(hasRecovery)TextButton(onRecovery){Text("导出上次恢复前备份")}
            Label("恢复前会显示数量并确认，同时保留当前数据的一份本地备份。")
        } }
        item { Panel { Title("资金设置");Label("记录收入、支出和调整，设置带日期的初始余额。这里追踪指定范围的资金余额，不是完整净资产。")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onBalance){Text(if(data.balances.isEmpty())"设置初始余额" else "核对余额")};OutlinedButton(onCash){Text("记一笔收支")}}
            data.balances.sortedByDescending{it.asOf}.take(5).forEach{Label("${Time.local(it.asOf)} · ¥ ${money(it.cents)}${if(it.note.isNotBlank())" · ${it.note}" else ""}")}
        } }
        item { Panel { Title("记录约定");Label("• 留空是未知，金额和时长的 0 是明确为零。\n• 体验评分 −2 到 2，0 为中性；未选为未评分。\n• 活动重叠时间不重复累计。\n• 睡眠按起床日确认，跨日时长按自然日拆分。\n• 保存旧预估，修改不会覆盖历史。\n• 取消事件与未定预测会保留。\n• 本机数据与 Diet 食记完全独立。") } }
        item { Label("事记 EventLog 0.1.1 · 每次留一点依据") }
    }
}
