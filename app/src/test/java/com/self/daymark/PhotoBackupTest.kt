package com.self.daymark

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.zip.*
import com.google.gson.JsonParser

class PhotoBackupTest {
    private val bytes="fixture-image-bytes".toByteArray()
    private fun fixture():LedgerData {
        val e=Event(id="e",title="图片记录",startAt=1)
        val hash=bytes.inputStream().use(PhotoPolicy::hash)
        return LedgerData(events=listOf(e),photos=listOf(Photo(id="p",ownerType="event",ownerId=e.id,fileName="$hash.jpg",mimeType="image/jpeg",bytes=bytes.size.toLong(),sha256=hash)))
    }
    private fun zip(vararg entries:Pair<String,ByteArray>):ByteArray=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{z->entries.forEach{(name,body)->z.putNextEntry(ZipEntry(name));z.write(body);z.closeEntry()}}}.toByteArray()
    @Test fun fullArchiveRestoresOriginalImageBytesAndLinks() {
        val data=fixture();val folder=Files.createTempDirectory("photo-restore").toFile()
        try{
            val out=ByteArrayOutputStream();Export.writeZip(out,data){bytes.inputStream()}
            assertEquals(data,Export.readBackup(out.toByteArray().inputStream(),folder))
            assertArrayEquals(bytes,File(folder,data.photos.single().fileName).readBytes())
        }finally{folder.deleteRecursively()}
    }
    @Test fun metadataOnlyJsonCannotSilentlyRestoreWithoutPictures() {
        assertThrows(IllegalArgumentException::class.java){Export.readBackup(Export.json(fixture()).byteInputStream())}
        assertThrows(IllegalArgumentException::class.java){Export.writeZip(ByteArrayOutputStream(),fixture())}
    }
    @Test fun missingCorruptAndUnlistedImagesFailValidation() {
        val data=fixture();val metadata="records.json" to Export.json(data).toByteArray()
        val folder=Files.createTempDirectory("photo-invalid").toFile()
        try{
            assertThrows(IllegalArgumentException::class.java){Export.readBackup(zip(metadata).inputStream(),folder)}
            assertThrows(IllegalArgumentException::class.java){Export.readBackup(zip(metadata,"images/${data.photos[0].fileName}" to "broken".toByteArray()).inputStream(),folder)}
            assertThrows(IllegalArgumentException::class.java){Export.readBackup(zip("records.json" to Export.json(data.copy(photos=emptyList())).toByteArray(),"images/${data.photos[0].fileName}" to bytes).inputStream(),folder)}
        }finally{folder.deleteRecursively()}
    }
    @Test fun unsafePathsAndOversizedMetadataAreRejected() {
        val data=fixture();val p=data.photos[0]
        assertThrows(IllegalArgumentException::class.java){p.copy(fileName="../escape.jpg").validate()}
        assertThrows(IllegalArgumentException::class.java){p.copy(bytes=PhotoPolicy.MAX_BYTES+1).validate()}
        val folder=Files.createTempDirectory("photo-path").toFile()
        try{assertThrows(IllegalArgumentException::class.java){Export.readBackup(zip("images/../escape.jpg" to bytes).inputStream(),folder)}}finally{folder.deleteRecursively()}
    }
    @Test fun versionThreeJsonRemainsRestorableWithoutImages() {
        val data=fixture().copy(photos=emptyList())
        val tree=JsonParser.parseString(Export.json(data)).asJsonObject;tree.addProperty("schemaVersion",3);tree.getAsJsonObject("data").remove("photos")
        assertEquals(data,Export.readBackup(tree.toString().byteInputStream()))
    }
    @Test fun photoOwnersAndCountMustBeValid() {
        val data=fixture();val p=data.photos[0]
        assertThrows(IllegalArgumentException::class.java){Export.validate(data.copy(events=emptyList()))}
        assertThrows(IllegalArgumentException::class.java){Export.validate(data.copy(photos=(0..12).map{p.copy(id=it.toString())}))}
        assertThrows(IllegalArgumentException::class.java){Export.validate(data.copy(photos=listOf(p,p)))}
    }
    @Test fun exportRejectsImageBytesThatDoNotMatchTheirDigest() {
        assertThrows(IllegalArgumentException::class.java){Export.writeZip(ByteArrayOutputStream(),fixture()){ByteArray(bytes.size){0}.inputStream()}}
    }
}
