package com.self.daymark

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream

class PhotoPersistenceTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    @Test fun versionThreeDatabaseAddsPhotosWithoutChangingSavedRecords() = runBlocking {
        val name="photo-migration-${System.nanoTime()}.db";val file=context.getDatabasePath(name);file.parentFile!!.mkdirs()
        val schema=InstrumentationRegistry.getInstrumentation().context.assets.open("com.self.daymark.LedgerDatabase/3.json").bufferedReader().use{JSONObject(it.readText()).getJSONObject("database")}
        try{
            SQLiteDatabase.openOrCreateDatabase(file,null).use{sql->
                val entities=schema.getJSONArray("entities")
                for(i in 0 until entities.length()){
                    val e=entities.getJSONObject(i);val table=e.getString("tableName");sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table))
                    val indices=e.optJSONArray("indices");if(indices!=null)for(j in 0 until indices.length())sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
                }
                val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())sql.execSQL(setup.getString(i))
                sql.execSQL("INSERT INTO events VALUES ('e','原事情','生活',10,NULL,'done','文字','体验',-2,NULL,0,1,2,3,NULL,45)")
                sql.execSQL("INSERT INTO cash VALUES ('c',NULL,'原流水',-3200,'expense',10,1,2)")
                sql.execSQL("INSERT INTO plans VALUES ('p','原计划','备注','2026-10-14','morning',NULL,30,NULL,NULL,NULL,1,2,NULL)")
                sql.version=3
            }
            val db=Room.databaseBuilder(context,LedgerDatabase::class.java,name).addMigrations(LedgerDatabase.MIGRATION_3_4).build()
            try{val d=db.dao().dump();assertEquals(45L,d.events.single().actualMinutes);assertEquals("文字",d.events.single().description);assertEquals(-3200L,d.cash.single().cents);assertEquals(30L,d.plans.single().expectedMinutes);assertTrue(d.photos.isEmpty());assertEquals(d,Export.fromJson(Export.json(d)))}finally{db.close()}
        }finally{context.deleteDatabase(name)}
    }
    @Test fun picturesSurviveSourceDeletionReopenExportRestoreAndPlanUndo() = runBlocking {
        val name="photo-data-${System.nanoTime()}.db";var db=Room.databaseBuilder(context,LedgerDatabase::class.java,name).build()
        val files=PhotoFiles(context);val prior=files.folder.list()?.toSet() ?: emptySet()
        val source=File(context.cacheDir,"photo-source-${newId()}.jpg")
        val staging=File(context.cacheDir,"photo-test-${newId()}").apply{mkdirs()}
        var importedName:String?=null
        try{
            Bitmap.createBitmap(20,40,Bitmap.Config.ARGB_8888).also{it.eraseColor(System.nanoTime().toInt() or 0xff000000.toInt());source.outputStream().use{out->it.compress(Bitmap.CompressFormat.JPEG,95,out)};it.recycle()}
            ExifInterface(source.absolutePath).apply{setAttribute(ExifInterface.TAG_ORIENTATION,"6");saveAttributes()}
            val p=Plan(title="图片计划");var repo=LedgerRepository(db);repo.savePlan(p);val eventId=repo.completePlan(p.id)
            val photo=files.importUri(Uri.fromFile(source),"event",eventId)
            importedName=photo.fileName
            val cash=CashEntry(title="带图支出",cents=-500,kind="expense",occurredAt=1)
            val second=photo.copy(id=newId(),ownerType="cash",ownerId=cash.id)
            repo.saveEvent(db.dao().events().single(),null,null,listOf(photo));repo.saveCash(cash,listOf(second));source.delete()
            files.validate(listOf(photo));val decoded=PhotoFiles.decode(File(files.folder,photo.fileName),100)!!
            assertEquals(40,decoded.width);assertEquals(20,decoded.height);decoded.recycle()
            db.close();db=Room.databaseBuilder(context,LedgerDatabase::class.java,name).build();repo=LedgerRepository(db)
            val original=db.dao().dump();assertEquals(2,original.photos.size)
            val out=ByteArrayOutputStream();Export.writeZip(out,original){File(files.folder,it.fileName).inputStream()}
            val restored=Export.readBackup(out.toByteArray().inputStream(),staging);files.install(restored,staging);assertEquals(original,restored)
            repo.undoPlan(p.id);assertEquals(2,db.dao().photos().size);repo.completePlan(p.id);assertEquals(photo,db.dao().photos().first{it.id==photo.id})
            repo.saveCash(cash,emptyList());assertEquals(1,db.dao().photos().size)
            repo.restore(restored);assertEquals(restored,db.dao().dump())
            try{repo.saveCash(cash.copy(cents=-999),listOf(photo));fail("Cross-owner photo must fail")}catch(_:IllegalArgumentException){}
            assertEquals(-500L,db.dao().cash().first{it.id==cash.id}.cents)
            repo.deleteCash(cash);assertEquals(1,db.dao().photos().size);Export.validate(db.dao().dump())
        }finally{db.close();context.deleteDatabase(name);source.delete();staging.deleteRecursively();importedName?.takeIf{it !in prior}?.let{File(files.folder,it).delete()}}
    }
    @Test fun invalidImageIsRejectedBeforeItCanBeAttached() {
        val source=File(context.cacheDir,"invalid-${newId()}.jpg").apply{writeText("not a picture")}
        try{assertThrows(IllegalArgumentException::class.java){PhotoFiles(context).importUri(Uri.fromFile(source),"event","e")}}finally{source.delete()}
    }
}
