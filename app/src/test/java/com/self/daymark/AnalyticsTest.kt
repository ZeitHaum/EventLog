package com.self.daymark

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.*
import java.util.TimeZone

class AnalyticsTest {
    private val day=LocalDate.parse("2026-10-12")
    private fun at(clock:String,date:LocalDate=day)=Time.parse(date.toString(),clock)
    @Before fun zone(){TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))}
    @Test fun overlappingActivitiesCountElapsedTimeOnce() {
        val a=Event(title="工作",startAt=at("09:00"),endAt=at("10:00"))
        val b=Event(title="阅读",startAt=at("09:30"),endAt=at("10:30"))
        val s=Analytics.daily(LedgerData(events=listOf(a,b)),day)
        assertEquals(90L,s.activityMinutes);assertEquals(2,s.unknownCosts)
    }
    @Test fun intervalsSplitAtMidnightWithoutLosingMinutes() {
        val event=Event(title="写作",startAt=at("23:30",day.minusDays(1)),endAt=at("00:30"))
        val sleep=SleepEntry(day.toString(),"timed",at("23:00",day.minusDays(1)),at("07:00"))
        val data=LedgerData(events=listOf(event),sleeps=listOf(sleep))
        assertEquals(30L,Analytics.daily(data,day.minusDays(1)).activityMinutes)
        assertEquals(30L,Analytics.daily(data,day).activityMinutes)
        assertEquals(60L,Analytics.daily(data,day.minusDays(1)).sleepMinutes)
        assertEquals(420L,Analytics.daily(data,day).sleepMinutes)
        assertTrue(Analytics.daily(data,day).sleepConfirmed)
    }
    @Test fun unknownValuesStayDifferentFromExplicitZero() {
        assertNull(parseMoney(""));assertEquals(0L,parseMoney("0"))
        assertNull(parseMinutes(""));assertEquals(0L,parseMinutes("0"))
        val event=Event(title="散步",startAt=at("08:00"))
        val data=LedgerData(events=listOf(event))
        assertNull(data.cost(event));assertEquals(1,Analytics.daily(data,day).unknownDurations)
        assertNull(Analytics.balanceAt(data,at("12:00")))
        assertFalse(Analytics.daily(data,day.minusDays(1)).anyData)
    }
    @Test fun currencyUsesExactCentsAndRejectsFractionalCents() {
        assertEquals(29L,parseMoney("0.29"));assertEquals(-105L,parseMoney("-1.05",true))
        assertThrows(IllegalArgumentException::class.java){parseMoney("1.001")}
        assertThrows(IllegalArgumentException::class.java){parseMoney("NaN")}
        assertThrows(IllegalArgumentException::class.java){parseMoney("-3")}
    }
    @Test fun fundsDependOnActualLedgerNotPredictions() {
        val event=Event(title="计划旅行",startAt=at("16:00"),status="planned")
        val data=LedgerData(events=listOf(event),forecasts=listOf(Forecast(eventId=event.id,costCents=999999)),
            balances=listOf(BalanceAnchor(asOf=at("00:00"),cents=100000)),
            cash=listOf(CashEntry(title="午饭",cents=-2500,kind="expense",occurredAt=at("12:00")),CashEntry(title="收入",cents=20000,kind="income",occurredAt=at("08:00"))))
        assertEquals(117500L,Analytics.balanceAt(data,at("23:00")))
        assertNull(Analytics.balanceAt(data,at("00:00")-1))
    }
    @Test fun newBalanceAnchorDoesNotDoubleCountEarlierFlows() {
        val data=LedgerData(balances=listOf(BalanceAnchor(asOf=at("00:00"),cents=100000),BalanceAnchor(asOf=at("12:00"),cents=120000)),cash=listOf(CashEntry(title="早饭",cents=-1000,kind="expense",occurredAt=at("08:00")),CashEntry(title="晚饭",cents=-2000,kind="expense",occurredAt=at("18:00"))))
        assertEquals(99000L,Analytics.balanceAt(data,at("11:00")))
        assertEquals(118000L,Analytics.balanceAt(data,at("23:00")))
    }
    @Test fun retrospectiveAndLateForecastsNeverImprovePastScore() {
        val event=Event(title="写作",startAt=at("10:00"),endAt=at("11:00"),firstOutcomeAt=at("11:10"))
        val early=Forecast(eventId=event.id,madeAt=at("09:00"),minutes=30,prospective=true)
        val revision=early.copy(id=newId(),madeAt=at("09:30"),minutes=50)
        val late=early.copy(id=newId(),madeAt=at("11:00"),minutes=60)
        val retrospective=early.copy(id=newId(),madeAt=at("08:00"),minutes=60,prospective=false)
        val data=LedgerData(events=listOf(event),forecasts=listOf(revision,late,retrospective,early))
        val result=Analytics.errors(data).single()
        assertEquals(early.id,result.forecast.id);assertEquals(30L,result.minutesError)
        assertNull(result.costError)
    }
    @Test fun probabilityVersionsDoNotInflateSampleCount() {
        val event=Event(title="写作",startAt=at("10:00"),status="cancelled",firstOutcomeAt=at("11:00"))
        val first=Forecast(eventId=event.id,madeAt=at("08:00"),question="今天完成？",probability=70,deadlineAt=at("23:00"),prospective=true)
        val second=first.copy(id=newId(),madeAt=at("09:00"),probability=50)
        val data=LedgerData(events=listOf(event),forecasts=listOf(first,second),resolutions=listOf(Resolution(first.id,false,at("23:30")),Resolution(second.id,false,at("23:30"))))
        assertEquals(1,Analytics.probabilityRows(data).size)
        assertEquals(.49,Analytics.probabilityRows(data).single().brier,.00001)
        assertTrue(Analytics.errors(data).isEmpty())
    }
    @Test fun calendarHandlesLeapYearsAndMondayStart() {
        assertEquals(29,Analytics.calendarDays(YearMonth.of(2024,2)).count{it!=null})
        assertEquals(LocalDate.of(2026,6,1),Analytics.calendarDays(YearMonth.of(2026,6)).first())
        assertEquals(6,Analytics.calendarDays(YearMonth.of(2026,2)).takeWhile{it==null}.size)
    }
    @Test fun plannedEventsDoNotContributeActualTimeOrCost() {
        val event=Event(title="未来",startAt=at("15:00"),status="planned")
        val result=Analytics.daily(LedgerData(events=listOf(event)),day)
        assertEquals(0L,result.activityMinutes);assertEquals(0,result.eventCount)
        assertThrows(IllegalArgumentException::class.java){event.copy(endAt=at("16:00")).validate()}
    }
}
