package com.phiragen.core.ai

import com.phiragen.core.audio.AnalysisConfig
import com.phiragen.core.audio.AnalysisResult
import com.phiragen.core.chart.Guidance
import com.phiragen.core.chart.GuidanceWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.math.log10
import kotlin.math.max

data class AiConfig(
    /** OpenAI 兼容接口根地址, 例如 https://api.openai.com/v1 */
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val timeoutMs: Int = 20_000,
    val retries: Int = 2,
) {
    /** 返回第一个校验失败的原因, 全部通过返回 null */
    fun validate(): String? {
        val u = baseUrl.trim()
        if (!(u.startsWith("https://") || u.startsWith("http://"))) return "接口地址需以 http:// 或 https:// 开头"
        if (u.length < 10) return "接口地址过短"
        if (apiKey.isBlank()) return "请填写密钥"
        if (model.isBlank()) return "请填写模型名称"
        if (timeoutMs !in 3_000..120_000) return "超时需在 3 到 120 秒之间"
        if (retries !in 0..5) return "重试次数需在 0 到 5 之间"
        return null
    }
}

enum class AiErrorKind(val text: String) {
    AUTH("密钥无效或无权限"),
    RATE_LIMIT("请求过于频繁"),
    TIMEOUT("请求超时"),
    NETWORK("网络不可用"),
    SERVER("服务端错误"),
    BAD_REQUEST("请求被拒绝, 请检查模型名称与地址"),
    FORMAT("返回内容无法解析"),
}

class AiException(val kind: AiErrorKind, detail: String? = null) : Exception(detail ?: kind.text) {
    val retryable: Boolean get() = kind == AiErrorKind.TIMEOUT || kind == AiErrorKind.NETWORK ||
        kind == AiErrorKind.SERVER || kind == AiErrorKind.RATE_LIMIT
}

class HttpReply(val code: Int, val body: String)

/** 网络层抽象, 便于测试时替换 */
interface HttpTransport {
    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): HttpReply
}

class UrlConnectionTransport : HttpTransport {
    override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): HttpReply {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.doOutput = true
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            return HttpReply(code, text.take(1_000_000))
        } finally {
            c.disconnect()
        }
    }
}

