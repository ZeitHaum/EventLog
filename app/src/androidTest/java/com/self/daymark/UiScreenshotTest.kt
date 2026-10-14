package com.self.daymark

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

class UiScreenshotTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val day=LocalDate.now().minusDays(1)
    // UI fixtures never enter the user's database.
    private fun fixture():LedgerData {
        val e=Event(id="reading",title="读书与整理想法",startAt=Time.recordAt(day),actualMinutes=45,
            description="读完一章，整理了两个问题。",feeling=2)
        return LedgerData(events=listOf(e),cash=listOf(CashEntry(title="午餐",cents=-2500,kind="expense",occurredAt=e.startAt)),
            sleeps=listOf(SleepEntry(day.toString(),"timed",Time.start(day)-3600000,Time.start(day)+7*3600000)),
            balances=listOf(BalanceAnchor(asOf=Time.start(day.minusDays(10)),cents=100000)),
            plans=listOf(Plan(id="write",title="写作与整理笔记",day=day.toString(),period="morning",expectedMinutes=45),
                Plan(id="exercise",title="散步",day=day.toString(),startMinute=18*60,expectedMinutes=30),
                Plan(id="meeting",title="讨论想法",day=day.toString(),startMinute=18*60+15,expectedMinutes=45),
                Plan(id="backlog",title="找一本想读的书")))
    }
    @Test fun navigationCalendarTrendsAndPlanningScreensWork() {
        val data=fixture();var opened:LocalDate?=null
        compose.activityRule.scenario.onActivity{a->a.setContent{DaymarkTheme{
            var tab by remember{mutableIntStateOf(0)}
            Scaffold(bottomBar={DaymarkNavigation(tab,{tab=it},{})}){padding->Box(Modifier.fillMaxSize().padding(padding)){
                when(tab){
                    0->TodayScreen(data,day,{}, {}, {}, {}, {}, {})
                    1->PlansScreen(data,day,{}, {}, {}, {}, {}, {})
                    2->CalendarScreen(data,day,{opened=it})
                    3->TrendsScreen(data,{})
                    4->DataScreen(data,{}, {}, {}, {},false,{})
                }
            }}
        }}}
        screenshot("20-today.png")
        compose.onNodeWithText("计划").performClick()
        compose.onNodeWithText("我的一天").assertIsDisplayed()
        screenshot("21-my-day.png")
        compose.onNodeWithText("按时段分组").performClick()
        compose.onNodeWithText("上午",substring=false).assertExists()
        compose.onNodeWithText("日程").performClick()
        compose.onNodeWithTag("schedule-timeline").performScrollTo().assertIsDisplayed()
        screenshot("22-schedule.png")
        compose.onNodeWithTag("schedule-exercise").assertExists()
        compose.onNodeWithTag("plans-list").performScrollToIndex(0)
        compose.onNodeWithText("待安排").performClick()
        compose.onNodeWithText("找一本想读的书").assertIsDisplayed()
        compose.onNodeWithText("日历").performClick()
        compose.onNodeWithText("本月已记录支出").assertIsDisplayed()
        compose.onNodeWithTag("calendar-day-$day").performScrollTo().performClick()
        compose.runOnIdle{assertEquals(day,opened)}
        compose.onNodeWithText("活动").performClick().assertIsSelected()
        screenshot("23-calendar.png")
        compose.onNodeWithText("趋势").performClick()
        compose.onNodeWithText("月").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("资金余额趋势").assertExists()
        screenshot("24-trends.png")
        compose.onNodeWithText("数据").performClick()
        compose.onNodeWithText("导出全部数据 · ZIP").assertIsDisplayed()
    }
    @Test fun simplifiedEditorMergesLegacyTextAndSavesDurationZeroCostAndNegativeRating() {
        val source=fixture().events.single().copy(description="原来的描述",experience="原来的体验",actualMinutes=null,endAt=Time.recordAt(day)+3600000)
        var saved:Event?=null;var cost:Long?=null
        compose.activityRule.scenario.onActivity{a->a.setContent{DaymarkTheme{EventEditor(source,null,null,day,{}, {e,c,_,_->saved=e;cost=c},{})}}}
        compose.onNodeWithText("计划中").assertDoesNotExist()
        compose.onNodeWithText("原来的描述\n\n原来的体验").assertExists()
        compose.onNodeWithText("实际耗时 / 分钟").performTextReplacement("90")
        compose.onNodeWithText("支出 / 元").performTextReplacement("0")
        compose.onNodeWithTag("rating-当时体验--2").performScrollTo().performClick().assertIsSelected()
        screenshot("25-record-editor.png")
        compose.onNodeWithText("保存").assertIsDisplayed().performClick()
        compose.runOnIdle{
            assertEquals(90L,saved?.actualMinutes);assertNull(saved?.endAt);assertEquals(0L,cost)
            assertEquals(-2,saved?.feeling);assertEquals("原来的描述\n\n原来的体验",saved?.description)
        }
    }
    @Test fun newCashDefaultsToExpenseAndAllowsSwitchToIncome() {
        var saved:CashEntry?=null
        compose.activityRule.scenario.onActivity{a->a.setContent{DaymarkTheme{CashEditor(null,day,{}, {c,_->saved=c},{})}}}
        compose.onNodeWithText("支出").assertIsSelected()
        compose.onNodeWithText("名称 *").performTextInput("午餐")
        compose.onNodeWithText("金额 / 元 *").performTextInput("25")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle{assertEquals(-2500L,saved?.cents);assertEquals("expense",saved?.kind)}
        compose.onNodeWithText("收入").performClick().assertIsSelected()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle{assertEquals(2500L,saved?.cents)}
    }
    @Test fun planEditorSupportsNameOnlyAndOptionalPeriodAndDuration() {
        var saved:Plan?=null
        compose.activityRule.scenario.onActivity{a->a.setContent{DaymarkTheme{PlanEditor(null,null,{}, {saved=it},{})}}}
        compose.onNodeWithText("事项名称 *").performTextInput("散步")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle{assertEquals("散步",saved?.title);assertNull(saved?.day);assertNull(saved?.expectedMinutes)}
        compose.onNodeWithText("＋ 安排日期").performClick()
        compose.onNodeWithText("下午").performClick().assertIsSelected()
        compose.onNodeWithText("预计耗时 / 分钟（选填）").performScrollTo().performTextInput("30")
        screenshot("26-plan-editor.png")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle{assertEquals("afternoon",saved?.period);assertEquals(30L,saved?.expectedMinutes);assertNull(saved?.startMinute)}
    }
    @Test fun completeAndUndoControlsGiveFeedbackAndOpenActualRecord() {
        var completed=false;var undone=false;var opened=false
        compose.activityRule.scenario.onActivity{a->a.setContent{DaymarkTheme{
            var data by remember{mutableStateOf(fixture())}
            PlansScreen(data,day,{}, {}, {p->completed=true;data=data.copy(plans=data.plans.map{if(it.id==p.id)it.copy(eventId="test",completedAt=1)else it})},
                {undone=true}, {opened=true}, {})
        }}}
        compose.onNodeWithTag("complete-write").performClick()
        compose.onNodeWithText("1 / 3 已完成").assertExists()
        compose.onNodeWithText("补充记录").performScrollTo().performClick()
        compose.onNodeWithTag("undo-write").performScrollTo().performClick()
        compose.onNodeWithText("确认").performClick()
        compose.runOnIdle{assertTrue(completed&&undone&&opened)}
    }
    @Test fun sleepUnknownCanBeSavedWithoutTimes() {
        var saved:SleepEntry?=null
        compose.activityRule.scenario.onActivity{a->a.setContent{DaymarkTheme{SleepEditor(day,null,{}, {saved=it})}}}
        compose.onNodeWithText("时间记不清").performClick().assertIsSelected()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle{assertEquals("unknown",saved?.status);assertNull(saved?.startAt);assertNull(saved?.endAt)}
    }
    @Test fun completionFeedbackOffersEditingWithoutOpeningAForm() {
        var opened:String?=null
        compose.activityRule.scenario.onActivity{a->a.setContent{DaymarkTheme{
            val snack=remember{SnackbarHostState()}
            var id by remember{mutableStateOf<String?>("event")}
            CompletionFeedback(id,snack,{opened=it},{id=null})
            Scaffold(snackbarHost={SnackbarHost(snack)}){padding->Box(Modifier.padding(padding))}
        }}}
        compose.onNodeWithText("✓ 又完成了一件事").assertIsDisplayed()
        compose.onNodeWithText("编辑记录").assertDoesNotExist()
        compose.onNodeWithText("补充记录").performClick()
        compose.runOnIdle{assertEquals("event",opened)}
    }
    private fun screenshot(name:String){
        compose.waitForIdle()
        val folder=File(compose.activity.getExternalFilesDir(null),"verification").apply{mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        File(folder,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
