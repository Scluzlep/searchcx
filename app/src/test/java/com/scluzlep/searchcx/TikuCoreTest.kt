package com.scluzlep.searchcx

import com.scluzlep.searchcx.tiku.OcsQuestionExtractor
import com.scluzlep.searchcx.tiku.TikuAdapterClient
import com.scluzlep.searchcx.tiku.TikuAnswerResolver
import com.scluzlep.searchcx.tiku.TikuConfig
import com.scluzlep.searchcx.tiku.TikuMarkdownFormatter
import com.scluzlep.searchcx.tiku.TikuMode
import com.scluzlep.searchcx.tiku.TikuProvider
import com.scluzlep.searchcx.tiku.TikuProviderTokens
import com.scluzlep.searchcx.tiku.TikuQuestionType
import com.scluzlep.searchcx.tiku.TikuSearchRequest
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class TikuCoreTest {

    @Test
    fun defaultConfigEnablesOnlyFreeProvidersAndExcludesPaidEvenWithTokens() {
        val freeIds = TikuProvider.defaultFreeIds()
        assertEquals(setOf("icodef", "local", "buguake"), freeIds)
        assertEquals(3, TikuProvider.freeProviders().size)
        assertEquals(5, TikuProvider.paidProviders().size)
        assertTrue(TikuProvider.paidProviders().all { !it.isFree })

        val tokens = TikuProviderTokens(
            wannengToken = "1234567890",
            icodefToken = "ic_tok",
            enncyToken = "en_tok",
            aidianYToken = "ai_tok",
            lemonToken = "le_tok",
            tikuhaiToken = "tk_tok"
        )
        val cfg = TikuConfig(tokens = tokens)

        assertEquals(freeIds, cfg.enabledProviders)
        assertEquals(listOf(TikuProvider.ICODEF), cfg.effectiveDirectProviders())
        assertEquals(
            listOf(TikuProvider.ICODEF, TikuProvider.LOCAL, TikuProvider.BUGUAKE),
            cfg.effectiveRemoteProviders()
        )
        assertEquals("icodef,local,buguake", cfg.effectiveRemoteUseParam())

        val remoteUrl = cfg.buildRemoteSearchUrl(noRecord = false)
        assertTrue(remoteUrl.contains("use=icodef,local,buguake"))
        assertTrue(remoteUrl.contains("icodefToken=ic_tok"))
        assertFalse(remoteUrl.contains("enncyToken"))
        assertFalse(remoteUrl.contains("wannengToken"))
        assertFalse(remoteUrl.contains("aidianYToken"))
        assertFalse(remoteUrl.contains("lemonToken"))
        assertFalse(remoteUrl.contains("tikuhaiToken"))
        assertFalse(remoteUrl.contains("noRecord"))
    }

    @Test
    fun paidProviderTogglingAndRemoteUrlGeneration() {
        val tokens = TikuProviderTokens(
            enncyToken = "enncy_secret",
            lemonToken = "lemon_secret",
            tikuhaiToken = "tikuhai_secret"
        )
        var cfg = TikuConfig(
            mode = TikuMode.REMOTE_ADAPTER,
            remoteBaseUrl = "http://192.168.1.10:8060",
            tokens = tokens
        )

        // Enabling enncy and tikuhai reflects in both direct (with token rules) and remote URL.
        cfg = cfg.withProviderToggled("enncy", true)
            .withProviderToggled("tikuhai", true)
            .withProviderToggled("lemon", true)
        assertEquals(
            listOf(TikuProvider.ICODEF, TikuProvider.ENNCY, TikuProvider.TIKUHAI, TikuProvider.LEMON),
            cfg.effectiveDirectProviders()
        )

        val url = cfg.buildRemoteSearchUrl(noRecord = true)
        val parsed = url.toHttpUrl()
        assertEquals("/adapter-service/search", parsed.encodedPath)
        assertEquals("icodef,local,buguake,enncy,tikuhai,lemon", parsed.queryParameter("use"))
        assertEquals("enncy_secret", parsed.queryParameter("enncyToken"))
        assertEquals("tikuhai_secret", parsed.queryParameter("tikuhaiToken"))
        assertEquals("lemon_secret", parsed.queryParameter("lemonToken"))
        assertEquals("true", parsed.queryParameter("noRecord"))

        // Enncy and Lemon require non-blank tokens in direct mode, while Tikuhai/Wanneng/Aidian do not.
        val noTokenPaid = TikuConfig()
            .withProviderToggled("enncy", true)
            .withProviderToggled("lemon", true)
            .withProviderToggled("tikuhai", true)
            .withProviderToggled("wanneng", true)
            .withProviderToggled("aidian", true)
        assertEquals(
            listOf(TikuProvider.ICODEF, TikuProvider.TIKUHAI, TikuProvider.WANNENG, TikuProvider.AIDIAN),
            noTokenPaid.effectiveDirectProviders()
        )
    }

    @Test
    fun configJsonRoundTripsEncryptedTokensAndRespectsExplicitEmptyProviders() {
        val tokens = TikuProviderTokens(
            wannengToken = "w1",
            icodefToken = "i2",
            enncyToken = "e3",
            aidianYToken = "a4",
            lemonToken = "l5",
            tikuhaiToken = "t6"
        )
        val original = TikuConfig(
            enabled = false,
            mode = TikuMode.REMOTE_ADAPTER,
            remoteBaseUrl = "http://10.0.0.2:8060/adapter-service/search",
            autoQueryBeforeAi = false,
            skipAiOnHit = true,
            enabledProviders = setOf("icodef", "enncy"),
            tokens = tokens
        )

        val encryptedJson = original.toJson { plain ->
            if (plain.isEmpty()) "" else "ENC[$plain]"
        }
        assertFalse(encryptedJson.contains("\"w1\""))
        assertFalse(encryptedJson.contains("\"e3\""))
        assertTrue(encryptedJson.contains("ENC[e3]"))

        val restored = TikuConfig.fromJson(encryptedJson) { enc ->
            enc.removePrefix("ENC[").removeSuffix("]")
        }
        assertEquals(original, restored)

        // Omitting enabledProviders defaults to free providers; explicit [] stays empty.
        val omittedJson = """{"enabled":true,"mode":"DIRECT_BUILTIN"}"""
        val defaulted = TikuConfig.fromJson(omittedJson) { it }
        assertEquals(TikuProvider.defaultFreeIds(), defaulted.enabledProviders)

        val explicitEmptyJson = """{"enabled":true,"enabledProviders":[]}"""
        val emptyProviders = TikuConfig.fromJson(explicitEmptyJson) { it }
        assertTrue(emptyProviders.enabledProviders.isEmpty())
    }

    @Test
    fun ocsExtractorUsesDomFirstAndParsesMultiQuestionsAndTypes() {
        val domList = listOf(
            TikuSearchRequest("1. 【单选题】违反安全保障义务责任属于（ ）", listOf("A. 公平责任", "B. 特殊侵权责任"), 0),
            TikuSearchRequest("1. 【单选题】违反安全保障义务责任属于（ ）", listOf("A. 公平责任", "B. 特殊侵权责任"), 0)
        )
        val fromDom = OcsQuestionExtractor.extract("忽略这段原始文本", domList)
        assertEquals(1, fromDom.size)
        assertEquals("违反安全保障义务责任属于（ ）", fromDom[0].question)
        assertEquals(listOf("公平责任", "特殊侵权责任"), fromDom[0].options)

        val manyDom = (1..20).map { idx -> TikuSearchRequest("第 $idx 题：题目内容 $idx", listOf("A. 甲", "B. 乙"), 0) }
        assertEquals(15, OcsQuestionExtractor.extract("", manyDom).size)

        val rawSingle = """
            1. [单选题] 中国的首都是哪里？（5分）
            A. 上海
            B. 北京
            C. 广州
            D. 深圳
        """.trimIndent()
        val parsedSingle = OcsQuestionExtractor.extract(rawSingle)
        assertEquals(1, parsedSingle.size)
        assertEquals("中国的首都是哪里？", parsedSingle[0].question)
        assertEquals(listOf("上海", "北京", "广州", "深圳"), parsedSingle[0].options)
        assertEquals(TikuQuestionType.SINGLE.code, parsedSingle[0].type)

        val rawMultiAndTypes = """
            1. 下列哪些属于面向对象特性？
            A、封装 B、继承 C、多态 D、过程化
            2. 太阳从西边升起。
            对
            错
            3. 中华人民共和国成立于 ____ 年。
            4. 【问答题】简述 TCP 三次握手过程。
        """.trimIndent()
        val multiParsed = OcsQuestionExtractor.extract(rawMultiAndTypes)
        assertEquals(4, multiParsed.size)
        assertEquals(TikuQuestionType.MULTIPLE.code, multiParsed[0].type)
        assertEquals(listOf("封装", "继承", "多态", "过程化"), multiParsed[0].options)
        assertEquals(TikuQuestionType.JUDGEMENT.code, multiParsed[1].type)
        assertEquals(listOf("对", "错"), multiParsed[1].options)
        assertEquals(TikuQuestionType.COMPLETION.code, multiParsed[2].type)
        assertEquals(TikuQuestionType.QA.code, multiParsed[3].type)

        val twelveRaw = (1..12).joinToString("\n") { "$it. 第 $it 题题干 ____" }
        assertEquals(10, OcsQuestionExtractor.extract(twelveRaw).size)
    }

    @Test
    fun answerResolverNormalizesMatchesDiceAndVotesMajority() {
        assertTrue(TikuAnswerResolver.isPlainAnswer("ABC"))
        assertFalse(TikuAnswerResolver.isPlainAnswer("BA"))
        assertFalse(TikuAnswerResolver.isPlainAnswer("no"))
        assertEquals("BC", TikuAnswerResolver.resolvePlainAnswer("Ｂ, Ｃ。"))
        assertNull(TikuAnswerResolver.resolvePlainAnswer("C, A"))

        assertEquals("正确答案", TikuAnswerResolver.normalizeAnswerText("Ａ． 正确答案！？；"))
        assertEquals("正确", TikuAnswerResolver.canonicalizeJudgement("True"))
        assertEquals("错误", TikuAnswerResolver.canonicalizeJudgement("×"))

        // Multi-choice majority vote with glued prefix, plain letters, and typo resolved by Dice >= 0.70.
        val multiReq = TikuSearchRequest(
            question = "违反安全保障义务责任属于（ ）",
            options = listOf("公平责任", "特殊侵权责任", "过错推定责任", "连带责任"),
            type = TikuQuestionType.MULTIPLE.code
        )
        val multiRes = TikuAnswerResolver.resolve(
            multiReq,
            listOf(
                TikuAnswerResolver.ProviderAnswer("icodef", listOf("B特殊侵权责任", "C过错推定责任。")),
                TikuAnswerResolver.ProviderAnswer("enncy", listOf("B, C")),
                TikuAnswerResolver.ProviderAnswer("wanneng", listOf("公平责任"))
            )
        )
        assertTrue(multiRes.isHit())
        assertEquals(listOf(1, 2), multiRes.answerIndex)
        assertEquals(listOf("B", "C"), multiRes.answerKey)
        assertEquals("BC", multiRes.answerKeyText)
        assertEquals(listOf("特殊侵权责任", "过错推定责任"), multiRes.bestAnswer)
        assertEquals("特殊侵权责任#过错推定责任", multiRes.answerText)
        assertEquals(listOf("icodef", "enncy", "wanneng"), multiRes.matchedProviders)

        // Dice >= 0.70 fuzzy match fallback.
        val diceReq = TikuSearchRequest(
            question = "面向对象语言不包括",
            options = listOf("Java程序设计语言", "Python脚本语言", "C语言面向过程"),
            type = TikuQuestionType.SINGLE.code
        )
        val diceRes = TikuAnswerResolver.resolve(
            diceReq,
            listOf(TikuAnswerResolver.ProviderAnswer("icodef", listOf("C语言面向过程啊")))
        )
        assertEquals(listOf(2), diceRes.answerIndex)
        assertEquals("C", diceRes.answerKeyText)

        // Judgement canonicalization with "T"/"F" must NOT confuse as option letter indices.
        val judgeReq = TikuSearchRequest(
            question = "地球是圆的",
            options = listOf("对", "错"),
            type = TikuQuestionType.JUDGEMENT.code
        )
        val judgeRes = TikuAnswerResolver.resolve(
            judgeReq,
            listOf(TikuAnswerResolver.ProviderAnswer("icodef", listOf("T")))
        )
        assertEquals(listOf(0), judgeRes.answerIndex)
        assertEquals("A", judgeRes.answerKeyText)
        assertEquals(listOf("对"), judgeRes.bestAnswer)

        // Completion majority vote without options.
        val fillReq = TikuSearchRequest(question = "北京是中国的____", type = TikuQuestionType.COMPLETION.code)
        val fillRes = TikuAnswerResolver.resolve(
            fillReq,
            listOf(
                TikuAnswerResolver.ProviderAnswer("icodef", listOf("首都。")),
                TikuAnswerResolver.ProviderAnswer("enncy", listOf("首都")),
                TikuAnswerResolver.ProviderAnswer("tikuhai", listOf("中心"))
            )
        )
        assertEquals(listOf("首都"), fillRes.bestAnswer)
        assertEquals("首都", fillRes.answerText)
    }

    @Test
    fun directModeCallsOnlyFreeIcodefByDefaultAndRespectsAllSixWhenEnabled() = runBlocking {
        val capturedUrls = CopyOnWriteArrayList<String>()
        val clientFreeOnly = TikuAdapterClient(mockOkHttp { req ->
            capturedUrls.add(req.url.toString())
            jsonResponse(req, """{"code":1,"data":"北京"}""")
        })

        // Paid tokens are present, yet paid providers are disabled by default.
        val defaultCfg = TikuConfig(
            mode = TikuMode.DIRECT_BUILTIN,
            tokens = TikuProviderTokens(
                wannengToken = "1234567890",
                icodefToken = "ic_tok",
                enncyToken = "en_tok",
                aidianYToken = "ai_tok",
                lemonToken = "le_tok",
                tikuhaiToken = "tk_tok"
            )
        )
        val req = TikuSearchRequest("中国的首都是？", listOf("上海", "北京"), TikuQuestionType.SINGLE.code)
        val freeRes = clientFreeOnly.searchOne(req, defaultCfg)

        assertTrue(freeRes.isHit())
        assertEquals(listOf("B"), freeRes.answerKey)
        assertEquals(listOf("icodef"), freeRes.matchedProviders)
        assertEquals(listOf("https://cx.icodef.com/wyn-nb?v=4"), capturedUrls.toList())

        // Now enable all 8 providers (6 direct + 2 remote-only local/buguake).
        val recordedRequests = CopyOnWriteArrayList<CapturedHttp>()
        val allClient = TikuAdapterClient(mockOkHttp { httpReq ->
            val bodyStr = readBodyString(httpReq)
            recordedRequests.add(CapturedHttp(httpReq, bodyStr))
            val url = httpReq.url.toString()
            when {
                url.startsWith("https://cx.icodef.com/wyn-nb") ->
                    jsonResponse(httpReq, """{"code":1,"data":"北京"}""")
                url.startsWith("http://lyck6.cn/scriptService/api/autoAnswer/1234567890") ->
                    jsonResponse(httpReq, """{"code":0,"result":{"success":true,"answers":[["北京"]]}}""")
                url.startsWith("https://api.tikuhai.com/search") ->
                    jsonResponse(httpReq, """{"code":200,"data":{"answer":"北京"}}""")
                url.startsWith("https://tk.enncy.cn/query") ->
                    jsonResponse(httpReq, """{"code":1,"data":{"answer":"北京"}}""")
                url.startsWith("http://new.api.51aidian.com/publics/newapi/direct") ->
                    jsonResponse(httpReq, """{"qlist":[{"answer":"上海"}]}""")
                url.startsWith("https://api.lemtk.xyz/api/v1/mcx") ->
                    jsonResponse(httpReq, """{"code":1000,"data":{"answer":"北京"}}""")
                else -> error("Unexpected URL called: $url")
            }
        })

        var allEnabledCfg = defaultCfg
        for (p in TikuProvider.entries) {
            allEnabledCfg = allEnabledCfg.withProviderToggled(p.id, true)
        }
        val allRes = allClient.searchOne(req, allEnabledCfg)
        assertTrue(allRes.isHit())
        assertEquals("B", allRes.answerKeyText)
        assertEquals(6, recordedRequests.size)

        // Verify per-provider wire protocol details.
        val byHost = recordedRequests.associateBy { it.request.url.host }
        assertEquals("ic_tok", byHost["cx.icodef.com"]!!.request.header("Authorization"))
        assertTrue(byHost["cx.icodef.com"]!!.body.contains("question="))

        val wannengReq = byHost["lyck6.cn"]!!
        assertEquals("0", wannengReq.request.header("plat"))
        assertEquals("/scriptService/api/autoAnswer/1234567890", wannengReq.request.url.encodedPath)

        val tikuhaiReq = byHost["api.tikuhai.com"]!!
        assertEquals("tikuhaiAdapter/0.1.0", tikuhaiReq.request.header("User-Agent"))
        assertEquals("0.1.0", tikuhaiReq.request.header("v"))
        assertEquals("tk_tok", JSONObject(tikuhaiReq.body).getString("key"))

        val enncyReq = byHost["tk.enncy.cn"]!!
        assertEquals("en_tok", enncyReq.request.url.queryParameter("token"))
        assertEquals("中国的首都是？", enncyReq.request.url.queryParameter("title"))

        val aidianReq = byHost["new.api.51aidian.com"]!!
        assertEquals("ai_tok", JSONObject(aidianReq.body).getString("token"))

        val lemonReq = byHost["api.lemtk.xyz"]!!
        assertEquals("Bearer le_tok", lemonReq.request.header("Authorization"))
        assertEquals("searchcx", JSONObject(lemonReq.body).getString("uid"))
    }

    @Test
    fun remoteModeAndBoundedBatchAndFormatterWorkCleanly() = runBlocking {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val capturedRemote = CopyOnWriteArrayList<CapturedHttp>()

        val client = TikuAdapterClient(mockOkHttp { req ->
            val current = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { prev -> maxOf(prev, current) }
            try {
                Thread.sleep(25)
                capturedRemote.add(CapturedHttp(req, readBodyString(req)))
                val payload = """
                    {
                      "plat": 0,
                      "question": "题干",
                      "options": ["甲", "乙"],
                      "type": 0,
                      "answer": {
                        "bestAnswer": ["乙"],
                        "answerKey": ["B"],
                        "answerKeyText": "B",
                        "answerText": "乙",
                        "answerIndex": [1],
                        "allAnswer": [["乙"], ["B"]]
                      }
                    }
                """.trimIndent()
                jsonResponse(req, payload)
            } finally {
                inFlight.decrementAndGet()
            }
        })

        val cfg = TikuConfig(
            mode = TikuMode.REMOTE_ADAPTER,
            remoteBaseUrl = "http://127.0.0.1:8060/adapter-service/search",
            tokens = TikuProviderTokens(
                icodefToken = "ic1",
                enncyToken = "should_not_leak"
            )
        )

        val testConn = client.testConnection(cfg)
        assertTrue(testConn.isSuccess)
        assertEquals("true", capturedRemote.last().request.url.queryParameter("noRecord"))

        capturedRemote.clear()
        val batchReqs = (1..6).map { i ->
            TikuSearchRequest(
                question = "问题 $i",
                options = listOf("甲", "乙"),
                type = TikuQuestionType.SINGLE.code,
                courseName = "高数",
                plat = 1,
                extra = "ext"
            )
        }
        val batchResults = client.searchBatch(batchReqs, cfg, maxItems = 5)
        assertEquals(5, batchResults.size)
        assertTrue("Expected max concurrent <= 3 but was ${maxInFlight.get()}", maxInFlight.get() <= 3)

        val firstCall = capturedRemote.first()
        assertEquals("icodef,local,buguake", firstCall.request.url.queryParameter("use"))
        assertEquals("ic1", firstCall.request.url.queryParameter("icodefToken"))
        assertNull(firstCall.request.url.queryParameter("enncyToken"))
        val sentJson = JSONObject(firstCall.body)
        assertEquals("", sentJson.getString("qid"))
        assertEquals(1, sentJson.getInt("plat"))
        assertEquals("高数", sentJson.getString("courseName"))
        assertEquals("ext", sentJson.getString("extra"))
        assertEquals(JSONArray(listOf("甲", "乙")).toString(), sentJson.getJSONArray("options").toString())

        val displayMd = TikuMarkdownFormatter.formatForDisplay(batchResults.take(1), cfg.mode.displayName)
        assertTrue(displayMd.contains("远端服务"))
        assertTrue(displayMd.contains("B · 乙"))
        assertFalse(containsEmoji(displayMd))

        val aiContext = TikuMarkdownFormatter.formatForAiContext(batchResults.take(1))
        assertTrue(aiContext.contains("[题库预检索结果]"))
        assertTrue(aiContext.contains("题库命中答案：B · 乙"))
        assertFalse(containsEmoji(aiContext))

        assertTrue(TikuMarkdownFormatter.formatForDisplay(emptyList(), "内置聚合").contains("未提取到"))
        assertEquals("", TikuMarkdownFormatter.formatForAiContext(emptyList()))
    }

    @Test
    fun directFreeFallbacksPartialFailuresAndTestConnection() = runBlocking {
        val calledPaths = CopyOnWriteArrayList<String>()
        val client = TikuAdapterClient(mockOkHttp { req ->
            calledPaths.add(req.url.toString())
            val url = req.url.toString()
            when {
                url == "http://lyck6.cn/scriptService/api/autoFreeAnswer" ->
                    jsonResponse(req, """{"code":0,"result":{"success":true,"answers":[["答案一"]]}}""")
                url == "http://new.api.51aidian.com/publics/newapi/freedirect" ->
                    jsonResponse(req, """{"qlist":[{"answer":"答案一"}]}""")
                url == "https://cx.icodef.com/wyn-nb?v=4" ->
                    jsonResponse(req, """{"error":"upstream"}""", code = 500)
                else -> error("Unexpected URL: $url")
            }
        })

        val cfg = TikuConfig(mode = TikuMode.DIRECT_BUILTIN)
            .withProviderToggled("wanneng", true)
            .withProviderToggled("aidian", true)
            .copy(tokens = TikuProviderTokens(wannengToken = "short"))

        val req = TikuSearchRequest("简述测试", type = TikuQuestionType.QA.code)
        val res = client.searchOne(req, cfg)
        assertTrue(res.isHit())
        assertEquals(listOf("答案一"), res.bestAnswer)
        assertTrue(calledPaths.contains("http://lyck6.cn/scriptService/api/autoFreeAnswer"))
        assertTrue(calledPaths.contains("http://new.api.51aidian.com/publics/newapi/freedirect"))

        val connOk = client.testConnection(cfg)
        assertTrue(connOk.isSuccess)
        assertTrue(connOk.getOrThrow().contains("直连探测通过"))

        val emptyCfg = TikuConfig(enabledProviders = emptySet())
        val connFail = client.testConnection(emptyCfg)
        assertTrue(connFail.isFailure)
        val missRes = client.searchOne(req, emptyCfg)
        assertFalse(missRes.isHit())
        val missMd = TikuMarkdownFormatter.formatForDisplay(listOf(missRes), "内置聚合")
        assertTrue(missMd.contains("未命中"))
        assertFalse(containsEmoji(missMd))
    }

    private data class CapturedHttp(val request: Request, val body: String)

    private fun mockOkHttp(handler: (Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()
    }

    private fun jsonResponse(request: Request, json: String, code: Int = 200): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "ERR")
            .body(json.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    private fun readBodyString(request: Request): String {
        val body = request.body ?: return ""
        val buffer = Buffer()
        body.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun containsEmoji(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val isEmoji = (cp in 0x1F300..0x1FAFF) || (cp in 0x2600..0x27BF)
            if (isEmoji) return true
            i += Character.charCount(cp)
        }
        return false
    }
}