class AiClient(
    private val config: AiConfig,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 发送对话请求并返回助手文本, 对可重试错误做指数退避 */
    fun chat(system: String, user: String, maxTokens: Int = 1200): String {
        config.validate()?.let { throw AiException(AiErrorKind.BAD_REQUEST, it) }
        var attempt = 0
        while (true) {
            try {
                return once(system, user, maxTokens)
            } catch (e: AiException) {
                if (!e.retryable || attempt >= config.retries) throw e
                sleeper(500L shl attempt)
                attempt++
            }
        }
    }

    /** 连接测试: 发送极短请求, 成功返回 null, 失败返回原因 */
    fun test(): String? = try {
        chat("只回答 ok", "ping", maxTokens = 8)
        null
    } catch (e: AiException) {
        e.message
    }

    private fun once(system: String, user: String, maxTokens: Int): String {
        val body = buildJsonObject {
            put("model", config.model.trim())
            put("temperature", 0.3)
            put("max_tokens", maxTokens)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        }.toString()
        val url = config.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val reply = try {
            transport.post(
                url,
                mapOf("Content-Type" to "application/json", "Authorization" to "Bearer ${config.apiKey.trim()}"),
                body,
                config.timeoutMs,
            )
        } catch (e: SocketTimeoutException) {
            throw AiException(AiErrorKind.TIMEOUT)
        } catch (e: IOException) {
            throw AiException(AiErrorKind.NETWORK)
        }
        when (reply.code) {
            in 200..299 -> Unit
            401, 403 -> throw AiException(AiErrorKind.AUTH)
            429 -> throw AiException(AiErrorKind.RATE_LIMIT)
            in 400..499 -> throw AiException(AiErrorKind.BAD_REQUEST)
            else -> throw AiException(AiErrorKind.SERVER, "服务端错误 ${reply.code}")
        }
        return try {
            val root = json.parseToJsonElement(reply.body).jsonObject
            root["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        } catch (e: Exception) {
            throw AiException(AiErrorKind.FORMAT)
        }
    }
}

sealed class AiOutcome {
    class Applied(val windowCount: Int) : AiOutcome()
    class FellBack(val reason: String) : AiOutcome()
    object Disabled : AiOutcome()
}

/**
 * AI 增强: 只上传曲速, 时长和按时间分段的能量与起音密度统计, 不上传音频, 文件名或个人信息
 * 模型返回分段密度倾向, 由离线生成器套用, 任何失败都回退为纯离线结果
 */
object AiEnhancer {
    private const val SYSTEM_PROMPT =
        "你是音游谱面编排助手. 根据给出的分段统计, 为每个时间段给出音符密度倍率. " +
            "高潮段提高到 1.1 到 1.5, 过渡与安静段降到 0.4 到 0.9, 其余保持 1.0. " +
            "只输出 JSON, 格式为 {\"windows\":[{\"start\":秒,\"end\":秒,\"density\":倍率}]}, 不要输出任何其他文字"

    fun buildSummary(a: AnalysisResult, segmentSec: Double = 4.0): String {
        val rmsPeak = (a.rms.maxOrNull() ?: 1f).coerceAtLeast(1e-6f)
        val segs = buildJsonArray {
            var t = 0.0
            while (t < a.durationSec) {
                val f0 = a.frameAt(t)
                val f1 = max(f0 + 1, a.frameAt(t + segmentSec))
                var e = 0.0
                var onsets = 0
                for (f in f0 until minOf(f1, a.frameCount)) {
                    e += a.rms[f]
                    if (a.env[f] > 0.5f) onsets++
                }
                val mean = e / max(1, minOf(f1, a.frameCount) - f0)
                val db = 20.0 * log10(mean / rmsPeak + 1e-9)
                add(buildJsonObject {
                    put("start", Math.round(t * 10) / 10.0)
                    put("energyDb", Math.round(db * 10) / 10.0)
                    put("onsets", onsets)
                })
                t += segmentSec
            }
        }
        return buildJsonObject {
            put("bpm", Math.round(a.bpm * 10) / 10.0)
            put("durationSec", Math.round(a.durationSec))
            put("segments", segs)
        }.toString()
    }

    fun parseGuidance(text: String, durationSec: Double): Guidance {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiException(AiErrorKind.FORMAT)
        val root = try {
            Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        } catch (e: Exception) {
            throw AiException(AiErrorKind.FORMAT)
        }
        val arr = root["windows"] as? JsonArray ?: throw AiException(AiErrorKind.FORMAT)
        val out = ArrayList<GuidanceWindow>()
        for (el in arr.take(400)) {
            val o = el as? JsonObject ?: continue
            val s = o["start"]?.jsonPrimitive?.doubleOrNull ?: continue
            val e = o["end"]?.jsonPrimitive?.doubleOrNull ?: continue
            val d = o["density"]?.jsonPrimitive?.doubleOrNull ?: continue
            if (s.isNaN() || e.isNaN() || d.isNaN()) continue
            if (e <= s || s < 0 || s > durationSec) continue
            out.add(GuidanceWindow(s, minOf(e, durationSec), d.coerceIn(0.3, 1.6)))
        }
        if (out.isEmpty()) throw AiException(AiErrorKind.FORMAT, "没有可用的分段建议")
        return Guidance(out.sortedBy { it.startSec })
    }

    fun enhance(a: AnalysisResult, client: AiClient): Pair<Guidance, AiOutcome> = try {
        val reply = client.chat(SYSTEM_PROMPT, buildSummary(a))
        val g = parseGuidance(reply, a.durationSec)
        g to AiOutcome.Applied(g.windows.size)
    } catch (e: AiException) {
        Guidance.NONE to AiOutcome.FellBack(e.message ?: e.kind.text)
    } catch (e: Exception) {
        Guidance.NONE to AiOutcome.FellBack("AI 处理失败")
    }
}
