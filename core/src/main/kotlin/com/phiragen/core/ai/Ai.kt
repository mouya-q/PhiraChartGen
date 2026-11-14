package com.phiragen.core.ai

import com.phiragen.core.audio.AnalysisConfig
import com.phiragen.core.audio.AnalysisResult
import com.phiragen.core.chart.EffectType
import com.phiragen.core.chart.Guidance
import com.phiragen.core.chart.PhraseCue
import com.phiragen.core.chart.PhraseStyle
import com.phiragen.core.chart.GuidanceWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
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
    /** 规范化后的接口根地址: 去掉空白, 结尾斜杠, 以及误粘贴的 /chat/completions */
    fun normalizedBase(): String {
        var u = baseUrl.trim().trimEnd('/')
        if (u.endsWith("/chat/completions")) u = u.removeSuffix("/chat/completions").trimEnd('/')
        return u
    }

    /** 返回第一个校验失败的原因, 全部通过返回 null */
    fun validate(): String? {
        val u = normalizedBase()
        if (!(u.startsWith("https://") || u.startsWith("http://"))) return "接口地址需以 http:// 或 https:// 开头"
        if (u.length < 10) return "接口地址过短"
        if (u.startsWith("http://") && !isLocalHost(u)) return "明文 http 只允许本机或局域网地址, 其他请使用 https"
        if (apiKey.isBlank()) return "请填写密钥"
        if (model.isBlank()) return "请填写模型名称"
        if (timeoutMs !in 3_000..120_000) return "超时需在 3 到 120 秒之间"
        if (retries !in 0..5) return "重试次数需在 0 到 5 之间"
        return null
    }
}

