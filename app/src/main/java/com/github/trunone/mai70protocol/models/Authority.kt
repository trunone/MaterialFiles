package com.github.trunone.mai70protocol.models

/**
 * Represents camera authority parameters including host, port, and authentication token.
 *
 * @property host Target camera IP address or hostname (default: "192.168.0.1").
 * @property port Target camera HTTP CGI port (default: 80).
 * @property token Active session token obtained during pairing or registration.
 */
data class Authority(
    val host: String = "192.168.0.1",
    val port: Int = 80,
    val token: String = ""
)
