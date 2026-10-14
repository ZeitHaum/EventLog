package com.self.daymark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

class PhotoFiles(private val context:Context) {
    val folder get()=File(context.filesDir,"photos").apply{mkdirs()}
    fun captureFile():File = File(File(context.cacheDir,"camera").apply{mkdirs()},"${newId()}.jpg")
    fun captureUri(file:File):Uri = FileProvider.getUriForFile(context,"${context.packageName}.photos",file)
    fun importUri(uri:Uri,type:String,owner:String):Photo {
        val temporary=File(folder,"${newId()}.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use{input->temporary.outputStream().use{out->
                val buffer=ByteArray(8192);var size=0L
                while(true){val n=input.read(buffer);if(n<0)break;size+=n;require(size<=PhotoPolicy.MAX_BYTES){"单张图片不能超过 20 MB"};out.write(buffer,0,n)}
            }} ?: error("无法读取图片，请重新选择")
            val bounds=checkImage(temporary)
            val mime=bounds.outMimeType ?: error("无法识别图片格式")
            val hash=temporary.inputStream().use(PhotoPolicy::hash)
            val photo=Photo(ownerType=type,ownerId=owner,fileName="$hash.${PhotoPolicy.extension(mime)}",mimeType=mime,bytes=temporary.length(),sha256=hash)
            val target=File(folder,photo.fileName)
            if(target.exists())PhotoPolicy.validateFile(photo,folder)
            else require(temporary.renameTo(target)){"图片保存失败，请检查可用空间"}
            return photo
        } finally {temporary.delete()}
    }
    fun validate(photos:List<Photo>) {photos.distinctBy{it.fileName}.forEach{PhotoPolicy.validateFile(it,folder)}}
    fun install(data:LedgerData,staging:File) {
        data.photos.distinctBy{it.fileName}.forEach{p->
            val source=PhotoPolicy.validateFile(p,staging);checkImage(source)
            val target=File(folder,p.fileName)
            if(target.exists())PhotoPolicy.validateFile(p,folder)
            else {
                val temporary=File(folder,"${newId()}.tmp")
                try{source.copyTo(temporary);require(temporary.renameTo(target)){"恢复图片失败，请检查可用空间"}}finally{temporary.delete()}
            }
        }
    }
    companion object {
        fun checkImage(file:File):BitmapFactory.Options {
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeFile(file.absolutePath,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=200_000_000L){"图片无效或分辨率过大"}
            PhotoPolicy.extension(bounds.outMimeType ?: "")
            return bounds
        }
        fun decode(file:File,maxSide:Int):Bitmap? {
            val bounds=checkImage(file)
            var sample=1
            while(maxOf(bounds.outWidth,bounds.outHeight)/sample>maxSide)sample*=2
            val bitmap=BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply{inSampleSize=sample}) ?: return null
            val orientation=runCatching{ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}.getOrDefault(1)
            val matrix=Matrix()
            when(orientation){
                2->matrix.setScale(-1f,1f);3->matrix.setRotate(180f);4->matrix.setScale(1f,-1f)
                5->{matrix.setRotate(90f);matrix.postScale(-1f,1f)};6->matrix.setRotate(90f)
                7->{matrix.setRotate(-90f);matrix.postScale(-1f,1f)};8->matrix.setRotate(-90f)
            }
            return if(matrix.isIdentity)bitmap else Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true).also{if(it!==bitmap)bitmap.recycle()}
        }
    }
}
