package com.self.daymark

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import java.time.*

@Composable fun TodayScreen(data:LedgerData, day:LocalDate, onDay:(LocalDate)->Unit, onEdit:(Event)->Unit, onSleep:()->Unit,onCash:()->Unit,onClose:()->Unit,onEditCash:(CashEntry)->Unit) {
    val summary=remember(data,day){Analytics.daily(data,day)}
    val events=data.activeEvents.filter { Time.day(it.startAt)==day || (it.endAt!=null && it.startAt<Time.start(day.plusDays(1)) && it.endAt>Time.start(day)) }.sortedBy { it.startAt }
    val sleep=data.sleeps.find { it.day==day.toString() }
    val checked=data.checks.any { it.day==day.toString() }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp,16.dp,20.dp,24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { Eyebrow("EVENTLOG / 事记"); Label("本地 · 离线可用") } }
        item { Text("把今天，记清楚。",fontSize=26.sp,fontWeight=FontWeight.Bold,color=Ink); Label("时间、金钱与体验，留给下一次选择。") }
        item { DaySelector(day,onDay) }
        item { Panel(color=SkyBlue) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Column { Text("已记录支出",color=Muted,fontSize=12.sp); Text("¥ ${money(summary.expense)}",fontSize=32.sp,fontWeight=FontWeight.Bold,color=Ink) }
                Column(horizontalAlignment=Alignment.End) { Text("活动占用",color=Muted,fontSize=12.sp); Text(minutesText(summary.activityMinutes),fontSize=25.sp,fontWeight=FontWeight.SemiBold,color=Ink) }
            }
            Text("${summary.eventCount} 件已完成  ·  ${if(summary.unknownCosts>0) "${summary.unknownCosts} 件费用待补" else "只汇总已知记录"}  ·  ${if(summary.unknownDurations>0) "${summary.unknownDurations} 件时长待补" else "重叠时间不重复计算"}",color=Muted,fontSize=11.sp)
        } }
        item { Panel(Modifier.clickable(onClick=onSleep),color=if(sleep==null)Color(0xFFFFF0DC) else Color(0xFFEAEEF9)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(if(sleep==null) "☾  今天的睡眠待确认" else "☾  睡眠已确认",fontWeight=FontWeight.SemiBold)
                    Label(when(sleep?.status) { "timed" -> "${Time.local(sleep.startAt!!)} — ${Time.local(sleep.endAt!!)} · ${minutesText((sleep.endAt-sleep.startAt)/60000)}"; "none" -> "已明确记录：没有睡眠"; "unknown" -> "已确认：时间记不清"; else -> "起床后补一笔，才能完成当天确认" }) }
                Text("›",fontSize=24.sp,color=PrimaryBlue)
            }
        } }
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { Title("这一天的事情"); TextButton(onCash){Text("＋ 收支") } } }
        if(events.isEmpty()) item { EmptyCard("留下一件小事","吃饭、读书，或好好休息。点下方开始，没有预估也可以记录。") }
        items(events,key={it.id}) { event -> EventCard(event,data.cost(event),data.latestForecast(event.id)){onEdit(event)} }
        val cash=data.cash.filter { it.eventId==null && Time.day(it.occurredAt)==day }.sortedBy{it.occurredAt}
        if(cash.isNotEmpty()) item { Title("独立收支") }
        items(cash,key={"cash-${it.id}"}) { c -> Panel(Modifier.clickable{onEditCash(c)}) { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(c.title,Modifier.weight(1f)); Text((if(c.cents>=0) "+" else "")+"¥ "+money(c.cents),color=if(c.cents>=0)PrimaryBlue else Clay) }; Label("${Time.clock(c.occurredAt)} · ${when(c.kind){"income"->"收入";"adjustment"->"余额调整";else->"支出"}}") } }
        if(day<=LocalDate.now()) item { OutlinedButton(onClose,Modifier.fillMaxWidth(),enabled=sleep!=null&&!checked) { Text(if(checked)"✓ 当天记录已确认" else if(sleep==null)"先确认睡眠，再完成当天记录" else "确认当天记录") }; Label("确认表示已复核，不代表空白时间或费用为零。修改后需要重新确认。") }
    }
}

