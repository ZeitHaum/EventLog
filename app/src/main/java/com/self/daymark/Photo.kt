package com.self.daymark

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.File
import java.io.InputStream
import java.io.Serializable
import java.security.MessageDigest

@Entity(tableName="photos", indices=[Index(value=["ownerType","ownerId"])])
data class Photo(
    @PrimaryKey val id:String = newId(), val ownerType:String, val ownerId:String,
    val fileName:String, val mimeType:String, val bytes:Long, val sha256:String,
    val createdAt:Long = System.currentTimeMillis()
):Serializable {
    fun validate() {
        require(id.isNotBlank() && ownerId.isNotBlank() && ownerType in listOf("event","cash")) { "图片关联无效" }
        require(bytes in 1..PhotoPolicy.MAX_BYTES && sha256.matches(Regex("[a-f0-9]{64}"))) { "图片大小或校验值无效" }
        require(fileName == "$sha256.${PhotoPolicy.extension(mimeType)}") { "图片文件名无效" }
    }
}

object PhotoPolicy {
    const val MAX_COUNT=12
    const val MAX_BYTES=20L*1024*1024
    fun extension(mime:String) = when(mime){
        "image/jpeg"->"jpg";"image/png"->"png";"image/webp"->"webp";"image/gif"->"gif"
        "image/heif","image/heic"->"heic";"image/bmp","image/x-ms-bmp"->"bmp"
        else->throw IllegalArgumentException("暂不支持此图片格式，请选择 JPEG、PNG、WebP、GIF、HEIC 或 BMP")
    }
    fun hash(input:InputStream):String {
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192)
        while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    fun validateFile(photo:Photo,folder:File):File {
        photo.validate();val file=File(folder,photo.fileName)
        require(file.isFile && file.length()==photo.bytes && file.inputStream().use(::hash)==photo.sha256) { "图片缺失或已损坏，请使用包含图片的完整 ZIP 备份" }
        return file
    }
    fun validateLinks(data:LedgerData) {
        require(data.photos.size<=100000 && data.photos.map{it.id}.distinct().size==data.photos.size) { "图片编号重复或数量过多" }
        data.photos.forEach { p ->
            p.validate()
            require(if(p.ownerType=="event")data.events.any{it.id==p.ownerId} else data.cash.any{it.id==p.ownerId && it.eventId==null}) { "图片对应的记录不存在" }
        }
        require(data.photos.groupBy{it.ownerType to it.ownerId}.values.all{it.size<=MAX_COUNT}) { "每条记录最多添加 12 张图片" }
        require(data.photos.groupBy{it.fileName}.values.all{list->list.map{it.bytes to it.sha256}.distinct().size==1}) { "图片信息冲突" }
    }
}
