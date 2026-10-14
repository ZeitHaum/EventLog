package com.self.daymark

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PlanningPersistenceTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()

    @Test fun completionUndoAndRedoAreIdempotentAndKeepEditedExperienceAndCash() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,LedgerDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db)
            val plan=Plan(title="散步",day=LocalDate.now().toString(),expectedMinutes=60)
            repo.savePlan(plan)
            val ids=coroutineScope { (1..3).map{async(Dispatchers.IO){repo.completePlan(plan.id)}}.awaitAll() }
            assertEquals(1,ids.distinct().size)
            var data=db.dao().dump()
            assertEquals(1,data.activeEvents.size);assertNull(data.activeEvents.single().durationMinutes)
            assertEquals(0L,Analytics.daily(data,LocalDate.now()).activityMinutes)
            val event=data.activeEvents.single().copy(actualMinutes=45,description="很放松",feeling=2)
            repo.saveEvent(event,1200,null)
            repo.undoPlan(plan.id);repo.undoPlan(plan.id)
            data=db.dao().dump()
            assertTrue(data.activeEvents.isEmpty());assertTrue(data.cash.isEmpty());assertNull(data.plans.single().completedAt)
            val backup=Export.fromJson(Export.json(data))
            repo.restore(backup)
            repo.completePlan(plan.id)
            data=db.dao().dump()
            assertEquals("很放松",data.activeEvents.single().description);assertEquals(45L,data.activeEvents.single().durationMinutes)
            assertEquals(1200L,data.cost(data.activeEvents.single()));assertEquals(1,data.cash.size)
            repo.completePlan(plan.id)
            assertEquals(data,db.dao().dump())
            repo.deleteEvent(data.activeEvents.single())
            assertNull(db.dao().plans().single().completedAt)
            assertTrue(db.dao().cash().isEmpty())
            Export.validate(db.dao().dump())
        } finally{db.close()}
    }

    @Test fun reschedulingDeletionAndRestoreKeepLinksAndKnownZero() = runBlocking {
        val name="planning-${System.nanoTime()}.db"
        var db=Room.databaseBuilder(context,LedgerDatabase::class.java,name).build()
        try {
            var repo=LedgerRepository(db)
            val plan=Plan(title="待安排")
            repo.savePlan(plan)
            repo.savePlan(plan.copy(day=LocalDate.now().plusDays(1).toString(),period="afternoon",expectedMinutes=30))
            assertEquals("afternoon",db.dao().plans().single().period)
            repo.completePlan(plan.id,0)
            val backup=Export.fromJson(Export.json(db.dao().dump()))
            db.close();db=Room.databaseBuilder(context,LedgerDatabase::class.java,name).build();repo=LedgerRepository(db)
            assertEquals(backup,db.dao().dump());assertEquals(0L,backup.activeEvents.single().durationMinutes)
            repo.undoPlan(plan.id);repo.deletePlan(plan.id)
            assertTrue(db.dao().dump().activePlans.isEmpty())
            repo.restore(backup)
            assertEquals(backup,db.dao().dump())
            try {repo.restore(backup.copy(events=emptyList()));fail("Must reject broken links")}catch(_:IllegalArgumentException){}
            assertEquals(backup,db.dao().dump())
        } finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun actualVersionTwoSchemaMigratesWithoutChangingAnyExistingValues() = runBlocking {
        val name="planning-migration-${System.nanoTime()}.db"
        val file=context.getDatabasePath(name);file.parentFile!!.mkdirs()
        val schema=InstrumentationRegistry.getInstrumentation().context.assets.open("com.self.daymark.LedgerDatabase/2.json").bufferedReader().use{JSONObject(it.readText()).getJSONObject("database")}
        val at=Time.parse("2026-10-12","13:30")
        try {
            SQLiteDatabase.openOrCreateDatabase(file,null).use { sql ->
                val entities=schema.getJSONArray("entities")
                for(i in 0 until entities.length()){
                    val e=entities.getJSONObject(i);val table=e.getString("tableName")
                    sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table))
                    val indices=e.optJSONArray("indices")
                    if(indices!=null)for(j in 0 until indices.length())sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
                }
                val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())sql.execSQL(setup.getString(i))
                for(status in listOf("done","planned","cancelled"))sql.execSQL("INSERT INTO events VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(status,status,"学习",at,null,status,"原描述","原体验",if(status=="done")-2 else null,null,0,1L,2L,null,null))
                sql.execSQL("INSERT INTO forecasts VALUES ('f','planned',1,40,NULL,NULL,'',NULL,NULL,'',1)")
                sql.execSQL("INSERT INTO cash VALUES ('cash',NULL,'支出',-1250,'expense',?,1,2)",arrayOf(at))
                sql.execSQL("INSERT INTO sleep VALUES ('2026-10-12','unknown',NULL,NULL,'原备注',1)")
                sql.execSQL("INSERT INTO balances VALUES ('b',0,50000,'余额',1)")
                sql.execSQL("INSERT INTO day_checks VALUES ('2026-10-12',2)")
                sql.version=2
            }
            fun open()=Room.databaseBuilder(context,LedgerDatabase::class.java,name).addMigrations(LedgerDatabase.MIGRATION_2_3).build()
            val db=open();val data=try{db.dao().dump()}finally{db.close()}
            assertEquals(3,data.events.size);assertTrue(data.events.all{it.description=="原描述"&&it.experience=="原体验"&&it.startAt==at})
            assertEquals(-2,data.events.first{it.id=="done"}.feeling)
            assertEquals(-1250L,data.cash.single().cents);assertEquals("原备注",data.sleeps.single().note)
            assertEquals(50000L,data.balances.single().cents);assertEquals(1,data.checks.size)
            val plan=data.plans.single();assertEquals("legacy-planned",plan.id);assertEquals(810,plan.startMinute);assertEquals(40L,plan.expectedMinutes)
            assertEquals(data,Export.fromJson(Export.json(data)))
            val reopened=open();try{assertEquals(data,reopened.dao().dump())}finally{reopened.close()}
        }finally{context.deleteDatabase(name)}
    }
}
