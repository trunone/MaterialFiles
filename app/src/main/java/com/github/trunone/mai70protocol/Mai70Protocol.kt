package com.github.trunone.mai70protocol

import com.github.trunone.mai70protocol.models.Authority
import com.github.trunone.mai70protocol.models.Category
import com.github.trunone.mai70protocol.models.FileEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Low-level protocol constants, signature generators, CGI URL builders, and JSON response parsers.
 */
object Mai70Protocol {

    /** Default salt string used in pairing and authentication algorithms */
    const val PAIRING_SALT: String = "73VpsAfdety8FDd0"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Calculates MD5 hash of an input string.
     */
    fun calculateMD5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return BigInteger(1, digest).toString(16).padStart(32, '0')
    }

    /**
     * Generates request sign key: `MD5(urlQuery + token)`.
     */
    fun generateSignKey(urlQuery: String, token: String): String =
        calculateMD5(urlQuery + token)

    /**
     * Sanitizes camera JSON responses (removes trailing commas before closing square brackets).
     */
    fun sanitizeJsonResponse(text: String): String {
        var result = text
        if (result.contains(",\n]}")) {
            result = result.replace(",\n]}", "]} ")
        } else if (result.contains(",]}")) {
            result = result.replace(",]}", "]} ")
        }
        return result
    }

    /**
     * Constructs base CGI request URL for camera endpoint.
     */
    fun buildCgiUrl(
        authority: Authority,
        cgiPath: String,
        queryParams: String,
        token: String = authority.token
    ): String {
        val queryPath = if (cgiPath.endsWith("?")) cgiPath else "$cgiPath?"
        val sign = generateSignKey(queryPath + queryParams, token)
        val connector = if (queryParams.endsWith("&") || queryParams.isEmpty()) "" else "&"
        return "http://${authority.host}:${authority.port}/cgi-bin/$queryPath$queryParams${connector}-signkey=$sign"
    }

    /**
     * Extracts string value from JSON object for given key.
     */
    fun extractJsonValue(jsonText: String, key: String): String {
        return try {
            val sanitized = sanitizeJsonResponse(jsonText)
            val jsonObject = json.parseToJsonElement(sanitized).jsonObject
            val element = jsonObject[key] ?: return ""
            element.jsonPrimitive.content
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Checks if response string indicates success (`"success"`, `"ResultCode":0`, `"ResultCode":"0"`, or `"Result":0`).
     */
    fun isSuccessResponse(responseText: String?): Boolean {
        if (responseText.isNullOrEmpty()) return false
        if (responseText.contains("success", ignoreCase = true)) return true
        val resultCode = extractJsonValue(responseText, "ResultCode")
        if (resultCode == "0") return true
        val resultVal = extractJsonValue(responseText, "Result")
        if (resultVal == "0") return true
        val statusVal = extractJsonValue(responseText, "status")
        return statusVal == "0"
    }

    /**
     * Parses `getfilecount.cgi` response into category to count map.
     */
    fun parseFileCountsResponse(responseText: String?): Map<Category, Int> {
        if (responseText.isNullOrEmpty()) return emptyMap()
        val sanitized = sanitizeJsonResponse(responseText)
        if (!isSuccessResponse(sanitized)) return emptyMap()

        val counts = mutableMapOf<Category, Int>()
        try {
            val jsonObject = json.parseToJsonElement(sanitized).jsonObject
            val resultArray = jsonObject["Result"]?.jsonArray ?: return emptyMap()
            for (element in resultArray) {
                val item = element.jsonObject
                val typeStr = item["type"]?.jsonPrimitive?.contentOrNull
                val countStr = item["count"]?.jsonPrimitive?.contentOrNull
                val type = typeStr?.toIntOrNull()
                val count = countStr?.toIntOrNull()
                if (type != null && count != null) {
                    counts[Category.fromType(type)] = count
                }
            }
        } catch (_: Exception) {
            // Fallback
        }
        return counts
    }

    /**
     * Parses `getfilelist.cgi` response into a list of [FileEntry].
     */
    fun parseFileListResponse(responseText: String?, category: Category = Category.UNKNOWN): List<FileEntry>? {
        if (responseText.isNullOrEmpty()) return null
        val sanitized = sanitizeJsonResponse(responseText)
        if (!isSuccessResponse(sanitized)) return null

        val entries = mutableListOf<FileEntry>()
        try {
            val jsonObject = json.parseToJsonElement(sanitized).jsonObject
            val resultArray = jsonObject["Result"]?.jsonArray ?: return null
            for (element in resultArray) {
                val item = element.jsonObject
                val path = item["path"]?.jsonPrimitive?.contentOrNull ?: ""
                val name = item["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val sizeStr = item["size"]?.jsonPrimitive?.contentOrNull ?: "0"
                var size = sizeStr.toLongOrNull() ?: 0L
                if (size < 0) {
                    size += (4L * 1024 * 1024 * 1024)
                }
                if (name.isNotEmpty()) {
                    entries.add(FileEntry(path = path, name = name, size = size, category = category))
                }
            }
            return entries
        } catch (_: Exception) {
            return null
        }
    }
}
