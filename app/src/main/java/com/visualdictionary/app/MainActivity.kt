package com.visualdictionary.app

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.Locale

private val android.content.Context.dataStore by preferencesDataStore(name = "dictionary_settings")
private val FAVORITES = stringSetPreferencesKey("favorites")
private val LEARNED = intPreferencesKey("learned_count")
private val DARK = booleanPreferencesKey("dark_theme")
private val FONT_SCALE = floatPreferencesKey("font_scale")

data class Word(val id:Int,val category:String,val english:String,val ipa:String,val pronunciation:String,val russian:String,val image:String)
private val categories = listOf("Дом","Люди","Здоровье","Работа","Обучение","Покупки","Пища","Общественное питание","Службы","Транспорт","Досуг","Спорт","Окружающая среда","Флора и фауна")

private data class AppUpdate(val versionCode:Int,val versionName:String,val downloadUrl:String,val notes:String)

private suspend fun fetchAppUpdate(): AppUpdate? = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(com.visualdictionary.app.BuildConfig.UPDATE_CHECK_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 7000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        if (connection.responseCode !in 200..299) return@withContext null
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        AppUpdate(
            json.optInt("versionCode", 0),
            json.optString("versionName", ""),
            json.optString("downloadUrl", ""),
            json.optString("releaseNotes", "Исправления и улучшения.")
        )
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { if(it == TextToSpeech.SUCCESS) tts?.language = Locale.UK }
        val words = loadWords()
        setContent { DictionaryApp(words) { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "word") } }
        
    }
    private fun loadWords(): List<Word> {
        val raw = assets.open("words.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o=arr.getJSONObject(i)
            Word(o.getInt("id"),o.getString("category"),o.getString("english"),o.optString("ipa"),o.optString("pronunciation"),o.getString("russian"),o.optString("image","house"))
        }
    }
    override fun onDestroy(){ tts?.shutdown(); super.onDestroy() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryApp(words:List<Word>,speak:(String)->Unit) {
    val context= LocalContext.current
    val scope= rememberCoroutineScope()
    var availableUpdate by remember { mutableStateOf<AppUpdate?>(null) }
    var updateChecked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        availableUpdate = fetchAppUpdate()?.takeIf { it.versionCode > com.visualdictionary.app.BuildConfig.VERSION_CODE }
        updateChecked = true
    }
    if (availableUpdate != null) {
        AlertDialog(
            onDismissRequest = { availableUpdate = null },
            title = { Text("Доступна новая версия") },
            text = {
                Column {
                    Text("Visual English Dictionary ${availableUpdate!!.versionName}")
                    Spacer(Modifier.height(8.dp))
                    Text(availableUpdate!!.notes)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val link = availableUpdate!!.downloadUrl
                    availableUpdate = null
                    if (link.startsWith("https://")) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                    }
                }) { Text("Скачать обновление") }
            },
            dismissButton = { TextButton(onClick = { availableUpdate = null }) { Text("Позже") } }
        )
    }
    val prefs by context.dataStore.data.collectAsState(initial=emptyPreferences())
    val favorites=prefs[FAVORITES] ?: emptySet()
    val learned=prefs[LEARNED] ?: 0
    val dark=prefs[DARK] ?: false
    val fontScale=prefs[FONT_SCALE] ?: 1f
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Все темы") }
    var selected by remember { mutableStateOf<Word?>(null) }
    var flashIndex by remember { mutableIntStateOf(0) }
    var reveal by remember { mutableStateOf(false) }
    var quizIndex by remember { mutableIntStateOf(0) }
    var quizChoice by remember { mutableStateOf<Int?>(null) }
    var quizScore by remember { mutableIntStateOf(0) }
    var quizDone by remember { mutableStateOf(false) }
    val palette=if(dark) darkColorScheme(primary=Color(0xFF8BB5FF),background=Color(0xFF111827),surface=Color(0xFF1F2937)) else lightColorScheme(primary=Color(0xFF2864D8),secondary=Color(0xFF18A999),background=Color(0xFFF4F6FB),surface=Color.White)
    val filtered=words.filter { (category=="Все темы"||it.category==category) && (query.isBlank()||it.english.contains(query,true)||it.russian.contains(query,true)) }
    MaterialTheme(colorScheme=palette,typography=Typography(bodyLarge=MaterialTheme.typography.bodyLarge.copy(fontSize=16.sp*fontScale))) {
        Scaffold(
            topBar={ TopAppBar(title={Text("Visual English Dictionary",fontWeight=FontWeight.Bold)},actions={IconButton(onClick={tab=2;selected=null}){Icon(Icons.Default.Favorite,null)}}) },
            bottomBar={ NavigationBar {
                NavigationBarItem(tab==0,{tab=0;selected=null},icon={Icon(Icons.Default.Home,null)},label={Text("Главная")})
                NavigationBarItem(tab==1,{tab=1;selected=null},icon={Icon(Icons.Default.Search,null)},label={Text("Словарь")})
                NavigationBarItem(tab==2,{tab=2;selected=null},icon={Icon(Icons.Default.Favorite,null)},label={Text("Избранное")})
                NavigationBarItem(tab==3,{tab=3;selected=null},icon={Icon(Icons.Default.Style,null)},label={Text("Карточки")})
                NavigationBarItem(tab==4,{tab=4;selected=null},icon={Icon(Icons.Default.Quiz,null)},label={Text("Тесты")})
                NavigationBarItem(tab==5,{tab=5;selected=null},icon={Icon(Icons.Default.Settings,null)},label={Text("Настройки")})
            }}
        ) { pad ->
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(pad).padding(horizontal=14.dp)) {
                if(selected!=null) {
                    WordDetail(selected!!,favorites.contains(selected!!.id.toString()),{id->scope.launch{context.dataStore.edit{p->val set=(p[FAVORITES]?:emptySet()).toMutableSet();if(id.toString() in set)set.remove(id.toString()) else set.add(id.toString());p[FAVORITES]=set}}},speak,{selected=null},fontScale)
                } else when(tab) {
                    0 -> HomeScreen(words,learned,{tab=1;category="Все темы"},{tab=3},{tab=4},{tab=2})
                    1 -> {
                        Text("Словарь",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=12.dp))
                        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(top=8.dp),placeholder={Text("Поиск на английском или русском")},leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true)
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)){(listOf("Все темы")+categories).forEach{FilterChip(category==it,{category=it},label={Text(it)})}}
                        LazyColumn(verticalArrangement=Arrangement.spacedBy(7.dp)){items(filtered){w->WordRow(w,favorites.contains(w.id.toString()),{selected=w},{speak(w.english)})}}
                    }
                    2 -> {
                        Text("Избранное",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=12.dp))
                        val favWords=words.filter{it.id.toString() in favorites}
                        if(favWords.isEmpty()) EmptyState("Здесь будут сохранённые слова")
                        else LazyColumn(verticalArrangement=Arrangement.spacedBy(7.dp)){items(favWords){w->WordRow(w,true,{selected=w},{speak(w.english)})}}
                    }
                    3 -> {
                        Text("Карточки",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=12.dp))
                        val w=words.getOrNull(flashIndex%words.size)
                        if(w!=null) Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)) {
                            Column(Modifier.padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                WordImage(w,Modifier.fillMaxWidth().height(220.dp))
                                Text(if(reveal)w.russian else w.english,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,modifier=Modifier.padding(18.dp))
                                if(reveal){Text(w.ipa);Text(w.pronunciation,color=Color.Gray)}
                                Button(onClick={reveal=!reveal},Modifier.fillMaxWidth()){Text(if(reveal)"Скрыть перевод" else "Показать перевод")}
                                OutlinedButton(onClick={speak(w.english)},Modifier.fillMaxWidth()){Icon(Icons.Default.VolumeUp,null);Text("  Прослушать")}
                                Button(onClick={flashIndex++;reveal=false;scope.launch{context.dataStore.edit{p->p[LEARNED]=(p[LEARNED]?:0)+1}}},Modifier.fillMaxWidth()){Text("Следующая карточка")}
                                Text("${flashIndex+1} / ${words.size}",color=Color.Gray)
                            }
                        }
                    }
                    4 -> QuizScreen(words,quizIndex,quizChoice,quizScore,quizDone,{quizChoice=it},{ 
                        val q=words[quizIndex%words.size]
                        if(quizChoice==q.id) quizScore++
                        if(quizIndex+1>=10) quizDone=true else quizIndex++
                        quizChoice=null
                    },{quizIndex=0;quizScore=0;quizChoice=null;quizDone=false},speak)
                    else -> SettingsScreen(dark,fontScale,{v->scope.launch{context.dataStore.edit{it[DARK]=v}}},{v->scope.launch{context.dataStore.edit{it[FONT_SCALE]=v}}},learned,favorites.size,words.size)
                }
            }
        }
    }
}

