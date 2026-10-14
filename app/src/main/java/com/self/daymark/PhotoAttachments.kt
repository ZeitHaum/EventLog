package com.self.daymark

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import java.io.File

@Composable fun PhotoAttachments(type:String,owner:String,photos:List<Photo>,onChange:(ArrayList<Photo>)->Unit,onBusy:(Boolean)->Unit) {
    val context=LocalContext.current
    val files=remember(context){PhotoFiles(context)}
    val scope=rememberCoroutineScope()
    var working by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var capturePath by rememberSaveable{mutableStateOf<String?>(null)}
    var viewing by remember{mutableStateOf<Photo?>(null)}
    var removing by remember{mutableStateOf<Photo?>(null)}
    fun load(uris:List<Uri>,capture:File?=null) {
        if(uris.isEmpty()){capture?.delete();return}
        if(photos.size+uris.size>PhotoPolicy.MAX_COUNT){error="每条记录最多添加 12 张图片";capture?.delete();return}
        working=true;onBusy(true);error=null
        scope.launch {
            try {
                val imported=withContext(Dispatchers.IO){uris.map{files.importUri(it,type,owner)}}
                onChange(ArrayList(photos+imported))
            }catch(e:CancellationException){throw e}catch(e:Exception){error=e.message ?: "图片添加失败"}
            finally{capture?.delete();working=false;onBusy(false)}
        }
    }
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PhotoPolicy.MAX_COUNT)){load(it)}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){success->
        val file=capturePath?.let(::File);capturePath=null
        if(success && file!=null)load(listOf(files.captureUri(file)),file) else file?.delete()
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Label("图片 ${photos.size}/${PhotoPolicy.MAX_COUNT}")
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedButton({try{gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}catch(e:Exception){error="无法打开相册：${e.message}"}},enabled=!working&&photos.size<PhotoPolicy.MAX_COUNT,modifier=Modifier.testTag("add-gallery")){Text("从相册选择")}
            OutlinedButton({
                try{val file=files.captureFile();capturePath=file.absolutePath;camera.launch(files.captureUri(file))}
                catch(e:Exception){capturePath?.let{File(it).delete()};capturePath=null;error="无法打开相机，请确认手机有可用的相机应用"}
            },enabled=!working&&photos.size<PhotoPolicy.MAX_COUNT,modifier=Modifier.testTag("add-camera")){Text("拍照添加")}
        }
        if(working)LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let{Text(it,color=Clay)}
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            photos.forEachIndexed{index,p->Column(horizontalAlignment=Alignment.CenterHorizontally){
                PhotoBitmap(p,Modifier.size(88.dp).testTag("photo-${p.id}").clickable{viewing=p},false,"图片 ${index+1}")
                TextButton({removing=p},enabled=!working,modifier=Modifier.testTag("remove-${p.id}")){Text("移除")}
            }}
        }
    }
    removing?.let{p->ConfirmDialog("移除图片？","保存记录后生效，不会删除相册原图。",{removing=null},{onChange(ArrayList(photos.filter{it.id!=p.id}));removing=null})}
    viewing?.let{PhotoViewer(it){viewing=null}}
}

@Composable fun PhotoBitmap(photo:Photo,modifier:Modifier=Modifier,large:Boolean=false,description:String="图片") {
    val context=LocalContext.current
    val bitmap by produceState<Bitmap?>(null,photo.fileName,large) {
        value=withContext(Dispatchers.IO){runCatching{PhotoFiles.decode(File(PhotoFiles(context).folder,photo.fileName),if(large)2048 else 256)}.getOrNull()}
    }
    if(bitmap!=null)Image(bitmap!!.asImageBitmap(),description,modifier,contentScale=if(large)ContentScale.Fit else ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center){Label("加载图片")}
}

@Composable fun PhotoViewer(photo:Photo,onDismiss:()->Unit) {
    var scale by remember{mutableFloatStateOf(1f)}
    var x by remember{mutableFloatStateOf(0f)};var y by remember{mutableFloatStateOf(0f)}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=CanvasColor){Column{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton({scale=1f;x=0f;y=0f}){Text("重置缩放")};TextButton(onDismiss){Text("关闭图片")}}
            Box(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit){detectTransformGestures{_,pan,zoom,_->scale=(scale*zoom).coerceIn(1f,5f);x=if(scale>1)x+pan.x else 0f;y=if(scale>1)y+pan.y else 0f}}){
                PhotoBitmap(photo,Modifier.fillMaxSize().graphicsLayer{scaleX=scale;scaleY=scale;translationX=x;translationY=y},true)
            }
        }}
    }
}

@Composable fun PhotoPreview(photos:List<Photo>) {
    var viewing by remember{mutableStateOf<Photo?>(null)}
    if(photos.isNotEmpty())Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
        photos.take(3).forEachIndexed{i,p->PhotoBitmap(p,Modifier.size(56.dp).clickable{viewing=p},description="查看图片 ${i+1}")}
        if(photos.size>3)Label("共 ${photos.size} 张")
    }
    viewing?.let{PhotoViewer(it){viewing=null}}
}
