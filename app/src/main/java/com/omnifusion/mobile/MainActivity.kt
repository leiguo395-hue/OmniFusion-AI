package com.omnifusion.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val bg = Color.rgb(15, 18, 29)
    private val panel = Color.rgb(28, 33, 49)
    private val muted = Color.rgb(160, 169, 191)
    private val accent = Color.rgb(106, 130, 255)
    private lateinit var messagesView: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var prompt: EditText
    private lateinit var send: Button
    private lateinit var modelPicker: Spinner
    private lateinit var status: TextView
    private val history = JSONArray()
    private val models = arrayOf("自动免费 · OpenRouter", "手动填写模型 ID")
    private var selectedModel = "openrouter/free"
    private var busy = false

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 18): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun label(text: String, size: Float = 15f, color: Int = Color.WHITE) =
        TextView(this).apply { this.text = text; textSize = size; setTextColor(color); setTextIsSelectable(true) }
    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("OmniFusion", text))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }
    private fun apiKey(): String = getSharedPreferences("private", MODE_PRIVATE).getString("key", "") ?: ""
    private fun editKey() {
        val box = EditText(this).apply {
            hint = "sk-or-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(apiKey())
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        AlertDialog.Builder(this).setTitle("OpenRouter API Key")
            .setMessage("仅保存在本机应用私有存储，不会上传到 GitHub。请勿分享密钥。")
            .setView(box).setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                getSharedPreferences("private", MODE_PRIVATE).edit().putString("key", box.text.toString().trim()).apply()
                status.text = if (apiKey().isBlank()) "未配置密钥" else "已配置密钥 · 免费优先"
            }.show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(label("✦ OmniFusion", 23f).apply { setTypeface(null, Typeface.BOLD) })
        status = label(if (apiKey().isBlank()) "未配置密钥" else "已配置密钥 · 免费优先", 12f, muted)
        titleColumn.addView(status)
        header.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        val settings = Button(this).apply { text = "密钥"; setOnClickListener { editKey() } }
        header.addView(settings)
        root.addView(header)
        val modelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        modelPicker = Spinner(this)
        modelPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        modelPicker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) selectedModel = "openrouter/free"
                else {
                    val edit = EditText(this@MainActivity).apply { setText(selectedModel); setSingleLine(true) }
                    AlertDialog.Builder(this@MainActivity).setTitle("模型 ID")
                        .setView(edit).setNegativeButton("取消", null)
                        .setPositiveButton("确定") { _, _ -> selectedModel = edit.text.toString().trim() }.show()
                }
            }
        }
        modelRow.addView(modelPicker, LinearLayout.LayoutParams(0, dp(52), 1f))
        modelRow.addView(Button(this).apply {
            text = "新对话"
            setOnClickListener {
                historyClear()
                messagesView.removeAllViews()
                addBubble("新的对话开始啦。长按消息可复制。", false)
            }
        })
        root.addView(modelRow)
        scroll = ScrollView(this).apply { fillViewport = true }
        messagesView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        scroll.addView(messagesView)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addBubble("你好！我是 OmniFusion 测试版。支持长按复制、上下文聊天和免费模型调用。", false)
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            background = rounded(panel, 22)
            setPadding(dp(10), dp(6), dp(8), dp(6))
        }
        prompt = EditText(this).apply {
            hint = "给 OmniFusion 发消息…"
            setHintTextColor(muted)
            setTextColor(Color.WHITE)
            textSize = 15f
            minLines = 1
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = null
        }
        composer.addView(prompt, LinearLayout.LayoutParams(0, -2, 1f))
        send = Button(this).apply { text = "➤"; setOnClickListener { sendMessage() } }
        composer.addView(send)
        root.addView(composer)
        setContentView(root)
    }
    private fun historyClear() { while (history.length() > 0) history.remove(0) }
    private fun addBubble(message: String, user: Boolean) {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (user) Gravity.END else Gravity.START
            setPadding(0, dp(7), 0, dp(7))
        }
        val tag = label(if (user) "你" else "✦ OmniFusion", 12f, muted)
        outer.addView(tag)
        val text = label(message, 16f).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(if (user) accent else panel)
            setOnLongClickListener { copy(message); true }
        }
        outer.addView(text, LinearLayout.LayoutParams(-2, -2).apply { width = resources.displayMetrics.widthPixels - dp(70) })
        val copyButton = label("复制", 12f, muted).apply {
            setPadding(dp(8), dp(5), dp(8), 0)
            setOnClickListener { copy(message) }
        }
        outer.addView(copyButton)
        messagesView.addView(outer)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun sendMessage() {
        if (busy) return
        val question = prompt.text.toString().trim()
        if (question.isBlank()) return
        val key = apiKey()
        if (key.isBlank()) { editKey(); return }
        if (selectedModel.isBlank()) { Toast.makeText(this, "请填写模型 ID", Toast.LENGTH_SHORT).show(); return }
        val requestMessages = JSONArray(history.toString())
        requestMessages.put(JSONObject().put("role", "user").put("content", question))
        prompt.setText("")
        addBubble(question, true)
        busy = true
        send.isEnabled = false
        status.text = "正在思考…"
        thread {
            var conn: HttpURLConnection? = null
            try {
                conn = URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 20000
                conn.readTimeout = 120000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $key")
                val payload = JSONObject().put("model", selectedModel).put("messages", requestMessages).toString()
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream.bufferedReader().use { it.readText() }
                if (code !in 200..299) throw Exception("HTTP $code: ${response.take(350)}")
                val answer = JSONObject(response).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "").ifBlank { "模型没有返回文字。" }
                runOnUiThread {
                    history.put(JSONObject().put("role", "user").put("content", question))
                    history.put(JSONObject().put("role", "assistant").put("content", answer))
                    addBubble(answer, false)
                }
            } catch (e: Exception) {
                runOnUiThread { addBubble("请求失败：${e.message}", false) }
            } finally {
                conn?.disconnect()
                runOnUiThread {
                    busy = false
                    send.isEnabled = true
                    status.text = "免费模型 · ${selectedModel}"
                }
            }
        }
    }
}