@Composable
fun HomeScreen(words:List<Word>,learned:Int,openDictionary:()->Unit,openCards:()->Unit,openQuiz:()->Unit,openFavorites:()->Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("Англо-русский визуальный словарь",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=14.dp))
        Text("Слова • транскрипция • произношение",color=Color.Gray)
        OutlinedButton(onClick=openDictionary,Modifier.fillMaxWidth().padding(top=12.dp)){Icon(Icons.Default.Search,null);Text("  Найти слово")}
        Text("Тематические разделы",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=10.dp))
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.weight(1f)) {
            items(categories){c->
                val count=words.count{it.category==c}
                Card(onClick=openDictionary,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.CollectionsBookmark,null,tint=Color(0xFF2864D8),modifier=Modifier.size(30.dp))
                        Column(Modifier.weight(1f).padding(start=12.dp)){Text(c,fontWeight=FontWeight.Bold);Text("$count слов в текущей базе",color=Color.Gray,fontSize=12.sp)}
                        Icon(Icons.Default.ChevronRight,null)
                    }
                }
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(vertical=8.dp)) {
            Button(onClick=openCards,Modifier.weight(1f)){Text("Карточки")}
            Button(onClick=openQuiz,Modifier.weight(1f)){Text("Тесты")}
            OutlinedButton(onClick=openFavorites,Modifier.weight(1f)){Text("Избранное")}
        }
    }
}

