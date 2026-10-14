package com.self.daymark

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.zip.ZipInputStream
import java.time.LocalDate
import com.google.gson.GsonBuilder

class ExportTest {
    @Test fun currentBackupRoundTripPreservesEveryRatingIncludingNeutralAndUnknown() {
        val scores=listOf(null,-2,-1,0,1,2)
        val events=scores.mapIndexed { i,score -> Event(id="e$i",title="评分 $i",startAt=10,feeling=score,worth=score) }
        val data=LedgerData(events=events,forecasts=events.map { Forecast(eventId=it.id,expectedFeeling=it.feeling) })
        val json=Export.json(data)
        assertTrue(json.contains("\"schemaVersion\": 4"))
        assertEquals(data,Export.fromJson(json))
        val bytes=ByteArrayOutputStream().also{Export.writeZip(it,data)}.toByteArray()
        assertEquals(data,Export.readBackup(ByteArrayInputStream(bytes)))
        var eventsCsv="";var instructions=""
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while(true) {
            val entry=zip.nextEntry ?: break
            if(entry.name=="events.csv") eventsCsv=zip.readBytes().toString(Charsets.UTF_8)
            if(entry.name=="README.txt") instructions=zip.readBytes().toString(Charsets.UTF_8)
        } }
        assertTrue(eventsCsv.contains(",-2,-2,false,"))
        assertTrue(eventsCsv.contains(",0,0,false,"))
        assertTrue(instructions.contains("0中性"))
    }

    @Test fun versionOneBackupConvertsAllThreeRatingFieldsExactlyOnce() {
        val scores=listOf(null,1,2,3,4,5)
        val events=scores.mapIndexed { i,score -> Event(id="old$i",title="旧评分 $i",startAt=10,feeling=score,worth=score,deletedAt=if(i==1)20 else null) }
        val data=LedgerData(events=events,forecasts=events.map { Forecast(eventId=it.id,expectedFeeling=it.feeling) },cash=listOf(CashEntry(title="收入",cents=5000,kind="income",occurredAt=10)))
        val legacy=GsonBuilder().serializeNulls().create().toJson(Backup(schemaVersion=1,data=data))
        val restored=Export.fromJson(legacy)
        val expected=listOf(null,-2,-1,0,1,2)
        assertEquals(expected,restored.events.map{it.feeling})
        assertEquals(expected,restored.events.map{it.worth})
        assertEquals(expected,restored.forecasts.map{it.expectedFeeling})
        assertEquals(data.cash,restored.cash)
        assertEquals(20L,restored.events[1].deletedAt)
        assertEquals(restored,Export.fromJson(Export.json(restored)))
    }

    @Test fun invalidRatingsAreRejectedUsingTheDeclaredBackupVersion() {
        val gson=GsonBuilder().create()
        for (score in listOf(-2,0,6)) {
            val data=LedgerData(events=listOf(Event(title="旧数据",startAt=10,feeling=score)))
            assertThrows(IllegalArgumentException::class.java) { Export.fromJson(gson.toJson(Backup(schemaVersion=1,data=data))) }
        }
        val current=LedgerData(forecasts=listOf(Forecast(eventId="e",expectedFeeling=3)),events=listOf(Event(id="e",title="新数据",startAt=10)))
        assertThrows(IllegalArgumentException::class.java) { Export.fromJson(Export.json(current)) }
    }

    @Test fun dailyExportIncludesEveryDayTouchedByOvernightRecords() {
        val night=Time.parse("2026-10-10","23:00")
        val morning=Time.parse("2026-10-11","07:00")
        val data=LedgerData(sleeps=listOf(SleepEntry("2026-10-11","timed",night,morning)),
            events=listOf(Event(title="夜间工作",startAt=Time.parse("2026-10-12","23:30"),endAt=Time.parse("2026-10-13","00:30"))))
        assertEquals((10..13).map{LocalDate.of(2026,10,it)},Analytics.recordedDates(data))
        val bytes=ByteArrayOutputStream().also{Export.writeZip(it,data)}.toByteArray()
        var summary=""
        ZipInputStream(ByteArrayInputStream(bytes)).use{zip->while(true){val entry=zip.nextEntry?:break;if(entry.name=="daily_summary.csv")summary=zip.readBytes().toString(Charsets.UTF_8)}}
        assertTrue(summary.contains("\"2026-10-10\",true,0,0,0,0,60"))
        assertTrue(summary.contains("\"2026-10-13\",true,0,0,0,30,0"))
    }
    @Test fun deletedEventDoesNotExportRemovedCashAsZeroCost() {
        val event=Event(title="已删除",startAt=1,costKnown=true,deletedAt=2)
        val data=LedgerData(events=listOf(event))
        assertNull(data.cost(event))
        assertEquals(data,Export.fromJson(Export.json(data)))
    }
    @Test fun archiveRoundTripPreservesNullsUnicodeAndForecastVersions() {
        val event=Event(title="写作，\"想法\"",startAt=100000,experience="第一行\n第二行",status="planned")
        val f=Forecast(eventId=event.id,madeAt=1,minutes=45,prospective=true)
        val data=LedgerData(events=listOf(event),forecasts=listOf(f,f.copy(id=newId(),madeAt=2,minutes=60)),balances=listOf(BalanceAnchor(asOf=0,cents=10000)))
        val bytes=ByteArrayOutputStream().also{Export.writeZip(it,data)}.toByteArray()
        assertEquals(data,Export.readBackup(ByteArrayInputStream(bytes)))
        assertEquals(data,Export.fromJson(Export.json(data)))
        val names=mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use{zip->while(true){val entry=zip.nextEntry?:break;names+=entry.name}}
        assertTrue(names.containsAll(listOf("events.csv","cash.csv","sleep.csv","forecasts.csv","records.json","prediction_errors.csv","README.txt")))
    }
    @Test fun csvEscapesTextAndFormulaPrefixesButPreservesNumbers() {
        val csv=Export.csv(listOf("text","number","unknown"),listOf(listOf("=1+1",-123,null),listOf("a,\"b\"\n中文",0,null)))
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("\"'=1+1\",-123,"))
        assertTrue(csv.contains("\"a,\"\"b\"\"\n中文\""))
    }
    @Test fun malformedReferencesFailBeforeRestore() {
        val orphan=LedgerData(forecasts=listOf(Forecast(eventId="missing")))
        assertThrows(IllegalArgumentException::class.java){Export.validate(orphan)}
        val e=Event(title="散步",startAt=1,costKnown=true)
        assertThrows(IllegalArgumentException::class.java){Export.validate(LedgerData(events=listOf(e)))}
    }
    @Test fun sleepUnknownIsNeverTurnedIntoZeroDuration() {
        val sleep=SleepEntry("2026-10-12","unknown")
        val restored=Export.fromJson(Export.json(LedgerData(sleeps=listOf(sleep)))).sleeps.single()
        assertNull(restored.startAt);assertNull(restored.endAt);assertEquals("unknown",restored.status)
    }
}
