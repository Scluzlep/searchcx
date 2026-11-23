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
import com.scluzlep.searchcx.tiku.TikuSearchCache
import com.scluzlep.searchcx.tiku.TikuSearchRequest
import com.scluzlep.searchcx.tiku.TikuSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
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
        assertEquals(listOf(TikuProvider.ICODEF, TikuProvider.BUGUAKE), cfg.effectiveDirectProviders())
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
            listOf(TikuProvider.ICODEF, TikuProvider.BUGUAKE, TikuProvider.ENNCY, TikuProvider.TIKUHAI, TikuProvider.LEMON),
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
            listOf(TikuProvider.ICODEF, TikuProvider.BUGUAKE, TikuProvider.TIKUHAI, TikuProvider.WANNENG, TikuProvider.AIDIAN),
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
    fun directModeCallsFreeIcodefAndBuguakeByDefaultAndRespectsAllSevenWhenEnabled() = runBlocking {
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
        assertEquals(
            setOf("https://cx.icodef.com/wyn-nb?v=4",
                "https://easylearn.baidu.com/edu-web-go/bgk/searchlist?query=%E4%B8%AD%E5%9B%BD%E7%9A%84%E9%A6%96%E9%83%BD%E6%98%AF%EF%BC%9F&rn=10&pn=0"),
            capturedUrls.toSet()
        )

        // Now enable all 8 providers (7 direct + 1 remote-only local).
        val recordedRequests = CopyOnWriteArrayList<CapturedHttp>()
        val allClient = TikuAdapterClient(mockOkHttp { httpReq ->
            val bodyStr = readBodyString(httpReq)
            recordedRequests.add(CapturedHttp(httpReq, bodyStr))
            val url = httpReq.url.toString()
            when {
                url.startsWith("https://cx.icodef.com/wyn-nb") ->
                    jsonResponse(httpReq, """{"code":1,"data":"北京"}""")
                url.startsWith("https://easylearn.baidu.com/edu-web-go/bgk/searchlist") ->
                    jsonResponse(httpReq, """{"data":{"list":[]}}""")
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
        assertEquals(7, recordedRequests.size)

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
                url.startsWith("https://easylearn.baidu.com/edu-web-go/bgk/searchlist") ->
                    jsonResponse(req, """{"data":{"list":[]}}""")
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

    @Test
    fun cancellationPropagatesFromDirectAndRemoteSearchWithoutSwallowing() = runBlocking {
        val slowClient = TikuAdapterClient(mockOkHttp { req ->
            Thread.sleep(300)
            jsonResponse(req, """{"code":1,"data":"北京"}""")
        })
        val req = TikuSearchRequest("测试取消", type = TikuQuestionType.QA.code)

        for (mode in listOf(TikuMode.DIRECT_BUILTIN, TikuMode.REMOTE_ADAPTER)) {
            val cfg = TikuConfig(mode = mode)
            val job = async {
                slowClient.searchBatch(listOf(req), cfg)
            }
            delay(40)
            job.cancel()
            var caughtCancel = false
            try {
                job.await()
            } catch (_: CancellationException) {
                caughtCancel = true
            }
            assertTrue("Expected CancellationException in $mode", caughtCancel)
        }
    }

    @Test
    fun aidianResolvesItemOptionLettersAndSelectsBestQlistCandidate() = runBlocking {
        val aidianPayload = """
            {
              "code": 0,
              "qlist": [
                {
                  "question": "与当前毫无关系的古代历史事件是",
                  "options": ["A. 选项甲", "B. 选项乙"],
                  "answer": ["A"]
                },
                {
                  "question": "中国的首都是哪一座城市？",
                  "options": ["A. 上海", "B. 北京", "C. 广州"],
                  "answer": ["B"]
                }
              ]
            }
        """.trimIndent()

        val client = TikuAdapterClient(mockOkHttp { req ->
            when {
                req.url.toString().startsWith("http://new.api.51aidian.com/publics/newapi/freedirect") ->
                    jsonResponse(req, aidianPayload)
                else -> error("Unexpected URL: ${req.url}")
            }
        })

        val cfg = TikuConfig(
            mode = TikuMode.DIRECT_BUILTIN,
            enabledProviders = setOf("aidian")
        )
        val res = client.searchOne(
            TikuSearchRequest("中国的首都是哪座城市？", listOf("上海", "北京", "广州")),
            cfg
        )
        assertTrue(res.isHit())
        assertEquals("B", res.answerKeyText)
        assertEquals(listOf("北京"), res.bestAnswer)
    }

    @Test
    fun flattenLatexForTikuNormalizesFormulasPreservesPlainTextAndStripsImages() {
        assertEquals("1/2", OcsQuestionExtractor.flattenLatexForTiku("\$\\frac{1}{2}\$"))
        assertEquals("(a+b)/c", OcsQuestionExtractor.flattenLatexForTiku("""\dfrac{a+b}{c}"""))
        assertEquals("1/(x+1)", OcsQuestionExtractor.flattenLatexForTiku("""\tfrac{1}{x+1}"""))
        assertEquals("(a+b)/(c+d)", OcsQuestionExtractor.flattenLatexForTiku("\$\$\\frac{a+b}{c+d}\$\$"))
        assertEquals("1/(1+1/x)", OcsQuestionExtractor.flattenLatexForTiku("""\frac{1}{1+\frac{1}{x}}"""))
        assertEquals("(1/2)/(3/4)", OcsQuestionExtractor.flattenLatexForTiku("""\frac{\frac{1}{2}}{\frac{3}{4}}"""))

        assertEquals("√x", OcsQuestionExtractor.flattenLatexForTiku("""\sqrt{x}"""))
        assertEquals("√(x+1)", OcsQuestionExtractor.flattenLatexForTiku("""\sqrt{x+1}"""))
        assertEquals("√(a/b)", OcsQuestionExtractor.flattenLatexForTiku("""\sqrt{\frac{a}{b}}"""))
        assertEquals("3次根号8", OcsQuestionExtractor.flattenLatexForTiku("""\sqrt[3]{8}"""))
        assertEquals("n次根号(x+1)", OcsQuestionExtractor.flattenLatexForTiku("""\sqrt[n]{x+1}"""))

        assertEquals(
            "x^2 + a_n + x^(a+b) + a_(i,j)",
            OcsQuestionExtractor.flattenLatexForTiku("""x^{2} + a_{n} + x^{a+b} + a_{i,j}""")
        )
        assertEquals("e^(-x^2)", OcsQuestionExtractor.flattenLatexForTiku("""e^{-x^{2}}"""))
        assertEquals(
            "sin 30° + cos 60° + 45°",
            OcsQuestionExtractor.flattenLatexForTiku("""\sin 30^{\circ} + \cos 60^\circ + 45\degree""")
        )

        assertEquals(
            "(x/2)^2 + 常量 + dx + lim_(x → 0) x",
            OcsQuestionExtractor.flattenLatexForTiku(
                """\(\displaystyle \left(\frac{x}{2}\right)^{2} + \text{常量} + \mathrm{d}x + \operatorname{lim}_{x \to 0} x\)"""
            )
        )

        assertEquals(
            "a × b ÷ c ± d ∓ e · f ≤ g ≥ h ≠ 0 ≈ 1 ≡ 2 → ∞",
            OcsQuestionExtractor.flattenLatexForTiku(
                """\[a \times b \div c \pm d \mp e \cdot f \le g \ge h \ne 0 \approx 1 \equiv 2 \to \infty\]"""
            )
        )
        assertEquals(
            "∀ x ∈ A, ∃ y ∉ B ⇒ A ∪ B ∩ C ⊂ D ⊆ E",
            OcsQuestionExtractor.flattenLatexForTiku(
                """\forall x \in A, \exists y \notin B \Rightarrow A \cup B \cap C \subset D \subseteq E"""
            )
        )
        assertEquals(
            "∑ ∏ ∫ ∂ ∇ ∵ ∴ ∠ △ ⊥ ∥ ← ⇔",
            OcsQuestionExtractor.flattenLatexForTiku(
                """\sum \prod \int \partial \nabla \because \therefore \angle \triangle \perp \parallel \leftarrow \Leftrightarrow"""
            )
        )
        assertEquals(
            "α β γ θ λ π σ ω Γ Δ Θ Λ Π Σ Φ Ψ Ω",
            OcsQuestionExtractor.flattenLatexForTiku(
                """\alpha \beta \gamma \theta \lambda \pi \sigma \omega \Gamma \Delta \Theta \Lambda \Pi \Sigma \Phi \Psi \Omega"""
            )
        )

        assertEquals(
            "求 x^2 的最大值",
            OcsQuestionExtractor.flattenLatexForTiku("[图片1] 求 \$x^{2}\$ 的最大值 [图片]")
        )
        assertEquals("90%", OcsQuestionExtractor.flattenLatexForTiku("\$90\\%\$"))

        assertEquals("中国的首都是哪里？", OcsQuestionExtractor.flattenLatexForTiku("中国的首都是哪里？"))
        assertEquals(
            "原价 \$100，运费 \$10 + \$20 = \$30",
            OcsQuestionExtractor.flattenLatexForTiku("原价 \$100，运费 \$10 + \$20 = \$30")
        )
        assertEquals(
            """if (a < b && c > d) { user_name = "a\nb"; }""",
            OcsQuestionExtractor.flattenLatexForTiku("""if (a < b && c > d) { user_name = "a\nb"; }""")
        )

        val rawLatexQuiz = listOf(
            "1. 【单选题】计算 &lt;b&gt; \$\\frac{1}{2} + \\frac{1}{3}\$ [图片1]（5分）",
            "A. \$\\frac{5}{6}\$",
            "B. \$\\frac{1}{6}\$"
        ).joinToString("\n")
        val parsed = OcsQuestionExtractor.extract(rawLatexQuiz)
        assertEquals(1, parsed.size)
        assertEquals("计算 <b> 1/2 + 1/3", parsed[0].question)
        assertEquals(listOf("5/6", "1/6"), parsed[0].options)
    }

    @Test
    fun tikuSearchCacheEnforcesHitsOnlyTtlLruKeyIsolationAndPersistence() {
        val tempDir = Files.createTempDirectory("tiku_cache_test").toFile()
        try {
            var clock = 1_000L
            val cacheFile = File(tempDir, "tiku_cache.json")
            val cache = TikuSearchCache(
                persistenceFile = cacheFile,
                ttlMs = 5_000L,
                maxEntries = 2,
                nowMs = { clock }
            )

            // Misses must NOT be cached.
            val miss = TikuSearchResult(
                question = "未命中题目",
                options = emptyList(),
                type = TikuQuestionType.QA.code,
                bestAnswer = emptyList(),
                answerKey = emptyList(),
                answerKeyText = "",
                answerText = "",
                answerIndex = emptyList(),
                allAnswers = emptyList(),
                matchedProviders = emptyList(),
                error = "未找到"
            )
            cache.put("miss_key", miss)
            assertNull(cache.get("miss_key"))
            assertEquals(0, cache.size())

            // Hits are cached and LRU evicts the least-recently-used key when exceeding maxEntries.
            val hit1 = sampleHitResult("题目1", "答案1")
            val hit2 = sampleHitResult("题目2", "答案2")
            val hit3 = sampleHitResult("题目3", "答案3")

            cache.put("k1", hit1)
            clock += 100L
            cache.put("k2", hit2)
            assertEquals(2, cache.size())

            // Touch k1 so k2 becomes the eldest/LRU entry.
            clock += 100L
            assertEquals(hit1, cache.get("k1"))

            clock += 100L
            cache.put("k3", hit3)
            assertEquals(2, cache.size())
            assertNull(cache.get("k2"))
            assertEquals(hit1, cache.get("k1"))
            assertEquals(hit3, cache.get("k3"))

            // JSON persistence round-trip across separate TikuSearchCache instances.
            val reloaded = TikuSearchCache(
                persistenceFile = cacheFile,
                ttlMs = 5_000L,
                maxEntries = 2,
                nowMs = { clock }
            )
            assertEquals(2, reloaded.size())
            assertEquals(hit1, reloaded.get("k1"))
            assertEquals(hit3, reloaded.get("k3"))
            assertNull(reloaded.get("k2"))

            // TTL expiry check: k1 was stored at 1_000L, k3 at 1_300L.
            clock = 5_999L
            assertEquals(hit1, reloaded.get("k1"))
            clock = 6_000L
            assertNull(reloaded.get("k1"))
            assertEquals(hit3, reloaded.get("k3"))
            clock = 6_300L
            assertNull(reloaded.get("k3"))
            assertEquals(0, reloaded.size())

            // Corrupt cache files are safely ignored, and clear() clears persisted data.
            val corruptFile = File(tempDir, "corrupt_cache.json")
            corruptFile.writeText("{corrupt json", Charsets.UTF_8)
            val recovered = TikuSearchCache(persistenceFile = corruptFile, nowMs = { clock })
            assertEquals(0, recovered.size())
            assertNull(recovered.get("k1"))
            recovered.put("k_ok", hit1)
            assertEquals(hit1, recovered.get("k_ok"))
            recovered.clear()
            assertEquals(0, recovered.size())
            assertEquals(0, TikuSearchCache(persistenceFile = corruptFile, nowMs = { clock }).size())

            // Key isolation across modes, remote URLs, providers, types, options, and LaTeX equivalence.
            val directCfg = TikuConfig(
                mode = TikuMode.DIRECT_BUILTIN,
                remoteBaseUrl = "http://host-a:8060",
                enabledProviders = setOf("icodef", "buguake")
            )
            val directOtherRemoteCfg = directCfg.copy(remoteBaseUrl = "http://host-b:8060")
            val remoteHostACfg = directCfg.copy(mode = TikuMode.REMOTE_ADAPTER)
            val remoteHostACanonicalCfg = directCfg.copy(
                mode = TikuMode.REMOTE_ADAPTER,
                remoteBaseUrl = "http://host-a:8060/adapter-service/search"
            )
            val remoteHostBCfg = directOtherRemoteCfg.copy(mode = TikuMode.REMOTE_ADAPTER)
            val directExtraProviderCfg = directCfg.withProviderToggled("tikuhai", true)
            val directEnncyNoTokenCfg = directCfg.withProviderToggled("enncy", true)
            val directEnncyWithTokenCfg = directEnncyNoTokenCfg.copy(
                tokens = TikuProviderTokens(enncyToken = "tok_123")
            )

            val latexReqA = TikuSearchRequest(
                question = "1. 计算 \$\\frac{1}{2}\$",
                options = listOf("A. 0.5", "B. 1"),
                type = TikuQuestionType.SINGLE.code
            )
            val latexReqB = TikuSearchRequest(
                question = "2. 计算 1/2",
                options = listOf("0.5", "1"),
                type = TikuQuestionType.SINGLE.code
            )
            val diffTypeReq = latexReqB.copy(type = TikuQuestionType.MULTIPLE.code)
            val diffOptsReq = latexReqB.copy(options = listOf("0.5", "2"))

            assertEquals(
                TikuSearchCache.buildKey(latexReqA, directCfg),
                TikuSearchCache.buildKey(latexReqB, directCfg)
            )
            assertEquals(
                TikuSearchCache.buildKey(latexReqB, directCfg),
                TikuSearchCache.buildKey(latexReqB, directOtherRemoteCfg)
            )
            assertNotEquals(
                TikuSearchCache.buildKey(latexReqB, directCfg),
                TikuSearchCache.buildKey(latexReqB, remoteHostACfg)
            )
            assertEquals(
                TikuSearchCache.buildKey(latexReqB, remoteHostACfg),
                TikuSearchCache.buildKey(latexReqB, remoteHostACanonicalCfg)
            )
            assertNotEquals(
                TikuSearchCache.buildKey(latexReqB, remoteHostACfg),
                TikuSearchCache.buildKey(latexReqB, remoteHostBCfg)
            )
            assertNotEquals(
                TikuSearchCache.buildKey(latexReqB, directCfg),
                TikuSearchCache.buildKey(latexReqB, directExtraProviderCfg)
            )
            assertEquals(
                TikuSearchCache.buildKey(latexReqB, directCfg),
                TikuSearchCache.buildKey(latexReqB, directEnncyNoTokenCfg)
            )
            assertNotEquals(
                TikuSearchCache.buildKey(latexReqB, directEnncyNoTokenCfg),
                TikuSearchCache.buildKey(latexReqB, directEnncyWithTokenCfg)
            )
            assertNotEquals(
                TikuSearchCache.buildKey(latexReqB, directCfg),
                TikuSearchCache.buildKey(diffTypeReq, directCfg)
            )
            assertNotEquals(
                TikuSearchCache.buildKey(latexReqB, directCfg),
                TikuSearchCache.buildKey(diffOptsReq, directCfg)
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun tikuAdapterClientCachesHitsOnlyAndSkipsCacheOnTestConnection() = runBlocking {
        val httpCalls = AtomicInteger(0)
        val mockClient = mockOkHttp { req ->
            httpCalls.incrementAndGet()
            val body = readBodyString(req)
            when {
                body.contains("question=1%2F2") || body.contains("1/2") ->
                    jsonResponse(req, """{"code":1,"data":"0.5"}""")
                body.contains("%E9%A6%96%E9%83%BD") ->
                    jsonResponse(req, """{"code":1,"data":"北京"}""")
                else ->
                    jsonResponse(req, """{"code":0,"data":null}""")
            }
        }

        val cache = TikuSearchCache()
        val client = TikuAdapterClient(httpClient = mockClient, cache = cache)
        val cfg = TikuConfig(
            mode = TikuMode.DIRECT_BUILTIN,
            enabledProviders = setOf("icodef")
        )

        val firstHit = client.searchOne(
            TikuSearchRequest("1. \$\\frac{1}{2}\$", listOf("A. 0.5", "B. 1"), TikuQuestionType.SINGLE.code),
            cfg
        )
        assertTrue(firstHit.isHit())
        assertEquals(listOf("0.5"), firstHit.bestAnswer)
        assertEquals(1, httpCalls.get())
        assertEquals(1, cache.size())

        // Identical canonical query hits cache without making an HTTP call.
        val cachedHit = client.searchOne(
            TikuSearchRequest("2. \\frac{1}{2}", listOf("0.5", "1"), TikuQuestionType.SINGLE.code),
            cfg
        )
        assertEquals(firstHit, cachedHit)
        assertEquals(1, httpCalls.get())

        // Misses are NOT cached, so repeating the missed query hits HTTP again.
        val missReq = TikuSearchRequest("冷门未命中题目", type = TikuQuestionType.QA.code)
        val firstMiss = client.searchOne(missReq, cfg)
        assertFalse(firstMiss.isHit())
        assertEquals(2, httpCalls.get())
        val secondMiss = client.searchOne(missReq, cfg)
        assertFalse(secondMiss.isHit())
        assertEquals(3, httpCalls.get())
        assertEquals(1, cache.size())

        // testConnection must never read from or write to the search cache.
        assertTrue(client.testConnection(cfg).isSuccess)
        assertEquals(4, httpCalls.get())
        assertTrue(client.testConnection(cfg).isSuccess)
        assertEquals(5, httpCalls.get())
    }

    private fun sampleHitResult(question: String, answer: String): TikuSearchResult {
        return TikuSearchResult(
            question = question,
            options = listOf("选项甲", "选项乙"),
            type = TikuQuestionType.SINGLE.code,
            bestAnswer = listOf(answer),
            answerKey = listOf("A"),
            answerKeyText = "A",
            answerText = answer,
            answerIndex = listOf(0),
            allAnswers = listOf(listOf(answer)),
            matchedProviders = listOf("icodef"),
            error = null
        )
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