@Composable
fun WordImage(w:Word,modifier:Modifier=Modifier) {
    val ctx=LocalContext.current
    val id=ctx.resources.getIdentifier(w.image,"drawable",ctx.packageName)
    if(id!=0) Image(painterResource(id),contentDescription=w.english,modifier=modifier,contentScale=ContentScale.Fit)
}

@Composable
fun WordRow(w:Word,fav:Boolean,onClick:()->Unit,onSpeak:()->Unit) {
    Card(onClick=onClick,Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(9.dp),verticalAlignment=Alignment.CenterVertically) {
            WordImage(w,Modifier.size(72.dp))
            Column(Modifier.weight(1f).padding(start=9.dp)){
                Text(w.english,fontWeight=FontWeight.Bold,fontSize=17.sp)
                Text("${w.ipa}  ${w.pronunciation}",color=Color(0xFF55708F),fontSize=12.sp)
                Text(w.russian)
                Text(w.category,color=Color.Gray,fontSize=11.sp)
            }
            IconButton(onClick=onSpeak){Icon(Icons.Default.VolumeUp,null,tint=Color(0xFF2864D8))}
            if(fav) Icon(Icons.Default.Favorite,null,tint=Color(0xFFE45B70))
        }
    }
}

@Composable
fun WordDetail(w:Word,fav:Boolean,toggle:(Int)->Unit,speak:(String)->Unit,back:()->Unit,fontScale:Float) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top=8.dp)) {
        TextButton(onClick=back){Icon(Icons.Default.ArrowBack,null);Text("Назад")}
        Card(shape=RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                WordImage(w,Modifier.fillMaxWidth().height(230.dp))
                Text(w.category,color=Color.Gray)
                Text(w.english,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
                Text(w.ipa,style=MaterialTheme.typography.titleLarge,color=Color(0xFF2864D8))
                Text(w.pronunciation,color=Color.Gray)
                Text(w.russian,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(vertical=18.dp))
                Button(onClick={speak(w.english)},Modifier.fillMaxWidth()){Icon(Icons.Default.VolumeUp,null);Text("  Прослушать произношение")}
                OutlinedButton(onClick={toggle(w.id)},Modifier.fillMaxWidth()){Icon(if(fav)Icons.Default.Favorite else Icons.Default.FavoriteBorder,null);Text(if(fav)"  Убрать из избранного" else "  В избранное")}
            }
        }
    }
}

@Composable
fun QuizScreen(words:List<Word>,index:Int,choice:Int?,score:Int,done:Boolean,onChoice:(Int)->Unit,onNext:()->Unit,onRestart:()->Unit,speak:(String)->Unit) {
    val current=words[index%words.size]
    val options=remember(index,words){(listOf(current)+words.filter{it.id!=current.id}.shuffled().take(3)).shuffled()}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top=12.dp)) {
        Text("Проверка знаний",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        if(done) {
            Text("Тест завершён",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.padding(top=24.dp))
            Text("Правильных ответов: $score из 10")
            Button(onClick=onRestart,Modifier.fillMaxWidth()){Text("Пройти ещё раз")}
        } else {
            LinearProgressIndicator(progress={(index+1)/10f},Modifier.fillMaxWidth().padding(vertical=14.dp))
            Text("Вопрос ${index+1} из 10")
            WordImage(current,Modifier.fillMaxWidth().height(190.dp))
            Text(current.english,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,modifier=Modifier.padding(12.dp))
            OutlinedButton(onClick={speak(current.english)}){Icon(Icons.Default.VolumeUp,null);Text("  Прослушать")}
            Text("Выберите правильный перевод:",fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=10.dp))
            options.forEachIndexed{i,w->
                val isSelected=choice==w.id
                OutlinedButton(onClick={onChoice(w.id)},Modifier.fillMaxWidth().padding(vertical=4.dp),enabled=choice==null) {
                    Text(w.russian,Modifier.fillMaxWidth(),color=if(isSelected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
            Button(onClick=onNext,Modifier.fillMaxWidth(),enabled=choice!=null){Text(if(index==9)"Завершить" else "Далее")}
        }
    }
}

@Composable
fun SettingsScreen(dark:Boolean,fontScale:Float,onDark:(Boolean)->Unit,onFont:(Float)->Unit,learned:Int,fav:Int,total:Int) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top=14.dp)) {
        Text("Настройки",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically){Text("Тёмная тема",Modifier.weight(1f));Switch(dark,onDark)}
        Text("Размер текста: ${(fontScale*100).toInt()}%")
        Slider(fontScale,onFont,valueRange=0.85f..1.3f)
        Divider(Modifier.padding(vertical=12.dp))
        Text("Статистика обучения",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        Text("Изучено карточек: $learned")
        Text("Избранных слов: $fav")
        Text("Слов в установленной базе: $total")
        Text("Приложение работает офлайн. Озвучивание использует системный TTS Android.",color=Color.Gray,modifier=Modifier.padding(top=12.dp))
    }
}

@Composable
fun EmptyState(message:String){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(message,color=Color.Gray)}}
