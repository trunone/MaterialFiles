package com.github.trunone.mai70protocol

import kotlinx.coroutines.delay
import com.github.trunone.mai70protocol.http.HttpTransport
import com.github.trunone.mai70protocol.models.Authority
import kotlin.random.Random

/**
 * Handles 70mai pairing, token extraction, and registration workflows.
 */
class Mai70Auth(
    private val transport: HttpTransport,
    private val host: String = "192.168.0.1",
    private val port: Int = 80
) {

    /**
     * Executes the pairing handshake:
     * 1. Call `BindByBanya.cgi` with robust query format fallbacks
     * 2. Poll `UserconfirmByBanya.cgi` until button press confirmed
     * 3. Register client keep-alive
     *
     * @param onWaitingForButton Callback invoked before polling for camera button press.
     * @param maxAttempts Maximum polling retries (default 20).
     * @param delayMs Delay between retries in ms (default 5000ms).
     * @return Final session token string.
     * @throws Mai70PairingException on binding failure or timeout.
     */
    suspend fun pair(
        onWaitingForButton: suspend () -> Unit = {},
        maxAttempts: Int = 20,
        delayMs: Long = 5000
    ): String {
        val userId = Random.nextInt(100000, 999999).toString()
        val timestamp = (System.currentTimeMillis() / 1000L).toString()

        // Step 1: BindByBanya with fallback candidate formats
        val bindCandidates = listOf(
            // Format 0: Standard leading & after ?
            run {
                val qp = "&-usr=$userId"
                val sign = Mai70Protocol.calculateMD5("BindByBanya.cgi?$qp" + Mai70Protocol.PAIRING_SALT)
                "http://$host:$port/cgi-bin/BindByBanya.cgi?$qp&-signkey=$sign"
            },
            // Format 1: Direct key-value with userId + salt hash
            run {
                val sign = Mai70Protocol.calculateMD5(userId + Mai70Protocol.PAIRING_SALT)
                "http://$host:$port/cgi-bin/BindByBanya.cgi?&-usr=$userId&-signkey=$sign"
            },
            // Format 2: Without leading &
            run {
                val qp = "-usr=$userId"
                val sign = Mai70Protocol.calculateMD5("BindByBanya.cgi?$qp" + Mai70Protocol.PAIRING_SALT)
                "http://$host:$port/cgi-bin/BindByBanya.cgi?$qp&-signkey=$sign"
            },
            // Format 3: With timestamp
            run {
                val qp = "&-usr=$userId&-timestamp=$timestamp"
                val sign = Mai70Protocol.calculateMD5("BindByBanya.cgi?$qp" + Mai70Protocol.PAIRING_SALT)
                "http://$host:$port/cgi-bin/BindByBanya.cgi?$qp&-signkey=$sign"
            },
            // Format 4: Standard parameters without '-'
            run {
                val qp = "usr=$userId"
                val sign = Mai70Protocol.calculateMD5("BindByBanya.cgi?$qp" + Mai70Protocol.PAIRING_SALT)
                "http://$host:$port/cgi-bin/BindByBanya.cgi?$qp&signkey=$sign"
            }
        )

        var bindResponse: String? = null
        for (bindUrl in bindCandidates) {
            val response = transport.get(bindUrl)
            if (response != null && Mai70Protocol.isSuccessResponse(response)) {
                bindResponse = response
                break
            }
        }

        if (bindResponse == null) {
            throw Mai70PairingException("Empty or failed response from BindByBanya across candidate URLs. Check camera Wi-Fi connection.")
        }

        val token = Mai70Protocol.extractJsonValue(bindResponse, "Token")
        val bindTimestamp = Mai70Protocol.extractJsonValue(bindResponse, "timestamp").ifEmpty { timestamp }

        if (token.isEmpty()) {
            throw Mai70PairingException("Failed to extract token from BindByBanya response: $bindResponse")
        }

        // Step 2: UserconfirmByBanya
        val confirmCandidates = listOf(
            run {
                val qp = "&-timestamp=$bindTimestamp"
                val sign = Mai70Protocol.calculateMD5(bindTimestamp + Mai70Protocol.PAIRING_SALT)
                "http://$host:$port/cgi-bin/UserconfirmByBanya.cgi?$qp&-signkey=$sign"
            },
            run {
                val qp = "-timestamp=$bindTimestamp"
                val sign = Mai70Protocol.calculateMD5("UserconfirmByBanya.cgi?$qp" + Mai70Protocol.PAIRING_SALT)
                "http://$host:$port/cgi-bin/UserconfirmByBanya.cgi?$qp&-signkey=$sign"
            },
            run {
                val qp = "timestamp=$bindTimestamp"
                val sign = Mai70Protocol.calculateMD5("UserconfirmByBanya.cgi?$qp" + Mai70Protocol.PAIRING_SALT)
                "http://$host:$port/cgi-bin/UserconfirmByBanya.cgi?$qp&signkey=$sign"
            }
        )

        onWaitingForButton()

        var confirmedToken: String? = null
        for (attempt in 1..maxAttempts) {
            for (confirmUrl in confirmCandidates) {
                val confirmResponse = transport.get(confirmUrl)
                if (confirmResponse != null && Mai70Protocol.isSuccessResponse(confirmResponse)) {
                    val finalToken = Mai70Protocol.extractJsonValue(confirmResponse, "Token")
                    confirmedToken = finalToken.ifEmpty { token }
                    break
                }
            }
            if (confirmedToken != null) break
            if (attempt < maxAttempts) {
                delay(delayMs)
            }
        }

        val activeToken = confirmedToken
            ?: throw Mai70PairingException("User button confirmation timed out after $maxAttempts attempts")

        // Step 3: Register client
        val authority = Authority(host = host, port = port, token = activeToken)
        register(authority)

        return activeToken
    }

    /**
     * Registers active phone client with camera (`client.cgi`).
     *
     * @param authority Target [Authority] containing host, port, and token.
     * @param clientIp Client IP address (default: "192.168.0.2").
     * @return `true` if registration confirmed.
     */
    suspend fun register(authority: Authority, clientIp: String = "192.168.0.2"): Boolean {
        val timestamp = System.currentTimeMillis() / 1000L
        val queryPath = "client.cgi?"
        val queryParams = "&-operation=register&-ip=$clientIp&-timestamp=$timestamp"

        val url = Mai70Protocol.buildCgiUrl(authority, queryPath, queryParams)
        val responseText = transport.get(url) ?: return false
        return Mai70Protocol.isSuccessResponse(responseText)
    }
}
