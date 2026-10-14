package com.self.daymark

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

class PhotoUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun makeFixture(file:File){
        val bitmap=Bitmap.createBitmap(640,400,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply{drawColor(Color.rgb(221,237,252));drawText("EventLog TEST",45f,200f,Paint().apply{color=Color.rgb(56,109,166);textSize=64f})}
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun screenshot(name:String){
        compose.waitForIdle();val folder=File(compose.activity.getExternalFilesDir(null),"verification").apply{mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        File(folder,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun cashAndEventPicturesCanBeViewedAndRemovedOnlyOnSave(){
        val files=PhotoFiles(compose.activity);val source=File(compose.activity.cacheDir,"ui-photo-${newId()}.png");makeFixture(source)
        val prior=files.folder.list()?.toSet() ?: emptySet()
        val event=Event(id="photo-fixture",title="有图记录",startAt=1)
        val cash=CashEntry(id="cash-fixture",title="带图收支",cents=-100,kind="expense",occurredAt=1)
        val photo=files.importUri(Uri.fromFile(source),"cash",cash.id)
        var saved:List<Photo>?=null;val mode=mutableStateOf("cash")
        try{
            compose.activityRule.scenario.onActivity{a->a.setContent{DaymarkTheme{
                if(mode.value=="cash")CashEditor(cash,LocalDate.now(),{}, {_,p->saved=p},{},listOf(photo))
                else EventEditor(event,null,null,LocalDate.now(),{}, {_,_,_,p->saved=p},{},listOf(photo.copy(ownerType="event",ownerId=event.id)))
            }}}
            compose.onNodeWithTag("add-gallery").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("add-camera").assertIsDisplayed()
            compose.onNodeWithTag("photo-${photo.id}").performScrollTo().performClick()
            compose.onNodeWithText("关闭图片").assertIsDisplayed().performClick()
            screenshot("30-cash-photos.png")
            compose.onNodeWithTag("remove-${photo.id}").performScrollTo().performClick();compose.onNodeWithText("确认").performClick()
            compose.onNodeWithText("保存").performClick();compose.runOnIdle{assertTrue(saved!!.isEmpty());mode.value="event"}
            compose.onNodeWithTag("photo-${photo.id}").performScrollTo().assertIsDisplayed()
            screenshot("31-event-photos.png")
            compose.onNodeWithText("保存").performClick();compose.runOnIdle{assertEquals(1,saved!!.size);assertEquals("event",saved!!.single().ownerType)}
        }finally{source.delete();if(photo.fileName !in prior)File(files.folder,photo.fileName).delete()}
    }
    @Test fun realGalleryReturnsASelectedImageToCashDraft(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("externalPhotos")=="true")
        val context=compose.activity;val files=PhotoFiles(context);val prior=files.folder.list()?.toSet() ?: emptySet()
        val source=File(context.cacheDir,"gallery-fixture-${newId()}.png");makeFixture(source)
        val resolver=context.contentResolver
        val uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply{
            put(MediaStore.Images.Media.DISPLAY_NAME,"EventLog-test-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/EventLog-tests");put(MediaStore.Images.Media.IS_PENDING,1)
        })!!
        resolver.openOutputStream(uri)!!.use{out->source.inputStream().use{it.copyTo(out)}}
        resolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
        val cash=CashEntry(title="相册测试",cents=-1,kind="expense",occurredAt=1);var saved:List<Photo>?=null;var sourceDeleted=false
        try{
            compose.activityRule.scenario.onActivity{a->a.setContent{DaymarkTheme{CashEditor(cash,LocalDate.now(),{}, {_,p->saved=p},{})}}}
            compose.onNodeWithTag("add-gallery").performScrollTo().performClick()
            compose.waitUntil(180000){runCatching{compose.onAllNodesWithText("图片 1/12").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
            compose.onNodeWithText("保存").performClick();compose.runOnIdle{assertEquals(1,saved!!.size);assertEquals("cash",saved!!.single().ownerType)}
            resolver.delete(uri,null,null);sourceDeleted=true;files.validate(saved!!)
            screenshot("32-gallery-result.png")
        }finally{if(!sourceDeleted)resolver.delete(uri,null,null);source.delete();saved?.filter{it.fileName !in prior}?.forEach{File(files.folder,it.fileName).delete()}}
    }
    @Test fun realCameraReturnsAFullPhotoToEventDraft(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("externalPhotos")=="true")
        val files=PhotoFiles(compose.activity);val prior=files.folder.list()?.toSet() ?: emptySet()
        val event=Event(title="拍照测试",startAt=1);var saved:List<Photo>?=null
        try{
            compose.activityRule.scenario.onActivity{a->a.setContent{DaymarkTheme{EventEditor(event,null,null,LocalDate.now(),{}, {_,_,_,p->saved=p},{})}}}
            compose.onNodeWithTag("add-camera").performScrollTo().performClick()
            compose.waitUntil(180000){runCatching{compose.onAllNodesWithText("图片 1/12").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
            compose.onNodeWithText("保存").performClick();compose.runOnIdle{assertEquals(1,saved!!.size);assertTrue(saved!!.single().bytes>1000)}
            files.validate(saved!!);screenshot("33-camera-result.png")
        }finally{saved?.filter{it.fileName !in prior}?.forEach{File(files.folder,it.fileName).delete()}}
    }
}
