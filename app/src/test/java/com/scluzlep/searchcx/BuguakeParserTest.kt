package com.scluzlep.searchcx

import com.scluzlep.searchcx.tiku.BuguakeParser
import com.scluzlep.searchcx.tiku.TikuAdapterClient
import com.scluzlep.searchcx.tiku.TikuConfig
import com.scluzlep.searchcx.tiku.TikuMode
import com.scluzlep.searchcx.tiku.TikuSearchRequest
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

class BuguakeParserTest {
    @Test
    fun decodesUpstreamBase64XorPayload() {
        assertEquals("A", BuguakeParser.decode("cjQ=", ""))
    }

    @Test
    fun extractsOnlyAnswersFromSimilarQuestionsAndSupportsFallback() {
        val expected = JSONObject(
            """{"que_stem":[{"c":[{"c":"中国的首都是？"}]}],
               "que_options":[[
                  {"yes":false,"ret":[{"c":[{"c":"上海"}]}]},
                  {"yes":true,"ret":[{"c":[{"c":"北京"}]}]}
               ]],"que_answer":[{"c":[{"c":"不应采用的兜底答案"}]}]}"""
        )
        val irrelevant = JSONObject(
            """{"que_stem":[{"c":[{"c":"不相关的物理定律是哪条"}]}],
                "que_answer":[{"c":[{"c":"错误答案"}]}]}"""
        )
        val fallback = JSONObject(
            """{"que_stem":[{"c":[{"c":"中国的首都是？"}]}],
                "que_answer":[{"c":[{"c":"北京"}]}]}"""
        )
        val payload = JSONObject().put("data", JSONObject().put("list", JSONArray()
            .put(entry(expected))
            .put(entry(irrelevant))
            .put(entry(fallback))
            .put(JSONObject().put("bdjson", "invalid-base64").put("actk", "test"))))
        assertEquals(listOf("北京"), BuguakeParser.parse("中国的首都是？", payload))
    }

    @Test
    fun defaultDirectModeCanHitBuguakeWithoutServer() = runBlocking {
        val question = "中国的首都是？"
        val upstream = JSONObject(
            """{"que_stem":[{"c":[{"c":"中国的首都是？"}]}],
                "que_answer":[{"c":[{"c":"北京"}]}]}"""
        )
        val payload = JSONObject().put("data", JSONObject().put("list", JSONArray().put(entry(upstream))))
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val body = if (request.url.host == "easylearn.baidu.com") {
                assertEquals("GET", request.method)
                assertEquals(question, request.url.queryParameter("query"))
                assertEquals("10", request.url.queryParameter("rn"))
                assertEquals("0", request.url.queryParameter("pn"))
                payload.toString()
            } else {
                """{"code":1,"data":null}"""
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
        val client = TikuAdapterClient(http)
        val res = client.searchOne(
            TikuSearchRequest(question, listOf("上海", "北京")),
            TikuConfig(mode = TikuMode.DIRECT_BUILTIN)
        )
        assertTrue(res.isHit())
        assertEquals(listOf("buguake"), res.matchedProviders)
        assertEquals("B", res.answerKeyText)
    }

    private fun entry(data: JSONObject): JSONObject {
        val actk = "test"
        val escaped = URLEncoder.encode(data.toString(), "UTF-8")
        val bytes = ByteArray(escaped.length * 2)
        for (i in escaped.indices) {
            bytes[i * 2] = escaped[i].code.toByte()
        }
        val key = ("34cab29ef956d78afd" + actk).toByteArray(Charsets.UTF_8)
        for (i in bytes.indices) {
            bytes[i] = (bytes[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return JSONObject()
            .put("bdjson", Base64.getEncoder().encodeToString(bytes))
            .put("actk", actk)
    }
}
