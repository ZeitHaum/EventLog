package com.self.daymark

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import java.time.LocalDate
import java.time.LocalTime

val PrimaryBlue = Color(0xFF386DA6)
val SkyBlue = Color(0xFFDDEDFC)
val CanvasColor = Color(0xFFF5F8FC)
val Ink = Color(0xFF283E55)
val Muted = Color(0xFF66788B)
val Clay = Color(0xFFB15E4B)
val Amber = Color(0xFFD09542)

@Composable fun DaymarkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary=PrimaryBlue, onPrimary=Color.White, primaryContainer=SkyBlue, onPrimaryContainer=Ink,
        secondary=PrimaryBlue, onSecondary=Color.White, secondaryContainer=SkyBlue, onSecondaryContainer=PrimaryBlue,
        background=CanvasColor, surface=Color(0xFFFFFFFF), onSurface=Ink, onBackground=Ink, surfaceVariant=Color(0xFFEDF0F7), onSurfaceVariant=Muted,
        outline=Color(0xFFCBD8E5), outlineVariant=Color(0xFFD8E2ED), error=Clay), content=content)
}

@Composable fun DaymarkNavigation(tab: Int, onTab: (Int) -> Unit, onNew: () -> Unit) {
    Surface(color=MaterialTheme.colorScheme.surface) {
        Column {
            if(tab==0) Button(onNew,Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp).heightIn(min=48.dp),shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.buttonColors(containerColor=SkyBlue,contentColor=PrimaryBlue)) {
                Text("＋  记一件事",fontSize=16.sp)
            }
            NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
                val labels=listOf("今天","日历","趋势","预测","数据")
                val glyphs=listOf("◷","▦","↗","◎","≡")
                labels.forEachIndexed { i,label -> NavigationBarItem(selected=tab==i,onClick={onTab(i)},icon={Text(glyphs[i],fontSize=22.sp)},label={Text(label,fontSize=11.sp)}) }
            }
        }
    }
}

@Composable fun Panel(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surface, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),color=color) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}
@Composable fun Eyebrow(text: String) = Text(text,color=PrimaryBlue,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp)
@Composable fun Label(text: String, modifier: Modifier = Modifier) = Text(text,modifier,color=Muted,fontSize=12.sp,lineHeight=19.sp)
@Composable fun Title(text: String, modifier: Modifier = Modifier) = Text(text,modifier,fontSize=19.sp,fontWeight=FontWeight.Bold,color=Ink)
@Composable fun Metric(value: String, label: String, modifier: Modifier = Modifier, color: Color = Ink) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(3.dp)) { Text(value,fontSize=22.sp,fontWeight=FontWeight.SemiBold,color=color); Label(label) }
}
@Composable fun EmptyCard(title: String, message: String) = Panel { Title(title); Label(message) }

@Composable fun Input(label: String, value: String, onChange: (String)->Unit, modifier: Modifier=Modifier, numeric: Boolean=false, lines: Int=1) {
    OutlinedTextField(value,onChange,modifier.fillMaxWidth(),label={ Text(label,fontSize=13.sp) },singleLine=lines==1,minLines=lines,
        keyboardOptions=KeyboardOptions(keyboardType=if(numeric) KeyboardType.Decimal else KeyboardType.Text),shape=RoundedCornerShape(13.dp))
}

@Composable fun DateControl(day: LocalDate, onChange: (LocalDate)->Unit, modifier: Modifier=Modifier, label: String="日期", display: String?=null) {
    val context=LocalContext.current
    OutlinedButton(onClick={ DatePickerDialog(context,{ _,y,m,d -> onChange(LocalDate.of(y,m+1,d)) },day.year,day.monthValue-1,day.dayOfMonth).show() },modifier=modifier,
        shape=RoundedCornerShape(13.dp),contentPadding=PaddingValues(horizontal=12.dp,vertical=12.dp)) { Text(display ?: "$label  $day",fontSize=13.sp) }
}
@Composable fun ClockControl(clock: String, onChange: (String)->Unit, modifier: Modifier=Modifier, label:String="时间") {
    val context=LocalContext.current
    val time=runCatching { LocalTime.parse(clock) }.getOrDefault(LocalTime.NOON)
    OutlinedButton(onClick={ TimePickerDialog(context,{ _,h,m -> onChange("%02d:%02d".format(h,m)) },time.hour,time.minute,true).show() },modifier=modifier,
        shape=RoundedCornerShape(13.dp),contentPadding=PaddingValues(horizontal=12.dp,vertical=12.dp)) { Text("$label  $clock",fontSize=13.sp) }
}
@Composable fun DaySelector(day: LocalDate, onChange: (LocalDate)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        TextButton({onChange(day.minusDays(1))},Modifier.semantics { contentDescription="前一天" }) { Text("‹",fontSize=24.sp) }
        DateControl(day,onChange,label=if(day==LocalDate.now()) "今天" else "")
        TextButton({onChange(day.plusDays(1))},Modifier.semantics { contentDescription="后一天" }) { Text("›",fontSize=24.sp) }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun Choices(values: List<String>, selected: String, onChange: (String)->Unit) {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(7.dp)) { values.forEach { value -> FilterChip(selected==value,{onChange(value)},label={Text(value,fontSize=12.sp)}) } }
}
@Composable fun Rating(label: String, value: Int?, onChange: (Int?)->Unit) {
    Column { Label("$label · 再点可清空")
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            ExperienceScale.values.forEach { n ->
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                    FilterChip(value==n,{onChange(if(value==n)null else n)},
                        modifier=Modifier.fillMaxWidth().testTag("rating-$label-$n").semantics { contentDescription="$label：$n，${ExperienceScale.label(n)}" },
                        label={Text(n.toString(),Modifier.fillMaxWidth(),textAlign=TextAlign.Center)})
                    Text(ExperienceScale.label(n),color=if(value==n)PrimaryBlue else Muted,fontSize=10.sp,lineHeight=14.sp,textAlign=TextAlign.Center)
                }
            }
        }
    }
}
@Composable fun ConfirmDialog(title:String,text:String,onDismiss:()->Unit,onConfirm:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={Text(text)},confirmButton={TextButton(onConfirm){Text("确认")}},dismissButton={TextButton(onDismiss){Text("取消")}})
}