/** 允许明文传输的主机: 本机, 局域网, .local 域名 */
internal fun isLocalHost(url: String): Boolean {
    val host = try { URL(url).host.lowercase() } catch (e: Exception) { return false }
    if (host == "localhost" || host.endsWith(".local")) return true
    val p = host.split('.').mapNotNull { it.toIntOrNull() }
    if (p.size != 4) return false
    return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
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

    /** 请求参数的兼容开关, 遇到服务端明确拒绝时自动调整 */
    private class Quirks {
        var useMaxCompletionTokens = false
        var sendTemperature = true
        var extraV1 = false
        var jsonMode = false
    }

    /** 发送对话请求并返回助手文本, 对可重试错误做指数退避 */
    fun chat(system: String, user: String, maxTokens: Int = 1200, requireText: Boolean = true, jsonMode: Boolean = false): String {
        config.validate()?.let { throw AiException(AiErrorKind.BAD_REQUEST, it) }
        val quirks = Quirks().also { it.jsonMode = jsonMode }
        var attempt = 0
        var adapted = 0
        while (true) {
            try {
                return once(system, user, maxTokens, quirks, requireText)
            } catch (e: AdaptRequest) {
                // 服务端指出参数不被支持: 调整后立即重发, 不计入重试次数
                if (++adapted > 3) throw AiException(AiErrorKind.BAD_REQUEST, e.reason)
            } catch (e: AiException) {
                if (!e.retryable || attempt >= config.retries) throw e
                sleeper(500L shl attempt)
                attempt++
            }
        }
    }

    /** 连接测试: 只要服务端返回合法的补全响应即视为成功, 不要求有文本 (推理模型可能先输出空内容) */
    fun test(): String? = try {
        chat("只回答 ok", "ping", maxTokens = 64, requireText = false)
        null
    } catch (e: AiException) {
        e.message
    }

    private class AdaptRequest(val reason: String) : Exception(reason)

    private fun once(system: String, user: String, maxTokens: Int, q: Quirks, requireText: Boolean): String {
        val body = buildJsonObject {
            put("model", config.model.trim())
            if (q.sendTemperature) put("temperature", 0.3)
            put(if (q.useMaxCompletionTokens) "max_completion_tokens" else "max_tokens", maxTokens)
            if (q.jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        }.toString()
        val base = config.normalizedBase() + if (q.extraV1) "/v1" else ""
        val url = "$base/chat/completions"
        val reply = try {
            transport.post(
                url,
                mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                    "Authorization" to "Bearer ${config.apiKey.trim()}",
                    // 部分网关会拦截默认的 Java 请求头
                    "User-Agent" to "PhiraChartGen/1.0 (Android)",
                ),
                body,
                config.timeoutMs,
            )
        } catch (e: SocketTimeoutException) {
            throw AiException(AiErrorKind.TIMEOUT)
        } catch (e: UnknownHostException) {
            throw AiException(AiErrorKind.NETWORK, "无法解析域名 ${hostOf(base)}, 请检查接口地址与网络")
        } catch (e: SSLException) {
            throw AiException(AiErrorKind.NETWORK, "TLS 握手失败 (${e.javaClass.simpleName}), 请检查证书, 系统时间或是否被代理拦截")
        } catch (e: ConnectException) {
            throw AiException(AiErrorKind.NETWORK, "无法连接到 ${hostOf(base)}, 请检查地址, 端口与网络")
        } catch (e: IOException) {
            val extra = e.message?.take(80)?.let { ": $it" } ?: ""
            throw AiException(AiErrorKind.NETWORK, "网络错误 (${e.javaClass.simpleName}$extra)")
        }
        val detail = errorDetail(reply.body)
        when (reply.code) {
            in 200..299 -> Unit
            401 -> throw AiException(AiErrorKind.AUTH, "密钥无效 (401)" + suffix(detail))
            403 -> throw AiException(AiErrorKind.AUTH, "无权限 (403), 可能是密钥权限, 地区限制或网关拦截" + suffix(detail))
            404 -> {
                // 常见情况: 地址缺少 /v1
                if (!q.extraV1 && !config.normalizedBase().substringAfter("://").contains("/")) { q.extraV1 = true; throw AdaptRequest("404") }
                throw AiException(AiErrorKind.BAD_REQUEST, "接口不存在 (404), 请检查地址是否需要带 /v1 与模型名称" + suffix(detail))
            }
            429 -> throw AiException(AiErrorKind.RATE_LIMIT, "请求过于频繁或额度不足 (429)" + suffix(detail))
            400, 422 -> {
                val d = detail.lowercase()
                if (!q.useMaxCompletionTokens && d.contains("max_completion_tokens")) { q.useMaxCompletionTokens = true; throw AdaptRequest("max_tokens") }
                if (q.sendTemperature && d.contains("temperature")) { q.sendTemperature = false; throw AdaptRequest("temperature") }
                if (q.jsonMode && (d.contains("response_format") || d.contains("json_object") || d.contains("json mode"))) { q.jsonMode = false; throw AdaptRequest("response_format") }
                throw AiException(AiErrorKind.BAD_REQUEST, "请求被拒绝 (${reply.code})" + suffix(detail))
            }
            in 400..499 -> throw AiException(AiErrorKind.BAD_REQUEST, "请求被拒绝 (${reply.code})" + suffix(detail))
            else -> throw AiException(AiErrorKind.SERVER, "服务端错误 (${reply.code})" + suffix(detail))
        }
        return try {
            val root = json.parseToJsonElement(reply.body).jsonObject
            val msg = root["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
            val text = contentText(msg["content"]).ifEmpty { contentText(msg["reasoning_content"]) }
            if (requireText && text.isBlank()) throw AiException(AiErrorKind.FORMAT, "模型返回了空内容, 可能被截断或是推理模型, 请换用对话模型")
            text
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException(AiErrorKind.FORMAT, "返回内容不是标准的对话补全格式" + suffix(reply.body.take(80).replace('\n', ' ')))
        }
    }

    /** content 可能是字符串, 也可能是带 text 字段的分段数组 */
    private fun contentText(el: JsonElement?): String = when (el) {
        null, JsonNull -> ""
        is JsonPrimitive -> el.content
        is JsonArray -> el.joinToString("") { part -> (part as? JsonObject)?.get("text")?.let { (it as? JsonPrimitive)?.content } ?: "" }
        else -> ""
    }

    /** 提取服务端错误信息, 只取 message 字段, 不回显请求头与密钥 */
    private fun errorDetail(body: String): String = try {
        val o = json.parseToJsonElement(body).jsonObject
        val err = o["error"]
        val msg = when (err) {
            is JsonObject -> (err["message"] as? JsonPrimitive)?.content
            is JsonPrimitive -> err.content
            else -> (o["message"] as? JsonPrimitive)?.content
        } ?: ""
        val key = config.apiKey.trim()
        val safe = if (key.length >= 8) msg.replace(key, "***") else msg
        safe.take(160)
    } catch (e: Exception) {
        ""
    }

    private fun suffix(d: String) = if (d.isBlank()) "" else ": $d"
    private fun hostOf(url: String) = try { URL(url).host } catch (e: Exception) { url }
}

sealed class AiOutcome {
    class Applied(val windowCount: Int, val effects: Int = 0, val styles: Int = 0) : AiOutcome()
    class FellBack(val reason: String) : AiOutcome()
    object Disabled : AiOutcome()
}

/**
 * AI 增强. 只上传曲速, 时长和按乐句 (8 拍) 统计的能量与起音数量, 不上传音频, 文件名或个人信息.
 * 模型为每个乐句选择主线风格, 音符密度倍率与装饰特效, 由离线生成器套用, 任何失败都回退为纯离线结果
 */
object AiEnhancer {
    /** 提示词里的设计库来自对玩家谱的统计研究, 见 docs/CHART_STUDY.md */
    internal val SYSTEM_PROMPT = """
你是资深音游谱面设计师, 为 Phira 的 RPE 谱面编排判定线动画与音符密度.
输入是整首歌按 8 拍一个乐句统计的数据: i 是乐句序号, e 是相对能量 (1.0 约为全曲高潮水平), o 是起音数量.
你要为需要改变默认做法的乐句给出编排. 默认: 风格 STEADY, 密度 1.0, 自动特效.

设计库 (来自真人高难度谱的统计):
- 真人谱的音符几乎都落在一条稳定的主线上, 视觉效果主要由不带音符的装饰线提供, 所以大多数乐句应保持 STEADY.
- style 可选: STEADY (主线每 2 拍轻微倾斜回弹), PULSE (能量高时主线每拍下沉, 配合底鼓), SPLIT (拆成左右两条线, 仅高难度), SPIN (整体旋转, 仅高难度).
- SPLIT 与 SPIN 是少见的大动作: 全曲不超过乐句总数的六分之一, 且两次之间至少隔 3 个乐句, 只用在最强的高潮段.
- effects 可选: SWEEPERS (近竖直的细线每 2 拍从两侧扫入并随拍闪烁, 常用于开头和重复的高潮段), WHOOSH (能量骤升的段落开头成对对称扫线, 约 2 拍), ECHO (从主线向下扩散淡出的回声线, 用于鼓点密集的高潮段).
- 安静段不加特效. 特效集中在开头, 能量骤升处与高潮段, 同一乐句最多 2 个.
- density 是音符密度倍率, 范围 0.4 到 1.5: 安静与过渡段 0.5 到 0.9, 高潮段 1.1 到 1.4.
- 只能使用以上名称, 不要发明新的.

只输出一个 JSON 对象, 不要任何解释, 不要代码块标记. 格式:
{"phrases":[{"i":0,"style":"STEADY","density":0.6,"effects":["SWEEPERS"]}]}
字段都是可选的, 不需要改变的乐句不要列出.

参考示例. 输入:
{"bpm":150,"phrases":[{"i":0,"e":0.2,"o":3},{"i":1,"e":0.3,"o":6},{"i":2,"e":0.6,"o":14},{"i":3,"e":0.65,"o":16},{"i":4,"e":0.95,"o":30},{"i":5,"e":0.9,"o":28},{"i":6,"e":0.35,"o":8},{"i":7,"e":0.3,"o":7},{"i":8,"e":0.7,"o":18},{"i":9,"e":1.0,"o":34},{"i":10,"e":1.0,"o":36},{"i":11,"e":0.4,"o":9}]}
输出:
{"phrases":[{"i":0,"density":0.5,"effects":["SWEEPERS"]},{"i":1,"density":0.7},{"i":2,"density":0.9},{"i":3,"style":"PULSE"},{"i":4,"style":"PULSE","density":1.2,"effects":["WHOOSH","SWEEPERS"]},{"i":5,"style":"PULSE","density":1.3,"effects":["ECHO"]},{"i":6,"density":0.6},{"i":7,"density":0.6},{"i":8,"style":"PULSE","density":1.0},{"i":9,"style":"SPIN","density":1.3,"effects":["WHOOSH","ECHO"]},{"i":10,"style":"PULSE","density":1.4,"effects":["ECHO"]},{"i":11,"density":0.5}]}
""".trimIndent()

    private fun phraseStartSec(a: AnalysisResult, i: Int): Double {
        val beat = a.beatSec
        var phi = a.phase % beat
        if (phi < 0.02) phi += beat
        return phi + 8 * i * beat
    }

    fun phraseCount(a: AnalysisResult): Int {
        val beat = a.beatSec
        var n = 0
        while (phraseStartSec(a, n) < a.durationSec) n++
        return n.coerceAtLeast(1)
    }

    fun buildSummary(a: AnalysisResult): String {
        val p90 = (com.phiragen.core.audio.Stats.percentile(a.rms, 90.0) + 1e-9).toFloat()
        val peaks = com.phiragen.core.audio.PeakPicker.pick(a.env, 0.045, 0.12)
        val n = phraseCount(a)
        val phrases = buildJsonArray {
            for (i in 0 until n) {
                val s = phraseStartSec(a, i)
                val f0 = a.frameAt(s)
                val f1 = max(f0 + 1, minOf(a.frameCount, ((s + 8 * a.beatSec) * a.fps).toInt()))
                var sum = 0.0
                for (f in f0 until f1) sum += a.rms[f]
                val e = (sum / (f1 - f0)) / p90
                val o = peaks.count { it in f0 until f1 }
                add(buildJsonObject {
                    put("i", i)
                    put("e", Math.round(e * 100) / 100.0)
                    put("o", o)
                })
            }
        }
        return buildJsonObject {
            put("bpm", Math.round(a.bpm * 10) / 10.0)
            put("phrases", phrases)
        }.toString()
    }

    // ---------------- 解析

    /** 去掉推理模型的思考标签与代码块标记 */
    internal fun clean(text: String): String {
        var t = text
        t = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE).replace(t, "")
        t = Regex("<thinking>[\\s\\S]*?</thinking>", RegexOption.IGNORE_CASE).replace(t, "")
        return t.replace("```json", "```").replace("```JSON", "```")
    }

    /** 按括号配对找出所有顶层 JSON 对象, 识别字符串中的括号与转义 */
    internal fun jsonObjects(text: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escape = false
        for ((i, ch) in text.withIndex()) {
            if (inString) {
                if (escape) escape = false else if (ch == '\\') escape = true else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> if (depth > 0) inString = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) { out.add(text.substring(start, i + 1)); start = -1 }
                }
            }
        }
        return out
    }

    private fun styleOf(name: String?): PhraseStyle? = when (name?.trim()?.uppercase()) {
        "STEADY", "CALM" -> PhraseStyle.STEADY
        "PULSE", "BOUNCE" -> PhraseStyle.PULSE
        "SPLIT" -> PhraseStyle.SPLIT
        "SPIN" -> PhraseStyle.SPIN
        else -> null
    }

    private fun effectOf(name: String?): EffectType? = when (name?.trim()?.uppercase()) {
        "SWEEPERS", "SWEEP", "FLASH" -> EffectType.SWEEPERS
        "WHOOSH", "BURST" -> EffectType.WHOOSH
        "ECHO", "RIPPLE" -> EffectType.ECHO
        else -> null
    }

    private fun num(o: JsonObject, vararg keys: String): Double? {
        for (k in keys) (o[k] as? JsonPrimitive)?.doubleOrNull?.let { if (!it.isNaN()) return it }
        return null
    }

    /** 解析模型回复. 兼容代码块, 前后说明文字, 以及旧格式 windows */
    fun parseGuidance(text: String, a: AnalysisResult): Guidance {
        val durationSec = a.durationSec
        val total = phraseCount(a)
        val cleaned = clean(text)
        var root: JsonObject? = null
        for (candidate in jsonObjects(cleaned)) {
            val o = try { Json.parseToJsonElement(candidate).jsonObject } catch (e: Exception) { continue }
            if (o.containsKey("phrases") || o.containsKey("windows") || o.containsKey("sections")) { root = o; break }
        }
        if (root == null) {
            val snippet = cleaned.trim().replace('\n', ' ').take(60)
            throw AiException(AiErrorKind.FORMAT, "返回内容无法解析, 模型回复开头: " + if (snippet.isEmpty()) "(空)" else snippet)
        }
        val windows = ArrayList<GuidanceWindow>()
        val cues = HashMap<Int, PhraseCue>()
        val list = (root["phrases"] ?: root["sections"]) as? JsonArray
        for (el in (list ?: JsonArray(emptyList())).take(400)) {
            val o = el as? JsonObject ?: continue
            val i = num(o, "i", "index", "phrase")?.toInt() ?: continue
            if (i < 0 || i >= total) continue
            val density = num(o, "density", "d")?.coerceIn(0.4, 1.5)
            val style = styleOf((o["style"] as? JsonPrimitive)?.content)
            val effArr = (o["effects"] ?: o["effect"]) as? JsonArray
            val effects = effArr?.mapNotNull { effectOf((it as? JsonPrimitive)?.content) }?.take(2)?.toSet()
            if (density == null && style == null && effects == null) continue
            cues[i] = PhraseCue(density, style, effects)
            if (density != null) windows.add(GuidanceWindow(phraseStartSec(a, i), minOf(phraseStartSec(a, i + 1), durationSec), density))
        }
        // 旧格式: 直接给出时间窗口与密度
        for (el in ((root["windows"] as? JsonArray) ?: JsonArray(emptyList())).take(400)) {
            val o = el as? JsonObject ?: continue
            val s = num(o, "start") ?: continue
            val e = num(o, "end") ?: continue
            val d = num(o, "density") ?: continue
            if (e <= s || s < 0 || s > durationSec) continue
            windows.add(GuidanceWindow(s, minOf(e, durationSec), d.coerceIn(0.3, 1.6)))
        }
        if (cues.isEmpty() && windows.isEmpty()) {
            throw AiException(AiErrorKind.FORMAT, "模型没有给出可用的编排建议, 回复开头: " + cleaned.trim().replace('\n', ' ').take(60))
        }
        return Guidance(windows.sortedBy { it.startSec }, cues)
    }

    fun enhance(a: AnalysisResult, client: AiClient): Pair<Guidance, AiOutcome> = try {
        val reply = client.chat(SYSTEM_PROMPT, buildSummary(a), maxTokens = 3000, jsonMode = true)
        val g = parseGuidance(reply, a)
        g to AiOutcome.Applied(
            windowCount = g.phrases.size.coerceAtLeast(g.windows.size),
            effects = g.phrases.values.sumOf { it.effects?.size ?: 0 },
            styles = g.phrases.values.count { it.style != null },
        )
    } catch (e: AiException) {
        Guidance.NONE to AiOutcome.FellBack(e.message ?: e.kind.text)
    } catch (e: Exception) {
        Guidance.NONE to AiOutcome.FellBack("AI 处理失败")
    }
}
