package com.omnifusion.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Explicit opt-in crossfire, NOT a model ensemble hidden in every chat request.
 * Each candidate gets the same frozen context; only preverified zero-price text
 * models may participate. No keys are stored or logged.
 */
internal object CrossfireEngine {
    data class Result(val model:String,val text:String,val error:String,val durationMs:Long)

    fun compare(secret:String, messages:JSONArray, models:List<String>):List<Result> {
        require(models.size in 2..3) { "Crossfire requires 2–3 candidate models" }
        val executor=Executors.newFixedThreadPool(models.size)
        try{
            val futures=models.map { id ->
                executor.submit(Callable { call(secret,id,messages) })
            }
            return models.indices.map { index ->
                try { futures[index].get(100,TimeUnit.SECONDS) }
                catch(e:Exception) {
                    futures[index].cancel(true)
                    Result(models[index],"","请求未完成："+(e.message?:"超时").take(90),100000)
                }
            }
        }finally { executor.shutdownNow() }
    }

    private fun call(secret:String,id:String,messages:JSONArray):Result {
        val since=System.currentTimeMillis()
        var c:HttpURLConnection?=null
        try {
            c=URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod="POST";c.doOutput=true
            c.connectTimeout=12000;c.readTimeout=80000
            c.setRequestProperty("Authorization","Bearer $secret")
            c.setRequestProperty("Content-Type","application/json")
            c.setRequestProperty("HTTP-Referer","https://github.com/leiguo395-hue/OmniFusion-AI")
            val request=JSONObject().put("model",id).put("stream",false).put("messages",messages)
            c.outputStream.use{it.write(request.toString().toByteArray(Charsets.UTF_8))}
            val code=c.responseCode
            if(code !in 200..299){
                val detail=c.errorStream?.bufferedReader()?.use{it.readText()}?:"未知服务错误"
                return Result(id,"","HTTP $code: "+detail.take(140),System.currentTimeMillis()-since)
            }
            val response=JSONObject(c.inputStream.bufferedReader().use{it.readText()})
            val msg=response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            val answer=msg?.optString("content")?:""
            return if(answer.isBlank())
                Result(id,"","模型未返回文本内容",System.currentTimeMillis()-since)
            else Result(id,answer,"",System.currentTimeMillis()-since)
        }catch(e:Exception){
            return Result(id,"",(e.message?:"网络连接失败").take(160),System.currentTimeMillis()-since)
        }finally { c?.disconnect() }
    }
}
