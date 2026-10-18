package com.normtronix

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create

// Swift-friendly upload helper: the compressed stream as Foundation Data
// (Data in Swift) instead of a KotlinByteArray. Same bytes as toBinary().
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun GpsCompressedStream.toData(): NSData {
    val bytes = toBinary()
    return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
}
