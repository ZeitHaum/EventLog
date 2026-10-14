package com.self.daymark

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

class PlanningTest {
    private val day=LocalDate.of(2026,10,12)
    @Test fun durationOnlyRecordsDoNotInventIntervalsOrTurnUnknownIntoZero() {
        val events=listOf(Event(title="未知",startAt=Time.start(day)),Event(title="零",startAt=Time.start(day),actualMinutes=0),
            Event(title="耗时",startAt=Time.start(day),actualMinutes=90),
            Event(title="旧跨日",startAt=Time.start(day)-1800000,endAt=Time.start(day)+1800000))
        val summary=Analytics.daily(LedgerData(events=events),day)
        assertEquals(120L,summary.activityMinutes)
        assertEquals(1,summary.unknownDurations)
        assertNull(events[2].endAt)
        assertEquals(30L,Analytics.daily(LedgerData(events=events),day.minusDays(1)).activityMinutes)
    }
    @Test fun legacyBackupCreatesPlansWithoutChangingHistoricalEventsOrScores() {
        val event=Event(id="old",title="旧计划",startAt=Time.parse(day.toString(),"13:30"),status="planned",description="做什么",experience="体验")
        val data=LedgerData(events=listOf(event),forecasts=listOf(Forecast(eventId=event.id,minutes=50)))
        val tree=JsonParser.parseString(Export.json(data)).asJsonObject
        tree.addProperty("schemaVersion",2);tree.getAsJsonObject("data").remove("plans")
        val imported=Export.fromJson(tree.toString())
        assertEquals(data.events,imported.events);assertEquals(data.forecasts,imported.forecasts)
        val plan=imported.plans.single()
        assertEquals("legacy-old",plan.id);assertEquals(810,plan.startMinute);assertEquals(50L,plan.expectedMinutes)
        assertEquals("做什么\n\n体验",plan.notes)
        assertEquals(imported,Export.fromJson(Export.json(imported)))
    }
    @Test fun newBackupPreservesCompletedLinksDurationsAndUnscheduledPlans() {
        val e=Event(title="完成",startAt=Time.start(day),actualMinutes=0)
        val data=LedgerData(events=listOf(e),plans=listOf(Plan(title="完成",eventId=e.id,completedAt=20,expectedMinutes=25),Plan(title="待安排")))
        val bytes=ByteArrayOutputStream().also{Export.writeZip(it,data)}.toByteArray()
        assertEquals(data,Export.readBackup(ByteArrayInputStream(bytes)))
        assertThrows(IllegalArgumentException::class.java){Export.validate(data.copy(events=emptyList()))}
        assertThrows(IllegalArgumentException::class.java){Export.validate(data.copy(plans=listOf(data.plans.first().copy(completedAt=null))))}
    }
    @Test fun timelineSeparatesOverlapsAndIncludesOvernightPlansWithoutCountingThemAsActivity() {
        val plans=listOf(Plan(title="甲",day=day.toString(),startMinute=600,expectedMinutes=60),
            Plan(title="乙",day=day.toString(),startMinute=630,expectedMinutes=60),
            Plan(title="丙",day=day.minusDays(1).toString(),startMinute=1410,expectedMinutes=60))
        val blocks=timelineBlocks(plans,day)
        assertEquals(3,blocks.size)
        assertEquals(0,blocks.first().start);assertEquals(30,blocks.first().end)
        assertNotEquals(blocks[1].lane,blocks[2].lane)
        assertEquals(0L,Analytics.daily(LedgerData(plans=plans),day).activityMinutes)
    }
}