@Composable fun EventCard(event:Event,cost:Long?,forecast:Forecast?,onClick:()->Unit) {
    Panel(Modifier.clickable(onClick=onClick)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Label("${Time.clock(event.startAt)}  ·  ${event.category}")
            Text(when(event.status){"planned"->"计划中";"cancelled"->"已取消";else->"已完成"},color=if(event.status=="planned")Amber else Muted,fontSize=11.sp)
        }
        Text(event.title,fontSize=18.sp,fontWeight=FontWeight.SemiBold)
        if(event.experience.isNotBlank()) Text(event.experience,color=Muted,fontSize=13.sp,maxLines=3)
        if(event.status=="done") {
            val minutes=event.endAt?.let{(it-event.startAt)/60000}
            Label("实际 ${minutesText(minutes)} · ¥ ${money(cost)}")
            event.feeling?.let { Label("体验 ${ExperienceScale.text(it)}") }
        }
        if(forecast!=null) Label("预计 ${minutesText(forecast.minutes)} · ¥ ${money(forecast.costCents)}${if(!forecast.prospective)" · 回顾记录" else ""}")
    }
}

@Composable fun CalendarScreen(data:LedgerData,selectedDay:LocalDate,onSelect:(LocalDate)->Unit) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(selectedDay).toString()) }
    var mode by rememberSaveable { mutableStateOf("收支") }
    val month=YearMonth.parse(monthText)
    val cells=Analytics.calendarDays(month).toMutableList().also { while(it.size%7!=0)it.add(null) }
    val summaries=remember(data,month){(1..month.lengthOfMonth()).associate { val d=month.atDay(it); d to Analytics.daily(data,d) }}
    val totalExpense=summaries.values.sumOf{it.expense}
    val trackingStart=remember(data){minOf(Analytics.recordedDates(data).firstOrNull() ?: LocalDate.now(),LocalDate.now())}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Title("一月的时间与收支")
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton({monthText=month.minusMonths(1).toString()}){Text("‹",fontSize=24.sp)}
            DateControl(month.atDay(1),{monthText=YearMonth.from(it).toString()},label="",display="${month.year} 年 ${month.monthValue} 月")
            TextButton({monthText=month.plusMonths(1).toString()}){Text("›",fontSize=24.sp)}
        }
        Choices(listOf("收支","活动","睡眠"),mode,{mode=it})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Column { Label("本月已记录支出");Text("¥ ${money(totalExpense)}",color=Ink,fontSize=20.sp,fontWeight=FontWeight.SemiBold) }
            Label("${summaries.values.count{it.anyData}} 天有记录")
        }
        Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface) {
          Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row { listOf("一","二","三","四","五","六","日").forEach { Text(it,Modifier.weight(1f),fontSize=11.sp,color=Muted,textAlign=TextAlign.Center) } }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                    week.forEach { d ->
                        if(d==null) Spacer(Modifier.weight(1f).height(58.dp))
                        else {
                            val s=summaries.getValue(d)
                            val future=d>LocalDate.now()
                            val color=when { !s.anyData->Color(0xFFF0F2F7); mode=="收支"&&s.net<0->Color(0xFFF8E9E1); else->Color(0xFFE7ECFA) }
                            Column(Modifier.weight(1f).height(58.dp).background(color,RoundedCornerShape(9.dp)).testTag("calendar-day-$d").clickable{onSelect(d)}.padding(vertical=3.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.SpaceBetween) {
                                Text(d.dayOfMonth.toString(),fontSize=13.sp,lineHeight=18.sp,fontWeight=if(d==LocalDate.now())FontWeight.Bold else FontWeight.Normal,color=if(future)Muted else Ink)
                                val sleepStatus=data.sleeps.find{it.day==d.toString()}?.status
                                val value=when {
                                    !s.anyData->"—"
                                    mode=="收支"->"${if(s.net>0) "+" else ""}${money(s.net).removeSuffix(".00")}"
                                    mode=="活动"->"${s.activityMinutes}m"
                                    s.sleepMinutes>0->"%.1fh".format(s.sleepMinutes/60.0)
                                    sleepStatus=="unknown"->"未知"
                                    sleepStatus=="none"->"0h"
                                    else->"—"
                                }
                                Text(value,fontSize=10.sp,lineHeight=13.sp,maxLines=1,color=if(mode=="收支"&&s.net<0)Clay else PrimaryBlue)
                                Text(if(!future&&d>=trackingStart&&!s.sleepConfirmed)"待睡眠" else if(s.sleepConfirmed)"·" else "",fontSize=8.sp,lineHeight=10.sp,color=Muted)
                            }
                        }
                    }
                }
            }
          }
        }
        Label(when(mode){"收支"->"格内为已记录净收支（含调整），不是资产余额；颜色只表示资金方向。";"活动"->"普通活动按自然日裁切、重叠区间只计一次；未记录的时间保持未知。";else->"格内为落在该自然日内的已记录睡眠时长；每天按起床日期确认。"})
        TextButton({monthText=YearMonth.now().toString()}){Text("回到本月")}
        Spacer(Modifier.height(12.dp))
    }
}
