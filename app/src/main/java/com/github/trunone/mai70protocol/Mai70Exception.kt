package com.github.trunone.mai70protocol

import java.io.IOException

/**
 * Exception subtype thrown on 70mai dashcam protocol failures.
 */
open class Mai70Exception(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * Thrown when camera pair confirmation times out or is rejected.
 */
class Mai70PairingException(
    message: String,
    cause: Throwable? = null
) : Mai70Exception(message, cause)

/**
 * Thrown when camera client registration or authentication key validation fails.
 */
class Mai70AuthenticationException(
    message: String,
    cause: Throwable? = null
) : Mai70Exception(message, cause)

/**
 * Thrown when target remote file cannot be located or retrieved from candidate URLs.
 */
class Mai70FileNotFoundException(
    message: String,
    cause: Throwable? = null
) : Mai70Exception(message, cause)
