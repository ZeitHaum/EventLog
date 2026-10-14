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
        item { Title("趋势") }
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
        item { Panel { Title("每天的活动时间");LineChart(daily.map{if(it.anyData)it.activityMinutes/60.0 else null},Amber,"已记录活动时长趋势，小时");Label("单位：小时 · 按日期累加耗时，旧区间去重。") } }
        item { Panel { Title("每天的睡眠时间");LineChart(daily.map{if(it.sleepMinutes>0||data.sleeps.find{s->s.day==it.date.toString()}?.status=="none")it.sleepMinutes/60.0 else null},Color(0xFF6B7D96),"已记录睡眠时长趋势，小时");Label("按自然日拆分跨午夜睡眠；未知与漏记不填零。") } }
        item { Panel {
            Title("体验留下的线索")
            val events=data.activeEvents.filter{it.status=="done"&&Time.day(it.startAt) in start..end}
            val rated=events.mapNotNull{it.feeling}
            if(rated.isEmpty())Label("还没有体验评分")
            else {
                Label("${rated.size} 次评分 · 平均 ${"%.1f".format(rated.average())}")
                ExperienceScale.values.forEach { score ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(ExperienceScale.text(score));Label("${rated.count{it==score}} 次")}
                }
            }
        } }
    }
}

@Composable fun DataScreen(data:LedgerData,onExport:()->Unit,onImport:()->Unit,onBalance:()->Unit,onCash:()->Unit,hasRecovery:Boolean,onRecovery:()->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Title("数据") }
        item { Panel {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric(data.activeEvents.count{it.status=="done"}.toString(),"记录");Metric(data.cash.size.toString(),"流水");Metric(data.activePlans.size.toString(),"计划")}

        } }
        item { Panel { Title("导出与恢复");Label("ZIP 包含完整 JSON、分类 CSV 和分析说明，可在电脑离线分析。")
            Button(onExport,Modifier.fillMaxWidth()){Text("导出全部数据 · ZIP")}
            OutlinedButton(onImport,Modifier.fillMaxWidth()){Text("从 ZIP / JSON 恢复")}
            if(hasRecovery)TextButton(onRecovery){Text("导出上次恢复前备份")}

        } }
        item { Panel { Title("资金设置");
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onBalance){Text(if(data.balances.isEmpty())"设置初始余额" else "核对余额")};OutlinedButton(onCash){Text("记一笔收支")}}
            data.balances.sortedByDescending{it.asOf}.take(5).forEach{Label("${Time.local(it.asOf)} · ¥ ${money(it.cents)}${if(it.note.isNotBlank())" · ${it.note}" else ""}")}
        } }
        item { Label("事记 EventLog 0.2.0 · 数据保存在本机") }
    }
}
