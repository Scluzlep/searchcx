package com.scluzlep.searchcx.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64

class PkceOAuthManagerTest {

    private val oauth = PkceOAuthManager()

    @Test
    fun generatesRfc7636CompliantVerifierAndS256Challenge() {
        val verifier = oauth.generateCodeVerifier()
        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.matches(Regex("^[A-Za-z0-9_-]+$")))

        // RFC 7636 Appendix B vector
        val challenge = oauth.computeS256Challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)
    }

    @Test
    fun buildsAuthorizeUrlsAndExtractsCodexClaimsFromJwt() {
        val customUrl = oauth.buildAuthorizeUrl(
            authEndpoint = "https://auth.example.com/authorize",
            clientId = "client_123",
            scopes = "openid offline_access",
            state = "st_abc",
            codeChallenge = "ch_xyz"
        )
        assertTrue(customUrl.contains("response_type=code"))
        assertTrue(customUrl.contains("code_challenge_method=S256"))
        assertTrue(customUrl.contains("redirect_uri=searchcx%3A%2F%2Foauth%2Fcallback"))

        val codexUrl = oauth.buildCodexAuthorizeUrl(
            state = "st_codex",
            codeChallenge = "ch_codex",
            redirectUri = "http://127.0.0.1:1455/auth/callback"
        )
        assertTrue(codexUrl.contains("originator=codex_cli_rs"))
        assertTrue(codexUrl.contains("codex_cli_simplified_flow=true"))
        assertTrue(codexUrl.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback"))

        val fakePayloadJson = """
            {
              "email": "user@example.com",
              "exp": 1900000000,
              "https://api.openai.com/auth": {
                "chatgpt_account_id": "acct_999",
                "chatgpt_plan_type": "plus"
              }
            }
        """.trimIndent()
        val enc = Base64.getUrlEncoder().withoutPadding()
        val fakeJwt = enc.encodeToString("""{"alg":"none"}""".toByteArray(StandardCharsets.UTF_8)) +
            "." + enc.encodeToString(fakePayloadJson.toByteArray(StandardCharsets.UTF_8)) + ".sig"

        val parsed = oauth.parseTokenResponse(
            """{"access_token":"$fakeJwt","refresh_token":"ref_xyz","expires_in":3600,"id_token":"$fakeJwt"}"""
        )
        val codexCred = oauth.toCodexCredential(parsed, nowMs = 1_000_000L)
        assertEquals("acct_999", codexCred.accountId)
        assertEquals("user@example.com", codexCred.email)
        assertEquals("Plus", codexCred.plan)
        assertEquals(4_600_000L, codexCred.expiresAtMs)
    }

    @Test
    fun loopbackOAuthServerReceivesLocalhostCallback() = runBlocking {
        val server = LoopbackOAuthServer(port = 0, callbackPath = "/auth/callback")
        try {
            server.startListening(expectedState = "state_ok")
            val deferred = async(Dispatchers.IO) {
                server.awaitCallback(expectedState = "state_ok", timeoutMs = 5_000L)
            }
            val code = withContext(Dispatchers.IO) {
                val conn = URL("${server.redirectUri}?code=auth_code_123&state=state_ok")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 2_000
                conn.readTimeout = 2_000
                try {
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
            }
            assertEquals(200, code)

            val result = deferred.await()
            assertEquals("auth_code_123", result.code)
            assertEquals("state_ok", result.state)
        } finally {
            server.close()
        }
    }
}
