package com.self.daymark

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RatingMigrationTest {
    @Test fun actualVersionOneSchemaMigratesRatingsAndReopensWithoutConvertingTwice() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="rating-migration-${System.nanoTime()}.db"
        val file=context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema=InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.self.daymark.LedgerDatabase/1.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val legacyScores=listOf(null,1,2,3,4,5)
        try {
            SQLiteDatabase.openOrCreateDatabase(file,null).use { sql ->
                val entities=schema.getJSONArray("entities")
                for(i in 0 until entities.length()) {
                    val entity=entities.getJSONObject(i)
                    val table=entity.getString("tableName")
                    sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                    val indices=entity.optJSONArray("indices")
                    if(indices!=null) for(j in 0 until indices.length()) sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
                }
                val setup=schema.getJSONArray("setupQueries")
                for(i in 0 until setup.length()) sql.execSQL(setup.getString(i))
                legacyScores.forEachIndexed { i,score ->
                    sql.execSQL("INSERT INTO events (id,title,category,startAt,endAt,status,description,experience,feeling,worth,costKnown,createdAt,updatedAt,firstOutcomeAt,deletedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        arrayOf<Any?>("e$i","旧事件 $i","工作",10L,20L,"done","备注","体验",score,score,0,1L,2L,20L,if(i==1)30L else null))
                    sql.execSQL("INSERT INTO forecasts (id,eventId,madeAt,minutes,costCents,expectedFeeling,question,probability,deadlineAt,basis,prospective) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        arrayOf<Any?>("f$i","e$i",1L,10L,null,score,"",null,null,"原判断",1))
                }
                sql.execSQL("INSERT INTO cash (id,eventId,title,cents,kind,occurredAt,createdAt,updatedAt) VALUES ('cash',NULL,'原收入',5000,'income',10,1,1)")
                sql.version=1
            }
            fun open()=Room.databaseBuilder(context,LedgerDatabase::class.java,name).addMigrations(LedgerDatabase.MIGRATION_1_2).build()
            val db=open()
            val migrated=try { db.dao().dump() } finally { db.close() }
            val expected=listOf(null,-2,-1,0,1,2)
            assertEquals(expected,migrated.events.sortedBy{it.id}.map{it.feeling})
            assertEquals(expected,migrated.events.sortedBy{it.id}.map{it.worth})
            assertEquals(expected,migrated.forecasts.sortedBy{it.id}.map{it.expectedFeeling})
            assertEquals(5000L,migrated.cash.single().cents)
            assertEquals(30L,migrated.events.single{it.id=="e1"}.deletedAt)
            Export.validate(migrated)
            val reopened=open()
            try {
                assertEquals(migrated,reopened.dao().dump())
                val event=migrated.events.single{it.id=="e3"}.copy(feeling=-2,worth=0)
                LedgerRepository(reopened).saveEvent(event,null,null)
                assertEquals(-2,reopened.dao().events().single{it.id==event.id}.feeling)
                assertEquals(0,reopened.dao().events().single{it.id==event.id}.worth)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
