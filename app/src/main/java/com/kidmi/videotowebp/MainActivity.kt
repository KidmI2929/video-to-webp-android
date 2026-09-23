package com.kidmi.videotowebp
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
class MainActivity: ComponentActivity(){ override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme{Screen()}}}}
@Composable fun Screen(){
 var video by remember{mutableStateOf<Uri?>(null)}
 var quality by remember{mutableFloatStateOf(80f)}
 var fps by remember{mutableFloatStateOf(15f)}
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){video=it}
 Surface(Modifier.fillMaxSize()){Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
  Text("Video → WebP",style=MaterialTheme.typography.headlineLarge)
  Text("영상 파일을 선택하고 Animated WebP로 변환합니다.")
  Button({picker.launch("video/*")},Modifier.fillMaxWidth()){Text(if(video==null)"영상 선택" else "다른 영상 선택")}
  if(video!=null) Text("영상 선택 완료")
  Text("FPS: "+fps.toInt()); Slider(fps,{fps=it},valueRange=5f..30f,steps=4)
  Text("품질: "+quality.toInt()); Slider(quality,{quality=it},valueRange=10f..100f,steps=8)
  Spacer(Modifier.weight(1f))
  Button({},Modifier.fillMaxWidth().height(56.dp),enabled=video!=null){Text("WebP로 변환")}
  Text("현재 빌드는 설치/실행 확인용입니다. 실제 변환 엔진은 다음 버전에 연결합니다.",style=MaterialTheme.typography.bodySmall)
 }}}
}