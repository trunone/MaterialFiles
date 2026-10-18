package com.github.trunone.mai70protocol.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.github.trunone.mai70protocol.http.HttpTransport
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Android implementation of [HttpTransport] backed by [OkHttpClient].
 *
 * Supports automatic camera Wi-Fi network detection and socket factory binding so the mobile phone
 * can communicate with the dashcam over Wi-Fi even when cellular mobile data is active.
 *
 * @param context Android Application Context.
 * @param defaultCameraIp Configured camera IP address (default: "192.168.0.1").
 * @param baseClient Optional customized base OkHttpClient.
 * @param onHttpLog Optional callback for logging HTTP request and response details in real time.
 */
class AndroidNetworkTransport(
    context: Context,
    private val defaultCameraIp: String = "192.168.0.1",
    baseClient: OkHttpClient = defaultOkHttpClient,
    var onHttpLog: ((String) -> Unit)? = null
) : HttpTransport {

    private val appContext: Context = context.applicationContext

    private var client: OkHttpClient = baseClient
    private var cameraClient: OkHttpClient? = null
    private var cameraNetwork: Network? = null

    /** Current IPv4 address assigned to camera interface */
    var currentIpAddress: String? = null
        private set

    /** DHCP Server (Gateway) address of detected camera Wi-Fi network */
    var dhcpServerIpAddress: String? = null
        private set

    companion object {
        /** Default reusable [OkHttpClient] instance with standard timeouts */
        val defaultOkHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Scans active Android network interfaces and binds socket factory to camera Wi-Fi network if found.
     */
    fun updateNetworkInfo() {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
        val wifiManager =
            appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

        @Suppress("DEPRECATION")
        val networks = connectivityManager.allNetworks
        var foundCameraNetwork: Network? = null

        for (network in networks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val linkProperties = connectivityManager.getLinkProperties(network)

            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val dhcpAddr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    linkProperties?.dhcpServerAddress?.hostAddress
                } else {
                    @Suppress("DEPRECATION")
                    val gateway = wifiManager?.dhcpInfo?.gateway ?: 0
                    if (gateway != 0) {
                        String.format(
                            Locale.US,
                            "%d.%d.%d.%d",
                            gateway and 0xff,
                            gateway shr 8 and 0xff,
                            gateway shr 16 and 0xff,
                            gateway shr 24 and 0xff
                        )
                    } else null
                }

                if (dhcpAddr == defaultCameraIp) {
                    foundCameraNetwork = network
                    this.dhcpServerIpAddress = dhcpAddr

                    val ipv4Address = linkProperties?.linkAddresses?.find {
                        it.address is Inet4Address
                    }?.address?.hostAddress
                    this.currentIpAddress = ipv4Address
                    break
                }
            }
        }

        if (foundCameraNetwork != null) {
            if (this.cameraNetwork != foundCameraNetwork) {
                this.cameraNetwork = foundCameraNetwork
                this.cameraClient = client.newBuilder()
                    .socketFactory(foundCameraNetwork.socketFactory)
                    .build()
            }
        } else {
            this.cameraNetwork = null
            this.cameraClient = null

            val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                connectivityManager.activeNetwork
            } else {
                null
            }
            val activeLP = connectivityManager.getLinkProperties(activeNetwork)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                this.dhcpServerIpAddress = activeLP?.dhcpServerAddress?.hostAddress
            } else {
                @Suppress("DEPRECATION")
                val gateway = wifiManager?.dhcpInfo?.gateway ?: 0
                if (gateway != 0) {
                    this.dhcpServerIpAddress = String.format(
                        Locale.US,
                        "%d.%d.%d.%d",
                        gateway and 0xff,
                        gateway shr 8 and 0xff,
                        gateway shr 16 and 0xff,
                        gateway shr 24 and 0xff
                    )
                }
            }
            this.currentIpAddress = activeLP?.linkAddresses?.find { it.address is Inet4Address }?.address?.hostAddress
        }
    }

    /**
     * Resolves the active [OkHttpClient] instance based on target URL.
     */
    fun getOkHttpClientForUrl(url: String): OkHttpClient {
        return if (url.contains(defaultCameraIp) && cameraClient != null) {
            cameraClient!!
        } else {
            client
        }
    }

    private fun buildRequest(url: String, method: String = "GET"): Request {
        return Request.Builder()
            .url(url)
            .method(method, null)
            .header("User-Agent", "70mai")
            .build()
    }

    private fun logHttp(message: String) {
        onHttpLog?.invoke(message)
    }

    override suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        updateNetworkInfo()
        val request = buildRequest(url)
        logHttp("--> GET $url")

        // Try with socket-factory bound camera client if available
        if (url.contains(defaultCameraIp) && cameraClient != null) {
            try {
                cameraClient!!.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    logHttp("<-- ${response.code} ${response.message} (${body?.length ?: 0} bytes)\n$body")
                    if (response.isSuccessful) {
                        return@withContext body
                    }
                }
            } catch (e: IOException) {
                logHttp("<-- GET failed on camera bound interface: ${e.message}")
            }
        }

        // Default client fallback
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                logHttp("<-- ${response.code} ${response.message} (${body?.length ?: 0} bytes)\n$body")
                if (!response.isSuccessful) return@withContext null
                body
            }
        } catch (e: IOException) {
            logHttp("<-- GET failed: ${e.message}")
            null
        }
    }

    override suspend fun openStream(url: String): InputStream? = withContext(Dispatchers.IO) {
        updateNetworkInfo()
        val request = buildRequest(url)
        logHttp("--> OPEN STREAM $url")

        if (url.contains(defaultCameraIp) && cameraClient != null) {
            try {
                val response = cameraClient!!.newCall(request).execute()
                logHttp("<-- STREAM ${response.code} ${response.message}")
                if (response.isSuccessful && response.body != null) {
                    return@withContext response.body!!.byteStream()
                } else {
                    response.close()
                }
            } catch (e: IOException) {
                logHttp("<-- OPEN STREAM failed on camera interface: ${e.message}")
            }
        }

        try {
            val response = client.newCall(request).execute()
            logHttp("<-- STREAM ${response.code} ${response.message}")
            if (!response.isSuccessful) {
                response.close()
                return@withContext null
            }
            val body = response.body ?: run {
                response.close()
                return@withContext null
            }
            body.byteStream()
        } catch (e: IOException) {
            logHttp("<-- OPEN STREAM failed: ${e.message}")
            null
        }
    }

    override suspend fun probe(url: String): Pair<Long, String?>? = withContext(Dispatchers.IO) {
        updateNetworkInfo()
        val activeClient = getOkHttpClientForUrl(url)
        logHttp("--> PROBE $url")

        // Try HEAD request first
        try {
            val headRequest = Request.Builder().url(url).header("User-Agent", "70mai").head().build()
            activeClient.newCall(headRequest).execute().use { response ->
                logHttp("<-- HEAD ${response.code} ${response.message}")
                if (response.isSuccessful) {
                    val length = response.header("Content-Length")?.toLongOrNull() ?: 0L
                    val lastModified = response.header("Last-Modified")
                    return@withContext Pair(length, lastModified)
                }
            }
        } catch (e: Exception) {
            logHttp("<-- HEAD probe failed: ${e.message}")
        }

        // Range request fallback
        try {
            val rangeRequest = Request.Builder().url(url).header("User-Agent", "70mai").header("Range", "bytes=0-0").build()
            activeClient.newCall(rangeRequest).execute().use { response ->
                logHttp("<-- RANGE ${response.code} ${response.message}")
                if (response.isSuccessful || response.code == 416) {
                    val contentRange = response.header("Content-Range")
                    val size = if (contentRange != null && contentRange.contains("/")) {
                        contentRange.substringAfter("/").toLongOrNull() ?: 0L
                    } else {
                        response.header("Content-Length")?.toLongOrNull() ?: 0L
                    }
                    val lastModified = response.header("Last-Modified")
                    return@withContext Pair(size, lastModified)
                }
            }
        } catch (e: Exception) {
            logHttp("<-- RANGE probe failed: ${e.message}")
        }
        null
    }
}
