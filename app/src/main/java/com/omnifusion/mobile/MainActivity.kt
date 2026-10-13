package com.omnifusion.mobile

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.animation.ObjectAnimator
import android.view.HapticFeedbackConstants
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.animation.DecelerateInterpolator
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val bg = Color.rgb(11,14,24)
    private val panel = Color.rgb(27,33,50)
    private val accent = Color.rgb(120,111,247)
    private val white = Color.rgb(244,246,255)
    private val dim = Color.rgb(154,164,193)
    private val prefs by lazy { getSharedPreferences("omni", MODE_PRIVATE) }
    private var model = "openrouter/free"
    private var chats = JSONArray()
    private var current = 0
    private var busy = false
    private var screen = 0
    private val uiHandler=Handler(Looper.getMainLooper())
    @Volatile private var lastRender=0L
    @Volatile private var requestId=0
    private var activeConnection:HttpURLConnection?=null
    private var modelNames=mutableMapOf<String,String>()
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var stream: ScrollView
    private lateinit var transcript: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: TextView
    private fun dp(v:Int) = (v*resources.displayMetrics.density).toInt()
    private fun shape(color:Int, radius:Int=18) = GradientDrawable().apply {setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun text(s:String, size:Float=15f, color:Int=white, bold:Boolean=false) = TextView(this).apply {
        setText(s);textSize=size;setTextColor(color);if(bold)setTypeface(null,Typeface.BOLD);gravity=Gravity.CENTER_VERTICAL;setTextIsSelectable(true)
    }
    private fun vertical()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun horizontal()=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun lp(top:Int=0)=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(top)}
    private fun button(s:String, action:()->Unit)=text(s,14f,white,true).apply{
        gravity=Gravity.CENTER;setPadding(dp(13),dp(11),dp(13),dp(11))
        background=RippleDrawable(ColorStateList.valueOf(Color.rgb(72,79,128)),shape(panel,14),null)
        setOnClickListener{
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            animate().scaleX(.96f).scaleY(.96f).setDuration(85).withEndAction{
                animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }.start()
            action()
        }
    }
    private fun copy(s:String){
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("OmniFusion",s))
        Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show()
    }
    private fun secureKey():String {
        val value=prefs.getString("encryptedKey",null)
        if(value!=null)return try{
            val data=Base64.decode(value,Base64.NO_WRAP)
            val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            val c=Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE,store.getKey("omniKey",null),GCMParameterSpec(128,data.copyOfRange(0,12)))
            String(c.doFinal(data.copyOfRange(12,data.size)))
        }catch(_:Exception){""}
        val old=getSharedPreferences("private",MODE_PRIVATE).getString("key","") ?: ""
        if(old.isNotBlank())storeKey(old)
        return old
    }
    private fun storeKey(value:String){
        try{
            val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            if(!store.containsAlias("omniKey")){
                val gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
                gen.init(KeyGenParameterSpec.Builder("omniKey",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                gen.generateKey()
            }
            val c=Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE,store.getKey("omniKey",null))
            prefs.edit().putString("encryptedKey",Base64.encodeToString(c.iv+c.doFinal(value.toByteArray()),Base64.NO_WRAP)).apply()
            getSharedPreferences("private",MODE_PRIVATE).edit().remove("key").apply()
        }catch(e:Exception){Toast.makeText(this,"密钥保存失败：${e.message}",Toast.LENGTH_LONG).show()}
    }
    private fun keyDialog(){
        val edit=EditText(this).apply{hint="sk-or-…";setText(secureKey());inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD}
        val box=vertical().apply{setPadding(dp(22),0,dp(22),0);addView(edit)}
        AlertDialog.Builder(this).setTitle("🔐 OpenRouter API Key").setMessage("使用 Android Keystore 加密保存在本机，不会提交到 GitHub。")
            .setView(box).setPositiveButton("保存"){_,_->storeKey(edit.text.toString().trim());navigate(screen)}
            .setNegativeButton("取消",null).show()
    }
    private fun newChat()=JSONObject().put("title","新对话").put("items",JSONArray())
    private fun items()=chats.getJSONObject(current).getJSONArray("items")
    private fun persist(){prefs.edit().putString("chats",chats.toString()).putInt("current",current).apply()}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);window.statusBarColor=bg;window.navigationBarColor=bg
        model=prefs.getString("model","openrouter/free") ?: "openrouter/free"
        chats=try{JSONArray(prefs.getString("chats","[]"))}catch(_:Exception){JSONArray()}
        if(chats.length()==0)chats.put(newChat())
        current=prefs.getInt("current",0).coerceIn(0,chats.length()-1)
        navigate(0)
    }
    private fun navigate(page:Int){
        if(busy && page!=0){Toast.makeText(this,"回复中，请等待完成",Toast.LENGTH_SHORT).show();return}
        screen=page;root=vertical().apply{setBackgroundColor(bg)}
        val top=horizontal().apply{setPadding(dp(20),dp(13),dp(18),dp(13))}
        top.addView(text("✦  OmniFusion",24f,white,true),LinearLayout.LayoutParams(0,-2,1f))
        top.addView(button("⚙"){navigate(3)})
        root.addView(top)
        body=vertical();root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        val nav=horizontal().apply{setPadding(dp(8),dp(7),dp(8),dp(10));background=shape(panel)}
        val tabs=listOf("◉\n聊天","✧\n创作","▤\n历史","⚙\n设置")
        tabs.forEachIndexed{i,s->
            val b=text(s,12f,if(page==i)white else dim,page==i).apply{
                gravity=Gravity.CENTER;background=if(page==i)shape(Color.rgb(55,58,94),12)else null
                setOnClickListener{navigate(i)}
            }
            nav.addView(b,LinearLayout.LayoutParams(0,dp(56),1f))
        }
        root.addView(nav);setContentView(root)
        when(page){0->chatPage();1->createPage();2->historyPage();else->settingsPage()}
        body.alpha=0f;body.translationY=dp(9).toFloat()
        body.animate().alpha(1f).translationY(0f).setDuration(210).setInterpolator(DecelerateInterpolator()).start()
    }
    private fun heading(title:String,description:String){
        body.addView(text(title,27f,white,true).apply{setPadding(dp(19),dp(10),dp(19),0)})
        body.addView(text(description,13f,dim).apply{setPadding(dp(19),dp(6),dp(19),dp(19))})
    }
    private fun readableModel(id:String):String {
        if(id=="openrouter/free")return "随机免费路由"
        val n=modelNames[id]?:id.substringAfterLast('/')
        val brand=when{
            id.startsWith("deepseek/")->"深度求索"
            id.startsWith("google/")->"谷歌 Gemini"
            id.startsWith("openai/")->"OpenAI GPT"
            id.startsWith("anthropic/")->"Claude"
            id.startsWith("qwen/")->"阿里通义千问"
            id.startsWith("nvidia/")->"英伟达"
            id.startsWith("meta-llama/")->"Meta"
            id.startsWith("mistralai/")->"Mistral"
            id.startsWith("microsoft/")->"微软"
            id.startsWith("minimax/")->"MiniMax"
            id.startsWith("moonshotai/")->"月之暗面 Kimi"
            id.startsWith("z-ai/")->"智谱 GLM"
            else->"其他模型"
        }
        return "$brand · $n"
    }
    private fun chatPage(){
        val row=horizontal().apply{setPadding(dp(15),0,dp(15),dp(10))}
        row.addView(button("◆ ${readableModel(model).take(30)} ▾"){modelDialog()},LinearLayout.LayoutParams(0,-2,1f))
        row.addView(button("＋"){chats.put(newChat());current=chats.length()-1;persist();navigate(0)})
        body.addView(row)
        stream=ScrollView(this).apply{isFillViewport=true;isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER}
        transcript=vertical().apply{setPadding(dp(17),dp(10),dp(17),dp(15))}
        stream.addView(transcript);body.addView(stream,LinearLayout.LayoutParams(-1,0,1f))
        if(items().length()==0){
            transcript.addView(text("你的灵感，从这里开始。",25f,white,true),lp(38))
            transcript.addView(text("提问、创作、编程、学习。选择适合的免费云端模型。",15f,dim),lp(15))
        }else redraw()
        val bar=horizontal().apply{
            background=shape(panel,21);setPadding(dp(7),dp(7),dp(7),dp(7))
        }
        input=EditText(this).apply{
            hint="向 OmniFusion 提问…";setHintTextColor(dim);setTextColor(white)
            background=null;textSize=15f;minLines=1;maxLines=5
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        bar.addView(input,LinearLayout.LayoutParams(0,-2,1f))
        send=button("➤"){ask()}
        bar.addView(send)
        body.addView(bar,LinearLayout.LayoutParams(-1,-2).apply{setMargins(dp(15),dp(8),dp(15),dp(13))})
    }
    private fun bubble(value:String,me:Boolean,reason:String=""):TextView{
        val wrapper=vertical().apply{setPadding(0,dp(8),0,dp(10))}
        wrapper.addView(text(if(me)"你" else "✦ OmniFusion",12f,dim),lp(0))
        if(!me&&reason.isNotBlank()){
            wrapper.addView(button("查看模型返回的推理内容 ▾"){
                AlertDialog.Builder(this).setTitle("模型实际提供的推理内容").setMessage(reason)
                    .setPositiveButton("复制"){_,_->copy(reason)}.setNegativeButton("关闭",null).show()
            },lp(6))
        }
        val message=text(value,15f).apply{
            setPadding(dp(15),dp(12),dp(15),dp(12))
            background=shape(if(me)Color.rgb(65,62,137)else panel,17)
            setOnLongClickListener{copy(this.text.toString());true}
        }
        wrapper.addView(message,lp(8))
        wrapper.addView(text("复制 ⧉",12f,dim).apply{
            setPadding(dp(9),dp(7),0,0);setOnClickListener{copy(message.text.toString())}
        })
        transcript.addView(wrapper)
        stream.post{stream.fullScroll(View.FOCUS_DOWN)}
        return message
    }
    private fun redraw(){
        transcript.removeAllViews()
        val all=items()
        for(i in 0 until all.length()){
            val entry=all.optJSONObject(i)?:continue
            bubble(entry.optString("content"),entry.optString("role")=="user",entry.optString("reason",""))
        }
    }
    private fun modelDialog(){
        AlertDialog.Builder(this).setTitle("中文精选模型中心")
            .setItems(arrayOf("✦ 随机免费路由（不保证最强）","🏆 中文精选免费模型（在线查询）","✎ 自定义模型 ID（可能付费）")){_,choice->
                when(choice){
                    0->choose("openrouter/free")
                    1->freeModels()
                    2->{
                        val field=EditText(this).apply{setText(model);setSingleLine()}
                        AlertDialog.Builder(this).setTitle("模型 ID").setView(field)
                            .setPositiveButton("应用"){_,_->choose(field.text.toString().trim())}.setNegativeButton("取消",null).show()
                    }
                }
            }.show()
    }
    private fun choose(id:String){if(id.isNotBlank()){model=id;prefs.edit().putString("model",id).apply();navigate(0)}}
    private fun freeModels(){
        Toast.makeText(this,"正在查官方实时目录，只展示零价格模型…",Toast.LENGTH_SHORT).show()
        thread{
            try{
                val conn=URL("https://openrouter.ai/api/v1/models").openConnection() as HttpURLConnection
                conn.connectTimeout=15000;conn.readTimeout=20000
                val arr=JSONObject(conn.inputStream.bufferedReader().use{it.readText()}).getJSONArray("data")
                conn.disconnect()
                val choices=mutableListOf<Triple<String,String,Int>>()
                for(i in 0 until arr.length()){
                    val x=arr.getJSONObject(i);val p=x.optJSONObject("pricing")?:continue
                    val id=x.optString("id")
                    val prompt=p.optString("prompt").toDoubleOrNull()?:continue
                    val completion=p.optString("completion").toDoubleOrNull()?:continue
                    if(prompt!=0.0||completion!=0.0)continue
                    val rank=when{
                        id.startsWith("deepseek/")->0
                        id.startsWith("google/")->1
                        id.startsWith("qwen/")->2
                        id.startsWith("moonshotai/")->3
                        id.startsWith("z-ai/")->4
                        id.startsWith("minimax/")->5
                        id.startsWith("nvidia/")->6
                        id.startsWith("meta-llama/")->7
                        id.startsWith("mistralai/")->8
                        else->20
                    }
                    val title=x.optString("name",id)
                    choices.add(Triple(id,title,rank))
                }
                choices.sortWith(compareBy<Triple<String,String,Int>>{it.third}.thenBy{it.first})
                val preferred=choices.filter{it.third<20}
                val shown=(if(preferred.isNotEmpty())preferred else choices).take(35)
                runOnUiThread{
                    if(shown.isEmpty()){
                        AlertDialog.Builder(this).setTitle("没有可用的免费模型")
                            .setMessage("官方目录目前未返回零价格模型，请检查网络或稍后再试。")
                            .setPositiveButton("好的",null).show()
                    }else{
                        shown.forEach{modelNames[it.first]=it.second}
                        AlertDialog.Builder(this).setTitle("精选免费模型 · 实时检测零价格")
                            .setItems(shown.map{"${readableModel(it.first)}\n免费 · ${it.first}"}.toTypedArray()){_,i->choose(shown[i].first)}
                            .setNegativeButton("取消",null).show()
                    }
                }
            }catch(e:Exception){runOnUiThread{Toast.makeText(this,"模型查询失败：${e.message}",Toast.LENGTH_LONG).show()}}
        }
    }
    private fun ask(){
        if(busy)return
        val question=input.text.toString().trim()
        if(question.isEmpty())return
        val secret=secureKey()
        if(secret.isEmpty()){keyDialog();return}
        val all=items()
        val recent=JSONArray()
        for(i in maxOf(0,all.length()-18) until all.length()){
            val entry=all.optJSONObject(i)?:continue
            if(entry.optString("role")=="assistant"||entry.optString("role")=="user")
                recent.put(JSONObject().put("role",entry.optString("role")).put("content",entry.optString("content")))
        }
        recent.put(JSONObject().put("role","user").put("content",question))
        all.put(JSONObject().put("role","user").put("content",question))
        if(all.length()==1)chats.getJSONObject(current).put("title",question.take(24))
        persist()
        bubble(question,true)
        input.setText("")
        val answerView=bubble("● 正在连接模型…",false)
        busy=true;send.isEnabled=false;send.alpha=.5f
        val selected=model
        val thisRequest=++requestId
        thread{
            var connection:HttpURLConnection?=null
            try{
                connection=URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
                activeConnection=connection
                connection.requestMethod="POST";connection.doOutput=true
                connection.connectTimeout=17000;connection.readTimeout=80000
                connection.setRequestProperty("Authorization","Bearer "+secret)
                connection.setRequestProperty("Content-Type","application/json")
                connection.setRequestProperty("Accept","text/event-stream")
                connection.setRequestProperty("HTTP-Referer","https://github.com/leiguo395-hue/OmniFusion-AI")
                connection.setRequestProperty("X-Title","OmniFusion AI")
                val payload=JSONObject().put("model",selected).put("stream",true).put("messages",recent)
                connection.outputStream.use{it.write(payload.toString().toByteArray(Charsets.UTF_8))}
                val http=connection.responseCode
                if(http !in 200..299){
                    val error=connection.errorStream?.bufferedReader()?.use{it.readText()}?:"无错误详情"
                    throw Exception("HTTP $http · "+error.take(280))
                }
                val result=StringBuilder()
                val thought=StringBuilder()
                var serverError=""
                connection.inputStream.bufferedReader().use{reader->
                    while(true){
                        val line=reader.readLine()?:break
                        if(!line.startsWith("data:"))continue
                        val body=line.substringAfter("data:").trim()
                        if(body=="[DONE]")break
                        try{
                            val json=JSONObject(body)
                            if(json.has("error")){serverError=json.optJSONObject("error")?.optString("message","模型返回错误")?:"模型返回错误";break}
                            val choice=json.optJSONArray("choices")?.optJSONObject(0)
                            val delta=choice?.optJSONObject("delta")
                            val piece=delta?.optString("content","")?:""
                            if(piece.isNotEmpty()&&piece!="null")result.append(piece)
                            val reason=delta?.optString("reasoning","")?:""
                            if(reason.isNotEmpty()&&reason!="null")thought.append(reason)
                            val now=System.currentTimeMillis()
                            if(now-lastRender>130){
                                val snapshot=result.toString().ifBlank{"● 模型已连接，正在生成…"}
                                runOnUiThread{if(thisRequest==requestId)answerView.text=snapshot}
                                lastRender=now
                            }
                        }catch(_:Exception){}
                    }
                }
                if(serverError.isNotBlank())throw Exception(serverError)
                if(result.isEmpty())throw Exception("模型未返回正文；可能已达到免费额度、正在限流，或当前模型不支持此请求")
                val final=result.toString()
                runOnUiThread{
                    if(thisRequest==requestId){
                        answerView.text=final
                        all.put(JSONObject().put("role","assistant").put("content",final)
                            .put("reason",thought.toString().take(10000)))
                        persist()
                    }
                }
            }catch(e:Exception){
                val error=(e.message?:"网络异常").take(380)
                runOnUiThread{
                    if(thisRequest==requestId){
                        answerView.text="⚠ 请求失败：$error\n\n建议换一个精选免费模型，或者检查 OpenRouter 免费额度。"
                        // Failed requests are shown in the UI but not added to future model context.
                        all.put(JSONObject().put("role","assistant").put("content",answerView.text.toString()))
                        persist()
                    }
                }
            }finally{
                connection?.disconnect();activeConnection=null
                runOnUiThread{
                    if(thisRequest==requestId){
                        busy=false;send.isEnabled=true;send.alpha=1f
                        stream.post{stream.fullScroll(View.FOCUS_DOWN)}
                    }
                }
            }
        }
    }
    private fun createPage(){
        heading("创作工作台 ✨","从灵感到专业提示词。媒体生成需另接可用的 API。")
        val cards=vertical().apply{setPadding(dp(18),0,dp(18),dp(10))}
        val choices=listOf(
            Triple("✧  视觉创意","生成摄影、插画、海报的中英双语提示词","请作为专业视觉艺术总监，把我的想法扩展成高质量生图提示词，含构图、风格、镜头、光照、色彩和负面提示词："),
            Triple("▣  视频分镜","规划视频的镜头、画面和运镜","请作为电影导演，把我的构想写成完整短视频分镜表，含秒数、景别、机位、运镜、主体、氛围、声音和视频模型提示词："),
            Triple("⌘  编程工作台","代码、调试、工程架构与分析","请作为资深软件工程师解决这个问题，提供可运行代码及测试方法："),
            Triple("◈  深度分析","复杂问题拆解、多角度推理与建议","请把以下复杂问题分解成假设、关键证据、方案对比、风险和最终建议；对于不确定的结论明确标注：")
        )
        choices.forEach{(title,desc,prefix)->
            val box=vertical().apply{
                setPadding(dp(18),dp(18),dp(18),dp(18));background=shape(panel,20)
                setOnClickListener{navigate(0);input.setText(prefix);input.setSelection(input.length())}
            }
            box.addView(text(title+"   ↗",19f,white,true));box.addView(text(desc,13f,dim),lp(9))
            cards.addView(box,lp(12))
        }
        cards.addView(text("说明：此版本不假装拥有尚未接入的生图或视频生成 API。",12f,dim),lp(17))
        val scroll=ScrollView(this);scroll.addView(cards);body.addView(scroll)
    }
    private fun historyPage(){
        heading("聊天档案 ▤","会话保存在本机，点击继续，长按删除。")
        val cards=vertical().apply{setPadding(dp(18),0,dp(18),dp(18))}
        for(i in chats.length()-1 downTo 0){
            val item=chats.getJSONObject(i)
            val b=vertical().apply{
                setPadding(dp(16),dp(14),dp(16),dp(14));background=shape(panel)
                setOnClickListener{current=i;persist();navigate(0)}
                setOnLongClickListener{
                    AlertDialog.Builder(this@MainActivity).setTitle("删除这段聊天？")
                        .setPositiveButton("删除"){_,_->
                            chats.remove(i);if(chats.length()==0)chats.put(newChat())
                            current=0;persist();navigate(2)
                        }.setNegativeButton("取消",null).show();true
                }
            }
            b.addView(text("◉  "+item.optString("title","新对话"),16f,white,true))
            b.addView(text("${item.getJSONArray("items").length()} 条消息 · 点击打开",12f,dim),lp(7))
            cards.addView(b,lp(10))
        }
        val sc=ScrollView(this);sc.addView(cards);body.addView(sc)
    }
    private fun settingsPage(){
        heading("控制中心 ⚙","所有模型、密钥和聊天数据都由你管理。")
        val box=vertical().apply{setPadding(dp(18),0,dp(18),dp(16))}
        box.addView(button(if(secureKey().isEmpty())"🔐  添加 API Key" else "🔐  修改 API Key"){keyDialog()},lp(8))
        box.addView(button("◆  模型：${model.substringAfterLast('/')}"){modelDialog()},lp(12))
        box.addView(text("数据管理",20f,white,true),lp(28))
        box.addView(button("↗  导出全部对话"){
            val sb=StringBuilder("OmniFusion AI 聊天导出\n\n")
            for(i in 0 until chats.length()){
                val c=chats.getJSONObject(i);sb.append("# "+c.optString("title")+"\n")
                val messages=c.getJSONArray("items")
                for(j in 0 until messages.length()){
                    val m=messages.getJSONObject(j)
                    sb.append(m.optString("role")+": "+m.optString("content")+"\n\n")
                }
            }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{
                type="text/plain";putExtra(Intent.EXTRA_TEXT,sb.toString())
            },"分享聊天记录"))
        },lp(12))
        box.addView(button("⌫  清空所有历史"){
            AlertDialog.Builder(this).setTitle("确定删除所有对话？")
                .setPositiveButton("确定"){_,_->chats=JSONArray().put(newChat());current=0;persist();navigate(3)}
                .setNegativeButton("取消",null).show()
        },lp(12))
        box.addView(text("V1.0 · OmniFusion\n\n支持：流式文字、免费模型目录、聊天存储、加密密钥、复制与多页面。\n\n仅显示 API 真正返回的推理内容，不能展示模型隐藏思维链。媒体生成和联网搜索尚未接入。免费模型可能限速、变更或不可用。",13f,dim),lp(28))
        val sc=ScrollView(this);sc.addView(box);body.addView(sc)
    }
}
