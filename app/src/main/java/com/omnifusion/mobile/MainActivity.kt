package com.omnifusion.mobile

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var model: EditText
    private lateinit var key: EditText
    private val history = JSONArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(245, 246, 250))
        }
        root.addView(TextView(this).apply {
            text = "OmniFusion AI"
            textSize = 24f
            setTextColor(Color.BLACK)
        })
        model = field(root, "OpenRouter 模型", "openrouter/free")
        key = field(root, "OpenRouter API Key", "").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        output = TextView(this).apply {
            text = "欢迎使用！请输入 OpenRouter API Key。免费模型存在配额限制。\n"
            textSize = 16f
            setTextColor(Color.DKGRAY)
        }
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        input = field(root, "发送消息", "")
        root.addView(Button(this).apply { text = "发送"; setOnClickListener { sendMessage() } })
        root.addView(Button(this).apply {
            text = "清空对话"
            setOnClickListener {
                while (history.length() > 0) history.remove(0)
                output.text = "已清空\n"
            }
        })
        setContentView(root)
    }

    private fun field(root: LinearLayout, hintText: String, value: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setText(value)
            setSingleLine(true)
            textSize = 15f
            root.addView(this)
        }
    }

    private fun sendMessage() {
        val message = input.text.toString().trim()
        val secret = key.text.toString().trim()
        val selectedModel = model.text.toString().trim()
        if (message.isEmpty()) return
        if (secret.isEmpty() || selectedModel.isEmpty()) {
            Toast.makeText(this, "请填写 API Key 和模型", Toast.LENGTH_LONG).show()
            return
        }
        val messages = JSONArray(history.toString())
        messages.put(JSONObject().put("role", "user").put("content", message))
        output.append("\n我：$message\n")
        input.setText("")
        thread {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 90000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $secret")
                val payload = JSONObject().put("model", selectedModel).put("messages", messages).toString()
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val response = stream.bufferedReader().use { it.readText() }
                if (status !in 200..299) error("HTTP $status: ${response.take(240)}")
                val answer = JSONObject(response).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "无文本回复")
                runOnUiThread {
                    history.put(JSONObject().put("role", "user").put("content", message))
                    history.put(JSONObject().put("role", "assistant").put("content", answer))
                    output.append("\nAI：$answer\n")
                }
            } catch (e: Exception) {
                runOnUiThread { output.append("\n请求失败：${e.message}\n") }
            } finally {
                connection?.disconnect()
            }
        }
    }
}
