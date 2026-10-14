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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import java.time.*

fun periodLabel(value:String) = when(value){"morning"->"上午";"afternoon"->"下午";"evening"->"晚上";else->"不限时段"}
fun periodValue(label:String) = when(label){"上午"->"morning";"下午"->"afternoon";"晚上"->"evening";else->"any"}
fun Plan.group() = startMinute?.let { when { it<720->"morning";it<1080->"afternoon";else->"evening" } } ?: period
fun Plan.orderMinute() = startMinute ?: when(period){"morning"->540;"afternoon"->840;"evening"->1140;else->1440}
fun Plan.scheduleText():String = listOfNotNull(day, startMinute?.let{"%02d:%02d".format(it/60,it%60)} ?: period.takeIf{it!="any"}?.let(::periodLabel), expectedMinutes?.let{"预计 ${minutesText(it)}"}).joinToString(" · ")

data class TimelineBlock(val plan:Plan,val start:Int,val end:Int,val lane:Int)
fun timelineBlocks(plans:List<Plan>,day:LocalDate):List<TimelineBlock> {
    val from=Time.start(day);val to=Time.start(day.plusDays(1))
    val candidates=plans.filter{it.deletedAt==null&&it.day!=null&&it.startMinute!=null}.mapNotNull { p ->
        val start=Time.parse(p.day!!,"%02d:%02d".format(p.startMinute!!/60,p.startMinute%60))
        val end=start+(p.expectedMinutes ?: 0)*60000
        if(start>=to || (start<from && end<=from))null
        else Triple(p,((maxOf(start,from)-from)/60000).toInt(),((minOf(maxOf(end,start+30*60000),to)-from)/60000).toInt())
    }.sortedWith(compareBy<Triple<Plan,Int,Int>>{it.second}.thenBy{it.first.createdAt})
    val ends=mutableListOf<Int>()
    return candidates.map { (p,start,end) ->
        var lane=ends.indexOfFirst{it<=start}
        if(lane<0){lane=ends.size;ends.add(end)}else ends[lane]=end
        TimelineBlock(p,start,maxOf(start+1,end),lane)
    }
}

@Composable fun PlansScreen(data:LedgerData,day:LocalDate,onDay:(LocalDate)->Unit,onEdit:(Plan)->Unit,
    onComplete:(Plan)->Unit,onUndo:(Plan)->Unit,onRecord:(Plan)->Unit,onNew:(LocalDate?)->Unit) {
    var mode by rememberSaveable{mutableStateOf("我的一天")}
    var grouped by rememberSaveable{mutableStateOf(false)}
    var undoId by rememberSaveable{mutableStateOf<String?>(null)}
    val plans=data.activePlans
    val selected=plans.filter{it.day==day.toString()}.sortedWith(compareBy<Plan>{it.completedAt!=null}.thenBy{it.orderMinute()}.thenBy{it.createdAt})
    val pending=selected.filter{it.completedAt==null}
    val done=selected.filter{it.completedAt!=null}
    fun open(p:Plan){if(p.completedAt!=null)onRecord(p)else onEdit(p)}
    LazyColumn(Modifier.fillMaxSize().testTag("plans-list"),contentPadding=PaddingValues(20.dp,16.dp,20.dp,20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Title("计划");Choices(listOf("我的一天","日程","待安排"),mode,{mode=it}) }
        if(mode=="待安排") {
            item{TextButton({onNew(null)}){Text("＋ 加入待安排")}}
            val backlog=plans.filter{it.day==null}.sortedBy{it.completedAt!=null}
            if(backlog.isEmpty())item{EmptyCard("还没有待安排事项","只写名称，日期稍后再定。")}
            items(backlog,key={it.id}){p->PlanCard(p,{open(p)},{onComplete(p)},{undoId=p.id})}
        }else{
            item{DaySelector(day,onDay)}
            item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                Text("${done.size} / ${selected.size} 已完成",color=PrimaryBlue,fontWeight=FontWeight.SemiBold)
                if(mode=="我的一天")TextButton({grouped=!grouped}){Text(if(grouped)"切为列表" else "按时段分组")}
                else TextButton({onNew(day)}){Text("＋ 日程")}
            }}
            if(mode=="日程") {
                item{PlanMonth(day,plans,onDay)}
                item{ScheduleTimeline(plans,day,::open)}
                if(selected.isNotEmpty())item{Title("当天事项")}
                items(selected,key={it.id}){p->PlanCard(p,{open(p)},{onComplete(p)},{undoId=p.id})}
            }else{
                if(selected.isEmpty())item{EmptyCard("今天想做些什么？","耗时、时段都可以稍后补。")}
                if(grouped){
                    listOf("any","morning","afternoon","evening").forEach { period ->
                        val group=pending.filter{it.group()==period}
                        if(group.isNotEmpty())item{Title(periodLabel(period))}
                        items(group,key={it.id}){p->PlanCard(p,{open(p)},{onComplete(p)},{undoId=p.id})}
                    }
                }else items(pending,key={it.id}){p->PlanCard(p,{open(p)},{onComplete(p)},{undoId=p.id})}
                if(done.isNotEmpty())item{Title("已完成")}
                items(done,key={it.id}){p->PlanCard(p,{open(p)},{onComplete(p)},{undoId=p.id})}
                val previous=plans.filter{it.completedAt==null&&it.day!=null&&it.day<day.toString()}.sortedBy{it.day}
                if(previous.isNotEmpty())item{Title("之前未完成 · ${previous.size}")}
                items(previous,key={"previous-${it.id}"}){p->PlanCard(p,{onEdit(p)},{onComplete(p)},{undoId=p.id})}
            }
        }
    }
    plans.find{it.id==undoId}?.let { p -> ConfirmDialog("撤销完成？","对应记录和支出会移出统计。再次完成可恢复已填的耗时、费用与体验。",{undoId=null},{onUndo(p);undoId=null}) }
}

