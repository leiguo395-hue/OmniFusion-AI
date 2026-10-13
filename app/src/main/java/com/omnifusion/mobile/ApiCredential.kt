package com.omnifusion.mobile

/**
 * Reject invalid OpenRouter credentials before constructing HTTP headers.
 * Never print, log, or interpolate credential contents into error messages.
 * A newline in Authorization is rejected locally by HttpURLConnection.
 */
internal object ApiCredential {
    fun valid(value:String):Boolean =
        value.startsWith("sk-or-") &&
        value.length in 20..512 &&
        value.all { ch -> ch.code in 33..126 && !ch.isWhitespace() }

    const val INVALID_MESSAGE =
        "OpenRouter API Key 格式不正确。请在设置中粘贴真正的 sk-or-… 密钥，不要填写提示词或多行文字。"
}
