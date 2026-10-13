package com.omnifusion.mobile

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Build
import android.view.WindowManager
import android.view.WindowInsets
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import android.widget.FrameLayout
import com.example.liquidglass.LiquidGlassView
import android.graphics.Typeface
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
    private val bg get() = if(prefs.getBoolean("lightMode",false)) Color.rgb(238,241,248) else Color.rgb(11,14,24)
    private val panel get() = if(prefs.getBoolean("lightMode",false)) Color.rgb(255,255,255) else Color.rgb(27,33,50)
    private val accent get() = try { Color.parseColor(prefs.getString("themeAccent","#786FF7")) } catch(_:Exception) { Color.rgb(120,111,247) }
    private val white get() = if(prefs.getBoolean("lightMode",false)) Color.rgb(25,32,48) else Color.rgb(244,246,255)
    private val dim get() = if(prefs.getBoolean("lightMode",false)) Color.rgb(77,87,108) else Color.rgb(154,164,193)
    private val prefs by lazy { getSharedPreferences("omni", MODE_PRIVATE) }
    @Volatile private var routingCatalog: List<ModelRouter.Model> = emptyList()
    private var model = "openrouter/free"
    private var thinking = "high"
    private var assistantStyle = "balanced"
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
    private var routeStatus: TextView? = null
    private fun updateRouteStatus(value:String){ routeStatus?.text=value }
    private lateinit var stream: ScrollView
    private lateinit var transcript: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: TextView
    private fun dp(v:Int) = (v*resources.displayMetrics.density).toInt()
    private fun shape(color:Int, radius:Int=18) = GradientDrawable().apply {setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun text(s:String, size:Float=15f, color:Int=white, bold:Boolean=false) = TextView(this).apply {
        setText(s);textSize=size;setTextColor(color);if(bold)setTypeface(null,Typeface.BOLD);gravity=Gravity.CENTER_VERTICAL;isClickable=false
    }
    private fun vertical()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun horizontal()=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun lp(top:Int=0)=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(top)}
    private fun button(s:String, action:()->Unit)=text(s,14f,white,true).apply{
        gravity=Gravity.CENTER;setPadding(dp(13),dp(11),dp(13),dp(11))
        background=RippleDrawable(ColorStateList.valueOf(accent and 0x00FFFFFF or 0x55000000),shape(panel,14),null)
        minHeight=dp(48);isClickable=true;isFocusable=true
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
    // Prevent pasted prompts and multiline text from becoming HTTP Authorization headers.
    private fun validRouterKey(key:String):Boolean =
        key.length in 12..512 && key.startsWith("sk-or-") &&
        key.all { it.code in 33..126 } && !key.contains(" ")

    private fun requireRouterKey():String? {
        val key=secureKey()
        if(!validRouterKey(key)){
            AlertDialog.Builder(this).setTitle("API Key 配置错误")
                .setMessage("当前保存的内容不是有效格式的 OpenRouter API Key。请粘贴以 sk-or- 开头的密钥，不要粘贴提示词或聊天内容。")
                .setPositiveButton("重新设置"){_,_->keyDialog()}
                .setNegativeButton("取消",null).show()
            return null
        }
        return key
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
            .setView(box).setPositiveButton("保存"){_,_->
                val candidate=edit.text.toString().trim()
                if(validRouterKey(candidate)){storeKey(candidate);navigate(screen)}
                else AlertDialog.Builder(this).setTitle("密钥格式不正确")
                    .setMessage("OpenRouter 密钥必须以 sk-or- 开头，只包含单行可打印字符。请不要粘贴提示词。")
                    .setPositiveButton("重新输入"){_,_->keyDialog()}
                    .setNegativeButton("取消",null).show()
            }
            .setNegativeButton("取消",null).show()
    }
    private fun newChat()=JSONObject().put("title","新对话").put("items",JSONArray())
    private fun items()=chats.getJSONObject(current).getJSONArray("items")
    private fun persist(){prefs.edit().putString("chats",chats.toString()).putInt("current",current).apply()}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);WindowCompat.setDecorFitsSystemWindows(window,false);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);window.statusBarColor=bg;window.navigationBarColor=bg
        model=prefs.getString("model","openrouter/free") ?: "openrouter/free"
        thinking=prefs.getString("thinking","high") ?: "high"
        assistantStyle=prefs.getString("style","balanced") ?: "balanced"
        chats=try{JSONArray(prefs.getString("chats","[]"))}catch(_:Exception){JSONArray()}
        if(chats.length()==0)chats.put(newChat())
        current=prefs.getInt("current",0).coerceIn(0,chats.length()-1)
        navigate(0)
        warmFreeModelCatalog()
    }
    private fun warmFreeModelCatalog(){
        // Fetch once at launch so AUTO does not silently remain a random router.
        thread {
            try{
                val conn=URL("https://openrouter.ai/api/v1/models").openConnection() as HttpURLConnection
                conn.connectTimeout=12000;conn.readTimeout=12000
                val arr=JSONObject(conn.inputStream.bufferedReader().use{it.readText()}).getJSONArray("data")
                conn.disconnect()
                val free=mutableListOf<String>()
                for(i in 0 until arr.length()){
                    val x=arr.optJSONObject(i)?:continue
                    val p=x.optJSONObject("pricing")?:continue
                    if(p.optString("prompt").toDoubleOrNull()==0.0 && p.optString("completion").toDoubleOrNull()==0.0){
                        val id=x.optString("id")
                        if(id.isNotBlank())free.add(id)
                    }
                }
                val complete=ModelRouter.fromCatalog(arr)
                runOnUiThread{
                    routingCatalog=complete
                    verifiedFreeModels.addAll(complete.filter{it.free}.map{it.id})
                }
            }catch(_:Exception){/* Offline fallback remains OpenRouter free route. */}
        }
    }
    private fun navigate(page:Int){
        if(busy && page!=0){Toast.makeText(this,"回复中，请等待完成",Toast.LENGTH_SHORT).show();return}
        screen=page;window.statusBarColor=bg;window.navigationBarColor=bg
        root=vertical().apply{
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(bg,accent and 0x00FFFFFF or 0xFF15152A.toInt(),bg))
        }
        val top=horizontal().apply{setPadding(dp(20),dp(13),dp(18),dp(13))}
        top.addView(text("✦  OmniFusion",24f,white,true),LinearLayout.LayoutParams(0,-2,1f))
        top.addView(button("⚙"){navigate(3)})
        root.addView(top)
        body=vertical();root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        val nav=horizontal().apply{setPadding(dp(8),dp(7),dp(8),dp(10));background=null}
        val tabs=listOf("◉\n聊天","✧\n创作","▤\n历史","⚙\n设置")
        tabs.forEachIndexed{i,s->
            val b=text(s,12f,if(page==i)white else dim,page==i).apply{
                gravity=Gravity.CENTER;minHeight=dp(52)
                background=RippleDrawable(ColorStateList.valueOf(accent and 0x00FFFFFF or 0x44000000),
                    if(page==i)shape(accent and 0x00FFFFFF or 0x33000000,12) else shape(panel,12),null)
                setOnClickListener{performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);if(screen!=i)navigate(i)}
            }
            nav.addView(b,LinearLayout.LayoutParams(0,dp(56),1f))
        }
        val glassNav=LiquidGlassView(this).apply {
            enableDynamicBackground=true
            refractionHeight=dp(14).toFloat()
            bevelWidth=dp(19).toFloat()
            dispersionStrength=0.16f
            setGlassTint(accent,0.12f)
            addView(nav,FrameLayout.LayoutParams(-1,-2))
        }
        root.addView(glassNav)
        setContentView(root)
        // Edge-to-edge layout: apply IME and system bars exactly once to the root.
        // Avoid unreliable screen-height calculations and double adjustResize.
        ViewCompat.setOnApplyWindowInsetsListener(root){ _, insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime=insets.getInsets(WindowInsetsCompat.Type.ime())
            val open=insets.isVisible(WindowInsetsCompat.Type.ime())
            glassNav.visibility=if(open)View.GONE else View.VISIBLE
            val bottom=if(open)ime.bottom else bars.bottom
            if(root.paddingTop!=bars.top || root.paddingBottom!=bottom)
                root.setPadding(0,bars.top,0,bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        when(page){0->chatPage();1->createPage();2->historyPage();else->settingsPage()}
        body.alpha=0f;body.translationY=dp(9).toFloat()
        body.animate().alpha(1f).translationY(0f).setDuration(210).setInterpolator(DecelerateInterpolator()).start()
    }
    private fun heading(title:String,description:String){
        body.addView(text(title,27f,white,true).apply{setPadding(dp(19),dp(10),dp(19),0)})
        body.addView(text(description,13f,dim).apply{setPadding(dp(19),dp(6),dp(19),dp(19))})
    }
    private fun readableModel(id:String):String {
        if(id=="openrouter/free")return "AUTO · 免费模型智能路由"
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
        row.addView(button("🎨"){imageCreator()})
        row.addView(button("⚡"){runCrossfire()})
        row.addView(button("＋"){chats.put(newChat());current=chats.length()-1;persist();navigate(0)})
        body.addView(row)
        stream=ScrollView(this).apply{isFillViewport=true;isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER}
        transcript=vertical().apply{setPadding(dp(17),dp(10),dp(17),dp(15))}
        stream.addView(transcript);body.addView(stream,LinearLayout.LayoutParams(-1,0,1f))
        if(items().length()==0){
            transcript.addView(text("你的灵感，从这里开始。",25f,white,true),lp(38))
            transcript.addView(text("提问、创作、编程、学习。选择适合的免费云端模型。",15f,dim),lp(15))
        }else redraw()
        // Lightweight routing visibility; no network call is triggered by opening this panel.
        routeStatus=text("◈ 调度待命 · "+(if(model=="openrouter/free")"AUTO 免费优先" else "手动锁定")+
            " · 模型目录 "+routingCatalog.size+" 个 · 额度未知",11f,dim).apply{
            setPadding(dp(18),dp(5),dp(18),dp(5))
            setOnClickListener{
                AlertDialog.Builder(this@MainActivity).setTitle("模型调度状态")
                    .setMessage(text.toString()+"\\n\\n健康度与剩余额度仅在服务商提供可靠数据时显示；未检测到的数据不猜测。")
                    .setPositiveButton("确定",null).show()
            }
        }
        body.addView(routeStatus)
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
            .setItems(arrayOf("✦ AUTO 自动选择免费模型","🏆 精选免费模型（在线查询）","🌐 全部模型目录（含付费，需确认）","✎ 自定义模型 ID（可能付费）")){_,choice->
                when(choice){
                    0->choose("openrouter/free")
                    1->freeModels()
                    2->allModels()
                    3->{
                        val field=EditText(this).apply{setText(model);setSingleLine()}
                        AlertDialog.Builder(this).setTitle("模型 ID").setView(field)
                            .setPositiveButton("应用"){_,_->choose(field.text.toString().trim())}.setNegativeButton("取消",null).show()
                    }
                }
            }.show()
    }
    private fun choose(id:String){
        if(id.isBlank())return
        if(id!="openrouter/free" && !id.endsWith(":free") && id !in verifiedFreeModels){
            AlertDialog.Builder(this).setTitle("可能产生 API 费用")
                .setMessage("此模型不是已核实的零价格模型。继续会使用你在 OpenRouter 的余额。确定手动启用吗？")
                .setPositiveButton("确认使用"){_,_->saveModel(id)}
                .setNegativeButton("取消",null).show()
        } else saveModel(id)
    }
    private val verifiedFreeModels=mutableSetOf<String>()
    private fun saveModel(id:String){model=id;prefs.edit().putString("model",id).apply();navigate(0)}
    private fun allModels(){loadModels(false)}
    private fun freeModels(){loadModels(true)}
    private fun loadModels(onlyFree:Boolean){
    Toast.makeText(this,if(onlyFree)"正在查询零价格模型…" else "正在加载各家模型目录…",Toast.LENGTH_SHORT).show()
        thread{
            try{
                val conn=URL("https://openrouter.ai/api/v1/models").openConnection() as HttpURLConnection
                conn.connectTimeout=15000;conn.readTimeout=20000
                val arr=JSONObject(conn.inputStream.bufferedReader().use{it.readText()}).getJSONArray("data")
                conn.disconnect()
                val choices=mutableListOf<Triple<String,String,Int>>()
                val freeIds=mutableSetOf<String>()
                for(i in 0 until arr.length()){
                    val x=arr.getJSONObject(i);val p=x.optJSONObject("pricing")?:continue
                    val id=x.optString("id")
                    val prompt=p.optString("prompt").toDoubleOrNull()?:continue
                    val completion=p.optString("completion").toDoubleOrNull()?:continue
                    val isFree=prompt==0.0 && completion==0.0
                    if(onlyFree && !isFree)continue
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
                    if(isFree)freeIds.add(id)
                }
                choices.sortWith(compareBy<Triple<String,String,Int>>{it.third}.thenBy{it.first})
                val preferred=choices.filter{it.third<20}
                val shown=(if(onlyFree && preferred.isNotEmpty())preferred else choices).take(if(onlyFree)35 else 100)
                runOnUiThread{
                    if(shown.isEmpty()){
                        AlertDialog.Builder(this).setTitle("没有可用的免费模型")
                            .setMessage("官方目录目前未返回零价格模型，请检查网络或稍后再试。")
                            .setPositiveButton("好的",null).show()
                    }else{
                        verifiedFreeModels.addAll(freeIds)
                        shown.forEach{modelNames[it.first]=it.second}
                        AlertDialog.Builder(this).setTitle(if(onlyFree)"精选免费模型" else "多厂商模型中心 · 价格以官方为准")
                            .setItems(shown.map{"${readableModel(it.first)}\\n${if(it.first in freeIds)"免费" else "可能收费"} · ${it.first}"}.toTypedArray()){_,i->choose(shown[i].first)}
                            .setNegativeButton("取消",null).show()
                    }
                }
            }catch(e:Exception){runOnUiThread{Toast.makeText(this,"模型查询失败：${e.message}",Toast.LENGTH_LONG).show()}}
        }
    }
    private fun autoModel(question:String):String {
        // Catalog-backed capability gate. Never send image requests to text-only models.
        val task=ModelRouter.taskOf(question)
        if(task==ModelRouter.Task.IMAGE_GEN) return "openrouter/free" // UI sends this to the dedicated image generator.
        return ModelRouter.choose(task,routingCatalog) ?: "openrouter/free"
    }
    /**
     * Resilient streaming chat: each request receives its own connection.
     * Automatic fallback is free-only and same-capability; a manually selected model is locked.
     * No speculative model warming: OpenRouter is a remote stateless HTTP API.
     */
    /**
     * User-triggered two-model crossfire. Both requests run concurrently.
     * Free catalog validation is mandatory; this mode will never spend on paid models.
     */
    private fun runCrossfire(){
        if(busy)return
        val prompt=input.text.toString().trim()
        if(prompt.isBlank()){Toast.makeText(this,"请先输入需要交叉验证的问题",Toast.LENGTH_SHORT).show();return}
        if(ModelRouter.taskOf(prompt)==ModelRouter.Task.IMAGE_GEN){
            imageCreator(prompt)
            return
        }
        val key=requireRouterKey() ?: return
        val task=ModelRouter.taskOf(prompt)
        val ids=mutableListOf<String>()
        repeat(2){
            val candidate=ModelRouter.choose(task,routingCatalog,excluded=ids.toSet())
            if(candidate!=null)ids.add(candidate)
        }
        if(ids.size<2){
            AlertDialog.Builder(this).setTitle("交叉火力未启动")
                .setMessage("至少需要两个支持当前任务的零价格模型。请检查网络和免费模型目录。不会改用付费模型。")
                .setPositiveButton("刷新模型目录"){_,_->warmFreeModelCatalog()}
                .setNegativeButton("取消",null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("⚡ 并行交叉火力")
            .setMessage("同时请求两个已验证免费模型：\\n"+ids.joinToString("\\n")+
                "\\n\\n免费模型仍可能有速率限制。是否执行？")
            .setPositiveButton("同时请求"){_,_->
                val history=items()
                val request=JSONArray().put(JSONObject().put("role","system").put("content",instruction()))
                for(i in maxOf(0,history.length()-12) until history.length()){
                    val item=history.optJSONObject(i)?:continue
                    if(item.optString("role") in listOf("user","assistant"))
                        request.put(JSONObject().put("role",item.optString("role"))
                            .put("content",item.optString("content")))
                }
                request.put(JSONObject().put("role","user").put("content",prompt))
                history.put(JSONObject().put("role","user").put("content",prompt))
                bubble(prompt,true)
                input.setText("")
                val display=bubble("⚡ 两路模型并发执行中…",false)
                busy=true;send.isEnabled=false
                val requestIndex=++requestId
                updateRouteStatus("⚡ 交叉火力 · "+task+" · 双模型并发中")
                persist()
                thread {
                    val results=CrossfireEngine.compare(key,request,ids)
                    val report=buildString {
                        append("⚡ 交叉火力 · 双模型独立结果\\n")
                        for((index,result) in results.withIndex()){
                            append("\\n—— ").append(index+1).append(". ")
                                .append(result.model).append(" (").append(result.durationMs).append("ms) ——\\n")
                            append(if(result.error.isBlank())result.text else "⚠ "+result.error)
                            append("\\n")
                        }
                        append("\\n结果并列呈现，差异需要进一步核实；并行不等于自动共识。")
                    }
                    runOnUiThread {
                        if(requestIndex==requestId){
                            display.text=report
                            history.put(JSONObject().put("role","assistant").put("content",report))
                            persist()
                            updateRouteStatus("⚡ 交叉火力 · "+results.count{it.error.isBlank()}+
                                "/2 成功 · "+results.maxOf{it.durationMs}+"ms")
                            busy=false;send.isEnabled=true;send.alpha=1f
                            stream.post{stream.fullScroll(View.FOCUS_DOWN)}
                        }
                    }
                }
            }.setNegativeButton("取消",null).show()
    }
    private fun ask(){
        if(busy)return
        val question=input.text.toString().trim()
        if(question.isEmpty())return
        val secret=requireRouterKey() ?: return
        val task=ModelRouter.taskOf(question)
        if(task==ModelRouter.Task.IMAGE_GEN){imageCreator(question);return}
        val all=items()
        val recent=JSONArray().put(JSONObject().put("role","system").put("content",instruction()))
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
        val locked=model!="openrouter/free"
        val candidates=if(locked)listOf(model) else {
            val selected=mutableListOf<String>()
            repeat(3){
                val next=ModelRouter.choose(task,routingCatalog,excluded=selected.toSet())
                if(next!=null) selected.add(next)
            }
            if(selected.isEmpty())selected.add("openrouter/free")
            selected
        }
        val started=System.currentTimeMillis()
        val answerView=bubble("◌ 正在连接 "+readableModel(candidates.first())+"…",false)
        val thisRequest=++requestId
        updateRouteStatus("◈ "+task+" · "+candidates.first()+" · 请求中 · 免费候选 "+candidates.size+" 个")
        busy=true;send.isEnabled=false;send.alpha=.5f
        thread {
            val switchLog=mutableListOf<String>()
            var finalAnswer:String?=null
            var usedModel=candidates.first()
            var reasoning=""
            var lastError=""
            try{
                for((index,id) in candidates.withIndex()){
                    if(thisRequest!=requestId)break
                    usedModel=id
                    var connection:HttpURLConnection?=null
                    val partial=StringBuilder()
                    val thoughts=StringBuilder()
                    try {
                        runOnUiThread {
                            if(thisRequest==requestId)answerView.text="◌ "+readableModel(id)+
                                " · "+(index+1)+"/"+candidates.size+
                                if(switchLog.isEmpty())" · 连接中…" else "\\n"+switchLog.joinToString("\\n")
                        }
                        connection=URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
                        activeConnection=connection
                        connection.requestMethod="POST";connection.doOutput=true
                        connection.connectTimeout=16000;connection.readTimeout=85000
                        connection.setRequestProperty("Authorization","Bearer "+secret)
                        connection.setRequestProperty("Content-Type","application/json")
                        connection.setRequestProperty("Accept","text/event-stream")
                        connection.setRequestProperty("HTTP-Referer","https://github.com/leiguo395-hue/OmniFusion-AI")
                        connection.setRequestProperty("X-Title","OmniFusion AI")
                        val payload=JSONObject().put("model",id).put("stream",true).put("messages",recent)
                        // A nonstandard reasoning parameter can cause free models to reject requests.
                        connection.outputStream.use{it.write(payload.toString().toByteArray(Charsets.UTF_8))}
                        val code=connection.responseCode
                        if(code !in 200..299) {
                            val detail=connection.errorStream?.bufferedReader()?.use{it.readText()} ?: "未提供错误详情"
                            throw Exception("HTTP $code: "+detail.take(140))
                        }
                        var streamError=""
                        connection.inputStream.bufferedReader().use{reader->
                            while(true){
                                val line=reader.readLine()?:break
                                if(!line.startsWith("data:"))continue
                                val event=line.substringAfter("data:").trim()
                                if(event=="[DONE]")break
                                try{
                                    val item=JSONObject(event)
                                    if(item.has("error")){
                                        streamError=item.optJSONObject("error")?.optString("message","流式响应错误")?:"流式响应错误"
                                        break
                                    }
                                    val delta=item.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                                    val word=delta?.optString("content","")?:""
                                    if(word.isNotEmpty()&&word!="null")partial.append(word)
                                    val thinkingText=delta?.optString("reasoning","")?:""
                                    if(thinkingText.isNotEmpty()&&thinkingText!="null")thoughts.append(thinkingText)
                                    val now=System.currentTimeMillis()
                                    if(now-lastRender>120 && partial.isNotEmpty()){
                                        val viewText=partial.toString()
                                        runOnUiThread{if(thisRequest==requestId)answerView.text=viewText}
                                        lastRender=now
                                    }
                                }catch(_:org.json.JSONException){}
                            }
                        }
                        if(streamError.isNotEmpty())throw Exception(streamError)
                        if(partial.isEmpty())throw Exception("模型未返回文本")
                        finalAnswer=partial.toString()
                        reasoning=thoughts.toString().take(10000)
                        break
                    }catch(e:Exception){
                        lastError=(e.message?:"网络故障").take(220)
                        // If tokens already streamed, rerouting would duplicate/contradict output.
                        // Also never override a manual lock or retry on invalid credentials.
                        val authError=lastError.contains("HTTP 401") || lastError.contains("HTTP 403") ||
                            lastError.contains("header value") || lastError.contains("Unexpected char")
                        if(partial.isNotEmpty() || locked || authError || index==candidates.lastIndex)break
                        switchLog.add("↪ "+id+" 不可用："+lastError.take(80)+"，切换免费备用模型")
                        val switching=index+1
                        runOnUiThread{
                            if(thisRequest==requestId)updateRouteStatus("◈ "+task+" · 重试 "+switching+
                                " · 原因："+lastError.take(56))
                        }
                    }finally{
                        connection?.disconnect()
                        activeConnection=null
                    }
                }
                val elapsed=System.currentTimeMillis()-started
                runOnUiThread{
                    if(thisRequest==requestId){
                        updateRouteStatus("◈ "+task+" · "+usedModel+" · "+
                            (if(finalAnswer!=null)"成功" else "失败")+" · "+elapsed+"ms · "+
                            (if(switchLog.isEmpty())"未切换" else "切换 "+switchLog.size+" 次"))
                        val explanation="\\n\\n—— 调度信息 ——\\n"+
                            "任务："+task+" · 模型："+usedModel+" · 耗时："+elapsed+"ms"+
                            if(switchLog.isEmpty())"" else "\\n"+switchLog.joinToString("\\n")
                        answerView.text=if(finalAnswer!=null)finalAnswer+explanation else
                            "⚠ 当前模型请求失败："+lastError+"\\n"+switchLog.joinToString("\\n")
                        all.put(JSONObject().put("role","assistant").put("content",
                            finalAnswer ?: "请求未完成："+lastError)
                            .put("reason",reasoning))
                        persist()
                    }
                }
            }finally{
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
        heading("创作工作台 ✨","真正的 AI 生图与创意工具。生成图片前会确认费用。")
        val cards=vertical().apply{setPadding(dp(18),0,dp(18),dp(10))}
        cards.addView(button("🎨  AI 真正生图 · 选择模型后生成"){imageCreator()},lp(8))
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
        cards.addView(text("生图只允许图片输出模型。价格与额度依 OpenRouter 实时数据为准，确认后才会请求；视频生成尚未接入。",12f,dim),lp(17))
        val scroll=ScrollView(this);scroll.addView(cards);body.addView(scroll)
    }
    private fun imageCreator(prefill:String=""){
        if(requireRouterKey()==null)return
        // Present only models whose official metadata advertises image OUTPUT.
        // Do not mistake image-input vision models for image generation.
        val candidates=ModelRouter.imageModels(routingCatalog)
        if(candidates.isEmpty()){
            AlertDialog.Builder(this).setTitle("生图模型目录尚未就绪")
                .setMessage("请确认网络、OpenRouter Key 与图片模型权限。稍后重试。不会自动改用文字模型，也不会产生费用。")
                .setPositiveButton("重试"){_,_->warmFreeModelCatalog()}
                .setNegativeButton("取消",null).show()
            return
        }
        val labels=candidates.take(60).map { it.id+(if(it.free)" · 免费价格标记" else " · 可能付费") }.toTypedArray()
        AlertDialog.Builder(this).setTitle("选择真正支持图片输出的模型")
            .setItems(labels){_,index ->
                val selected=candidates[index]
                imagePrompt(selected.id,selected.id,prefill)
            }.setNegativeButton("取消",null).show()
    }
    private fun imagePrompt(id:String,name:String,prefill:String=""){
        val prompt=EditText(this).apply{
            hint="详细描述你想生成的图片…";minLines=3;maxLines=6
            if(prefill.isNotBlank())setText(prefill)
            setPadding(dp(18),dp(16),dp(18),dp(16))
        }
        AlertDialog.Builder(this).setTitle("图片生成 · "+name.take(40))
            .setMessage("图片生成可能产生费用。请在 OpenRouter 查看此模型最新单图价格和余额；本 App 暂无硬性扣费上限。")
            .setView(prompt)
            .setPositiveButton("下一步"){_,_->
                val content=prompt.text.toString().trim()
                if(content.isBlank()){Toast.makeText(this,"请先输入图片描述",Toast.LENGTH_SHORT).show();return@setPositiveButton}
                AlertDialog.Builder(this).setTitle("确认调用付费图片 API？")
                    .setMessage("模型：$id\\n\\n此操作可能从你的 OpenRouter 余额扣费。是否继续？")
                    .setPositiveButton("同意生成"){_,_->generateImage(id,content)}
                    .setNegativeButton("取消",null).show()
            }.setNegativeButton("取消",null).show()
    }
    private fun generateImage(id:String,prompt:String){
        val secret=requireRouterKey() ?: return
        val progress=ProgressDialog(this).apply{
            setTitle("正在生成图片")
            setMessage("图片生成可能需要一分钟以上…")
            setCancelable(false);show()
        }
        thread {
            var conn:HttpURLConnection?=null
            try{
                conn=URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
                conn.requestMethod="POST";conn.doOutput=true;conn.connectTimeout=20000;conn.readTimeout=180000
                conn.setRequestProperty("Authorization","Bearer $secret")
                conn.setRequestProperty("Content-Type","application/json")
                val payload=JSONObject().put("model",id)
                    .put("messages",JSONArray().put(JSONObject().put("role","user").put("content",prompt)))
                    .put("modalities",JSONArray().put("image").put("text"))
                    .put("stream",false)
                conn.outputStream.use{it.write(payload.toString().toByteArray(Charsets.UTF_8))}
                val status=conn.responseCode
                if(status !in 200..299){
                    val detail=conn.errorStream?.bufferedReader()?.use{it.readText()}?:"未知错误"
                    throw Exception("HTTP $status · "+detail.take(300))
                }
                val response=JSONObject(conn.inputStream.bufferedReader().use{it.readText()})
                val message=response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                val images=message.optJSONArray("images")
                if(images==null || images.length()==0)throw Exception("模型没有返回图片："+message.optString("content").take(160))
                val item=images.getJSONObject(0)
                val encoded=item.optJSONObject("image_url")?.optString("url")
                    ?:item.optString("url")
                if(!encoded.startsWith("data:image/"))throw Exception("模型返回了非内嵌图片，请检查图片响应格式")
                val bytes=Base64.decode(encoded.substringAfter("base64,"),Base64.DEFAULT)
                val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:throw Exception("不支持的图片格式")
                runOnUiThread{
                    progress.dismiss()
                    val image=ImageView(this).apply{
                        setImageBitmap(bitmap);adjustViewBounds=true
                        scaleType=ImageView.ScaleType.FIT_CENTER
                    }
                    val frame=ScrollView(this).apply{addView(image)}
                    AlertDialog.Builder(this).setTitle("生成完成 · "+id)
                        .setView(frame)
                        .setPositiveButton("保存到相册"){_,_->
                            try{
                                val values=ContentValues().apply{
                                    put(MediaStore.Images.Media.DISPLAY_NAME,"OmniFusion_"+System.currentTimeMillis()+".png")
                                    put(MediaStore.Images.Media.MIME_TYPE,"image/png")
                                    if(Build.VERSION.SDK_INT>=29)put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/OmniFusion")
                                }
                                if(Build.VERSION.SDK_INT<29)throw Exception("Android 10 以下需要额外存储权限")
                                val uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:throw Exception("无法创建相册文件")
                                contentResolver.openOutputStream(uri)?.use{stream->
                                    if(!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))throw Exception("图片保存失败")
                                }?:throw Exception("无法打开相册写入")
                                Toast.makeText(this,"已保存到相册",Toast.LENGTH_LONG).show()
                            }catch(e:Exception){Toast.makeText(this,"保存失败："+e.message,Toast.LENGTH_LONG).show()}
                        }.setNegativeButton("关闭",null).show()
                }
            }catch(e:Exception){runOnUiThread{progress.dismiss();AlertDialog.Builder(this).setTitle("图片生成失败").setMessage(e.message).setPositiveButton("好的",null).show()}}
            finally{conn?.disconnect()}
        }
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
    private fun instruction():String {
        val style=when(assistantStyle){
            "brief"->"回答简练直接，优先结论和关键步骤，不堆砌套话。"
            "technical"->"以工程师方式回答：准确、可验证、给可执行方案和必要测试。"
            else->"自然中文交流，聪明、友好、清晰，有适度幽默但不油腻。"
        }
        return "你是 OmniFusion AI，一位认真、严谨、善于解决复杂问题的中文助手。"+
            "先判断用户的真正目标和约束，必要时拆解问题并内部检查结论。"+
            "事实、推测、未知要明确区分；不编造资料、工具调用、运行结果或链接。"+
            "数学和代码注意边界情况与验证；复杂任务先规划再执行，最终给可操作结果。"+
            "不展示隐藏思维链，但可以给简洁的依据、步骤、检查结果。"+
            "当模型能力或信息不足时明确说出，不要假装成功。"+style
    }
    private fun thinkingDialog(){
        val labels=arrayOf("⚡ 快速 · 低推理","⚖ 均衡 · 中推理","🧠 深度 · 高推理")
        val values=arrayOf("low","medium","high")
        AlertDialog.Builder(this).setTitle("思考强度（取决于模型支持）")
            .setSingleChoiceItems(labels,values.indexOf(thinking).coerceAtLeast(0)){dialog,index->
                thinking=values[index];prefs.edit().putString("thinking",thinking).apply()
                dialog.dismiss();navigate(3)
            }.setNegativeButton("取消",null).show()
    }
    private fun styleDialog(){
        val labels=arrayOf("自然聪明 · 日常交流","简洁直接 · 重点优先","严谨工程师 · 代码与分析")
        val values=arrayOf("balanced","brief","technical")
        AlertDialog.Builder(this).setTitle("交流风格")
            .setSingleChoiceItems(labels,values.indexOf(assistantStyle).coerceAtLeast(0)){dialog,index->
                assistantStyle=values[index];prefs.edit().putString("style",assistantStyle).apply()
                dialog.dismiss();navigate(3)
            }.setNegativeButton("取消",null).show()
    }
    private fun themeDialog(){
        val colors=arrayOf("#786FF7","#258DF1","#08A88A","#F28C49","#E36BA7","#373D51")
        val names=arrayOf("极光紫","深海蓝","翡翠绿","落日橙","樱花粉","曜石灰")
        val wrap=vertical().apply{setPadding(dp(18),dp(8),dp(18),dp(4))}
        val hex=EditText(this).apply{
            setSingleLine();hint="#786FF7";setText(prefs.getString("themeAccent","#786FF7"))
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        wrap.addView(text("选择预设或输入 6 位 HEX 颜色",14f,white),lp(4))
        val row=horizontal()
        colors.forEachIndexed{i,c->
            val swatch=TextView(this).apply{
                text="●";textSize=29f;setTextColor(Color.parseColor(c));gravity=Gravity.CENTER
                setOnClickListener{hex.setText(c);hex.setSelection(hex.length());performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)}
                contentDescription=names[i];minHeight=dp(48)
            }
            row.addView(swatch,LinearLayout.LayoutParams(0,dp(52),1f))
        }
        wrap.addView(row,lp(8));wrap.addView(hex,lp(6))
        val light=Switch(this).apply{text="浅色模式";setTextColor(white);isChecked=prefs.getBoolean("lightMode",false)}
        wrap.addView(light,lp(10))
        AlertDialog.Builder(this).setTitle("Liquid 主题实验室").setView(wrap)
            .setPositiveButton("应用"){_,_->
                val value=hex.text.toString().trim()
                if(!Regex("^#[0-9A-Fa-f]{6}$").matches(value)){
                    Toast.makeText(this,"请输入 #RRGGBB 格式颜色",Toast.LENGTH_LONG).show()
                }else{
                    prefs.edit().putString("themeAccent",value).putBoolean("lightMode",light.isChecked).apply()
                    navigate(screen)
                }
            }.setNegativeButton("取消",null).show()
    }
    private fun settingsPage(){
        heading("控制中心 ⚙","所有模型、密钥和聊天数据都由你管理。")
        val box=vertical().apply{setPadding(dp(18),0,dp(18),dp(16))}
        box.addView(button(if(secureKey().isEmpty())"🔐  添加 API Key" else "🔐  修改 API Key"){keyDialog()},lp(8))
        box.addView(button("◆  模型：${model.substringAfterLast('/')}"){modelDialog()},lp(12))
        box.addView(text("个性化 · Liquid 主题",20f,white,true),lp(26))
        box.addView(button("🎨  自定义主题色与明暗模式"){themeDialog()},lp(10))
        box.addView(button("🌐  浏览 GPT / Claude / Gemini / DeepSeek / Qwen"){allModels()},lp(10))
        box.addView(text("模型目录不代表已免费开通。付费模型会二次确认，所有请求使用你自己的 OpenRouter Key。",12f,dim),lp(8))
        box.addView(text("AI 大脑配置",20f,white,true),lp(28))
        box.addView(button("🧠  思考强度："+when(thinking){"low"->"快速";"medium"->"均衡";else->"深度"}){thinkingDialog()},lp(12))
        box.addView(button("✦  交流风格："+when(assistantStyle){"brief"->"简洁";"technical"->"严谨";else->"自然"}){styleDialog()},lp(12))
        box.addView(text("推理强度仅对支持的模型生效，部分免费模型可能忽略或拒绝参数。强推理不保证答案正确。",12f,dim),lp(9))
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