@Composable fun PlanCard(plan:Plan,onOpen:()->Unit,onComplete:()->Unit,onUndo:()->Unit) {
    val done=plan.completedAt!=null
    Panel(Modifier.testTag("plan-${plan.id}"),color=if(done)Color(0xFFECF3FA)else MaterialTheme.colorScheme.surface){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Column(Modifier.weight(1f).clickable(onClick=onOpen)){
                Text(plan.title,fontSize=17.sp,fontWeight=FontWeight.SemiBold,textDecoration=if(done)TextDecoration.LineThrough else TextDecoration.None)
                if(plan.scheduleText().isNotBlank())Label(plan.scheduleText())
                if(plan.notes.isNotBlank())Text(plan.notes,color=Muted,maxLines=2,fontSize=12.sp,overflow=TextOverflow.Ellipsis)
            }
            if(done)TextButton(onOpen){Text("补充记录")}
            else Button(onComplete,Modifier.testTag("complete-${plan.id}")){Text("完成")}
        }
        if(done)TextButton(onUndo,Modifier.testTag("undo-${plan.id}")){Text("✓ 已完成 · 撤销")}
    }
}

@Composable fun PlanMonth(day:LocalDate,plans:List<Plan>,onDay:(LocalDate)->Unit){
    val month=YearMonth.from(day)
    val cells=Analytics.calendarDays(month).toMutableList().also{while(it.size%7!=0)it.add(null)}
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            TextButton({onDay(day.minusMonths(1))}){Text("‹ 上月")};Label("${month.year} 年 ${month.monthValue} 月");TextButton({onDay(day.plusMonths(1))}){Text("下月 ›")}
        }
        Row{listOf("一","二","三","四","五","六","日").forEach{Label(it,Modifier.weight(1f))}}
        cells.chunked(7).forEach{week->Row(horizontalArrangement=Arrangement.spacedBy(3.dp)){
            week.forEach { date ->
                if(date==null)Spacer(Modifier.weight(1f).height(40.dp))
                else Column(Modifier.weight(1f).height(40.dp).background(if(date==day)SkyBlue else Color.White,RoundedCornerShape(8.dp)).testTag("plan-day-$date").clickable{onDay(date)},horizontalAlignment=Alignment.CenterHorizontally){
                    Text(date.dayOfMonth.toString(),fontSize=12.sp)
                    Text(if(plans.any{it.day==date.toString()})"•" else "",fontSize=10.sp,color=PrimaryBlue)
                }
            }
        }}
    }
}

@Composable fun ScheduleTimeline(plans:List<Plan>,day:LocalDate,onOpen:(Plan)->Unit){
    val blocks=remember(plans,day){timelineBlocks(plans,day)}
    val lanes=(blocks.maxOfOrNull{it.lane} ?: 0)+1
    val density=androidx.compose.ui.platform.LocalDensity.current
    val scroll=rememberScrollState()
    LaunchedEffect(day){scroll.scrollTo(with(density){((blocks.minOfOrNull{it.start} ?: 480).coerceAtLeast(0)*1.2f).dp.roundToPx()})}
    BoxWithConstraints(Modifier.fillMaxWidth().height(420.dp).background(Color.White,RoundedCornerShape(16.dp)).verticalScroll(scroll).testTag("schedule-timeline")){
        val width=(maxWidth-50.dp)/lanes
        Box(Modifier.fillMaxWidth().height(1728.dp)){
            (0..23).forEach { hour ->
                Row(Modifier.offset(y=(hour*72).dp).fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text("%02d:00".format(hour),Modifier.width(50.dp),fontSize=10.sp,color=Muted)
                    HorizontalDivider(color=Color(0xFFEDF2F8))
                }
            }
            blocks.forEach { b ->
                Column(Modifier.offset(x=50.dp+width*b.lane,y=(b.start*1.2f).dp).width(width-3.dp).height(((b.end-b.start)*1.2f).dp.coerceAtLeast(36.dp))
                    .background(if(b.plan.completedAt==null)SkyBlue else Color(0xFFE9EEF4),RoundedCornerShape(7.dp)).testTag("schedule-${b.plan.id}").clickable{onOpen(b.plan)}.padding(5.dp)){
                    Text(b.plan.title,fontSize=12.sp,lineHeight=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(b.plan.expectedMinutes?.let{minutesText(it)} ?: "耗时待定",fontSize=10.sp,lineHeight=12.sp,color=PrimaryBlue)
                }
            }
        }
    }
}
