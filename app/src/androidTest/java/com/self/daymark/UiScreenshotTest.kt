package com.self.daymark

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
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
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val day = LocalDate.now().minusDays(1)

    // Fixtures are rendered directly; no sample records enter daymark.db.
    private fun fixture(): LedgerData {
        val events=mutableListOf<Event>();val cash=mutableListOf<CashEntry>()
        val sleeps=mutableListOf<SleepEntry>();val forecasts=mutableListOf<Forecast>()
        val resolutions=mutableListOf<Resolution>()
        for(i in 0..11) {
            val d=day.minusDays(i.toLong())
            val start=Time.start(d)+10*3600000
            val e=Event(id="sample-$i",title=if(i==0)"读书与整理想法" else "学习与日常记录",category="学习",startAt=start,
                endAt=start+(60+i%3*15)*60000,status="done",costKnown=true,feeling=i%3,
                experience="读完一章，整理了两条值得继续思考的问题。",firstOutcomeAt=start+2*3600000)
            events.add(e)
            cash.add(CashEntry(eventId=e.id,title=e.title,cents=-(2500L+i*300),kind="expense",occurredAt=start))
            sleeps.add(SleepEntry(d.toString(),"timed",Time.start(d)-90*60000,Time.start(d)+7*3600000))
            val f=Forecast(eventId=e.id,madeAt=start-3600000,minutes=45,costCents=2000,expectedFeeling=1,
                question="当天能否完成阅读并写下两条笔记？",probability=75,deadlineAt=Time.start(d.plusDays(1))-1,prospective=true)
            forecasts.add(f);resolutions.add(Resolution(f.id,i%4!=0,Time.start(d.plusDays(1))))
        }
        val dinner=Event(id="sample-dinner",title="和朋友一起吃晚饭",category="社交",startAt=Time.start(day)+19*3600000,
            endAt=Time.start(day)+20*3600000,costKnown=true,feeling=2,experience="聊得很放松，也听到了一个新的想法。")
        events.add(dinner);cash.add(CashEntry(eventId=dinner.id,title=dinner.title,cents=-4200,kind="expense",occurredAt=dinner.startAt))
        cash.add(CashEntry(title="项目收入",cents=180000,kind="income",occurredAt=Time.start(day.minusDays(3))))
        return LedgerData(events,cash,sleeps,forecasts,resolutions,listOf(BalanceAnchor(asOf=Time.start(day.minusDays(12)),cents=1200000)))
    }

    @Test fun sampleScreensAndCalendarSelectionWork() {
        val data=fixture()
        var opened:LocalDate?=null
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DaymarkTheme {
            var tab by remember { mutableIntStateOf(0) }
            Scaffold(containerColor=CanvasColor,bottomBar={DaymarkNavigation(tab,{tab=it},{})}) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when(tab) {
                        0->TodayScreen(data,day,{}, {}, {}, {}, {}, {})
                        1->CalendarScreen(data,day,{opened=it})
                        2->TrendsScreen(data,{})
                        3->ForecastScreen(data,{}, {_,_->})
                        4->DataScreen(data,{}, {}, {}, {},false,{})
                    }
                }
            }
        } } }
        saveScreenshot("02-today-sample.png")
        compose.onNodeWithText("日历").performClick()
        compose.onNodeWithText("本月已记录支出").assertIsDisplayed()
        saveScreenshot("03-calendar-money.png")
        compose.onNodeWithText("活动").performClick().assertIsSelected()
        saveScreenshot("04-calendar-time.png")
        compose.onNodeWithText("睡眠").performClick().assertIsSelected()
        saveScreenshot("05-calendar-sleep.png")
        compose.onNodeWithTag("calendar-day-$day").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(day,opened) }
        compose.onNodeWithText("趋势").performClick()
        compose.onNodeWithText("月").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("资金余额趋势").assertExists()
        saveScreenshot("06-trends-month.png")
        compose.onNodeWithText("年").performClick().assertIsSelected()
        compose.onNodeWithText("预测").performClick()
        compose.onNodeWithText("概率判断").performScrollTo().assertIsDisplayed()
        saveScreenshot("07-forecast.png")
        compose.onNodeWithText("数据").performClick()
        compose.onNodeWithText("导出全部数据 · ZIP").assertIsDisplayed()
        saveScreenshot("08-data.png")
    }

    @Test fun editorSavesAnExplicitZeroCostAndActualDuration() {
        val data=fixture();val source=data.events.first()
        var saved:Pair<Event,Long?>?=null
        compose.activityRule.scenario.onActivity { activity->activity.setContent { DaymarkTheme {
            EventEditor(source,data.cost(source),data.latestForecast(source.id),day,{}, {e,c,_->saved=e to c},{})
        } } }
        saveScreenshot("09-event-editor.png")
        compose.onNodeWithText("用时 / 分钟").performScrollTo().performTextReplacement("90")
        compose.onNodeWithText("支出 / 元").performScrollTo().performTextReplacement("0")
        compose.onNodeWithText("保存").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        saveScreenshot("11-event-keyboard.png")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertNotNull(saved);assertEquals(0L,saved!!.second)
            assertEquals(90L,(saved!!.first.endAt!!-saved!!.first.startAt)/60000)
        }
    }

    @Test fun sleepUnknownCanBeConfirmedWithoutInventingTimes() {
        var saved:SleepEntry?=null
        compose.activityRule.scenario.onActivity { activity->activity.setContent { DaymarkTheme {
            SleepEditor(day,null,{}, {saved=it})
        } } }
        compose.onNodeWithText("时间记不清").performClick().assertIsSelected()
        saveScreenshot("10-sleep-editor.png")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals("unknown",saved?.status);assertNull(saved?.startAt);assertNull(saved?.endAt) }
    }

    @Test fun experienceChoicesSaveNegativeNeutralAndExpectedRatingsAndCanBeCleared() {
        val source=fixture().events.first().copy(feeling=null,worth=null)
        var saved:Event?=null
        var savedForecast:Forecast?=null
        compose.activityRule.scenario.onActivity { activity->activity.setContent { DaymarkTheme {
            EventEditor(source,null,null,day,{}, {e,_,f->saved=e;savedForecast=f},{})
        } } }
        compose.onNodeWithTag("rating-当时体验--2").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithContentDescription("当时体验：-2，非常不值").assertIsDisplayed()
        compose.onNodeWithContentDescription("当时体验：2，非常值得").assertIsDisplayed()
        saveScreenshot("12-experience-rating.png")
        compose.onNodeWithTag("rating-当时体验--2").performClick().assertIsNotSelected()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertNotNull(saved);assertNull(saved!!.feeling) }
        compose.onNodeWithTag("rating-当时体验--2").performClick()
        compose.onNodeWithText("＋ 添加预估与预测（选填）").performScrollTo().performClick()
        compose.onNodeWithTag("rating-期待的体验-2").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("rating-回头看，是否值得-0").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(-2,saved?.feeling)
            assertEquals(0,saved?.worth)
            assertEquals(2,savedForecast?.expectedFeeling)
        }
    }

    private fun saveScreenshot(name:String) {
        compose.waitForIdle()
        val folder=File(compose.activity.getExternalFilesDir(null),"verification").apply{mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        requireNotNull(bitmap)
        File(folder,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}
