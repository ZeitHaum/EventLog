package com.self.daymark

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun recordsSurviveReopenAndRestoreWithoutTouchingUserDatabase() = runBlocking {
        val name="verification-${System.nanoTime()}.db"
        var db=Room.databaseBuilder(context,LedgerDatabase::class.java,name).build()
        try {
            val at=System.currentTimeMillis()-3600000
            val event=Event(title="验证记录",startAt=at,endAt=at+600000)
            LedgerRepository(db).saveEvent(event,1250,null)
            val snapshot=Export.fromJson(Export.json(db.dao().dump()))
            db.close()
            db=Room.databaseBuilder(context,LedgerDatabase::class.java,name).build()
            assertEquals(snapshot,db.dao().dump())
            val repo=LedgerRepository(db)
            repo.deleteEvent(snapshot.events.single())
            assertTrue(db.dao().cash().isEmpty())
            repo.restore(snapshot)
            assertEquals(snapshot,db.dao().dump())
            val invalid=snapshot.copy(cash=emptyList())
            try { repo.restore(invalid); fail("Invalid restore must fail") } catch (_:IllegalArgumentException) {}
            assertEquals(snapshot,db.dao().dump())
        } finally { db.close();context.deleteDatabase(name) }
    }

    @Test fun eventEditsRefreshObserversEvenWhenRowCountIsUnchanged() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,LedgerDatabase::class.java).build()
        val updates=Channel<LedgerData>(Channel.UNLIMITED)
        val job=launch(Dispatchers.IO) { db.dao().changes().map{db.dao().dump()}.collect{updates.send(it)} }
        suspend fun awaitTitle(title:String) = withTimeout(5000) {
            while(updates.receive().activeEvents.singleOrNull()?.title!=title) { /* Wait for the matching invalidation. */ }
        }
        try {
            withTimeout(5000){updates.receive()}
            val repo=LedgerRepository(db)
            val event=Event(title="旧标题",startAt=System.currentTimeMillis()-600000)
            repo.saveEvent(event,null,null)
            awaitTitle("旧标题")
            repo.saveEvent(event.copy(title="新标题"),null,null)
            awaitTitle("新标题")
            assertEquals(1,db.dao().events().size)
        } finally { job.cancelAndJoin();updates.close();db.close() }
    }

    @Test fun forecastVersionsAreAppendOnlyAndRetrospectiveEditsCannotReplaceBaseline() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,LedgerDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db)
            val now=System.currentTimeMillis()
            val event=Event(title="未来的事情",startAt=now+3600000,status="planned")
            val forecast=Forecast(eventId=event.id,minutes=30,costCents=1000)
            repo.saveEvent(event,null,forecast)
            val first=db.dao().forecasts().single()
            repo.saveEvent(event,null,forecast)
            assertEquals(1,db.dao().forecasts().size)
            repo.saveEvent(event,null,forecast.copy(minutes=45))
            val versions=db.dao().forecasts()
            assertEquals(2,versions.size)
            assertEquals(first,versions.first())
            repo.saveEvent(event.copy(status="done",startAt=now-3600000,endAt=now),1500,forecast.copy(minutes=60))
            val saved=db.dao().dump()
            assertFalse(saved.forecasts.last().prospective)
            // Changing actual start to before the estimates invalidates those estimates.
            assertTrue(Analytics.errors(saved).isEmpty())
            assertEquals(first,saved.forecasts.first())
        } finally { db.close() }
    }

    @Test fun overnightSleepChangesInvalidateBothDaysAndMissingSleepBlocksClosing() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,LedgerDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db)
            val day=LocalDate.now().minusDays(1)
            try { repo.closeDay(day);fail("Missing sleep must block closing") } catch (_:IllegalArgumentException) {}
            repo.saveSleep(SleepEntry(day.minusDays(1).toString(),"unknown"))
            val sleep=SleepEntry(day.toString(),"timed",Time.start(day)-3600000,Time.start(day)+7*3600000)
            repo.saveSleep(sleep)
            repo.closeDay(day.minusDays(1));repo.closeDay(day)
            assertEquals(2,db.dao().checks().size)
            repo.saveSleep(sleep.copy(startAt=sleep.startAt!!-3600000))
            assertTrue(db.dao().checks().isEmpty())
        } finally { db.close() }
    }
}
