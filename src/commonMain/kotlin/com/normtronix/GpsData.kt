package com.normtronix

import kotlin.math.abs
import kotlin.math.roundToLong

// GPS distance constants
object GpsConstants {
    const val FEET_PER_DEGREE_LATITUDE = 364000.0  // Approximately 111 km
    const val DISTANCE_THRESHOLD_FEET = 6.0         // 6 feet minimum distance
    const val DISTANCE_THRESHOLD_DEGREES = DISTANCE_THRESHOLD_FEET / FEET_PER_DEGREE_LATITUDE  // ≈ 0.000017

    // Coordinate delta quantization. Deltas are stored as integer units of
    // (1 / COORD_SCALE) degrees. 1e6 => ~0.36ft (~4.4") grid, matched to RTK-grade
    // sensors. Encode and decode MUST use this same value.
    const val COORD_SCALE = 1e6
    
    // Racing dynamics constants for low-performance cars (200hp, 3000lb, street tires)
    const val MAX_BRAKING_DECEL_G = 0.8  // Street tire limit for 3000lb car
    const val MAX_ACCELERATION_G = 0.5   // Limited by HP/weight ratio and tires
    const val GRAVITY_FPS2 = 32.2        // feet per second squared
    
    // Convert G-forces to mph/second for easier calculation
    const val MAX_BRAKING_MPS = MAX_BRAKING_DECEL_G * GRAVITY_FPS2 * 0.681818  // ~17.5 mph/sec
    const val MAX_ACCELERATION_MPS = MAX_ACCELERATION_G * GRAVITY_FPS2 * 0.681818  // ~11.0 mph/sec
    
    // Cornering thresholds (lower for street tires)
    const val CORNER_HEADING_CHANGE_THRESHOLD = 5.0  // degrees per second
    const val HIGH_DYNAMIC_ZONE_THRESHOLD = 0.3      // Fraction of max acceleration
}

data class GpsData(
    val latitude: Double,
    val longitude: Double,
    val speedMph: Int,
    val heading: Float,
    val timestamp: Long,
    val lapNumber: Int = 999  // 999 = unknown lap
)

data class GpsDelta(
    val timeDelta: Int,
    val speedDelta: Int,
    val headingDelta: Int,  // In 0.1 degree units
    val latDelta: Int,      // In 1/COORD_SCALE degree units (~0.36 ft / 4.4" precision)
    val lonDelta: Int       // In 1/COORD_SCALE degree units (~0.36 ft / 4.4" precision)
)

// Racing zone classification for adaptive sampling
enum class RacingZone {
    STRAIGHT,     // Low dynamic activity
    BRAKING,      // High deceleration zone
    CORNER,       // Turning zone
    ACCELERATION  // High acceleration zone
}

// Adaptive frequency analysis result
data class FrequencyAnalysis(
    val averageFrequencyHz: Double,
    val minIntervalMs: Long,
    val maxIntervalMs: Long,
    val recommendedSamplingMs: Long
)

// Compression metrics for upload efficiency
data class CompressionMetrics(
    val uncompressedSize: Int,
    val compressedSize: Int,
    val compressionRatio: Double,
    val spaceSaved: Int,
    val spaceSavedPercent: Double
)

// Represents a single lap chunk with independent compression
data class LapChunk(
    val lapNumber: Int,
    val startPoint: GpsData,
    val deltas: List<GpsDelta>
) {
    fun toBinary(): ByteArray {
        val buffer = mutableListOf<Byte>()
        
        // Lap header: lap number (4 bytes) + start point (32 bytes)
        buffer.addAll(lapNumber.toBinaryBytes())
        buffer.addAll(startPoint.latitude.toBinaryBytes())
        buffer.addAll(startPoint.longitude.toBinaryBytes())
        buffer.addAll(startPoint.speedMph.toBinaryBytes())
        buffer.addAll(startPoint.heading.toBinaryBytes())
        buffer.addAll(startPoint.timestamp.toBinaryBytes())
        
        // Number of deltas (4 bytes)
        buffer.addAll(deltas.size.toBinaryBytes())
        
        // Encode deltas in fixed order: lat, lon, speed, heading, timestamp
        // Note: time delta uses unsigned encoding since it's always positive
        for (delta in deltas) {
            buffer.addAll(delta.latDelta.toVariableLength().toList())
            buffer.addAll(delta.lonDelta.toVariableLength().toList())
            buffer.addAll(delta.speedDelta.toVariableLength().toList())
            buffer.addAll(delta.headingDelta.toVariableLength().toList())
            buffer.addAll(delta.timeDelta.toUnsignedVariableLength().toList())
        }
        
        return buffer.toByteArray()
    }
}


class GpsCompressedStream {
    private val lapChunks = mutableMapOf<Int, MutableList<GpsData>>()
    
    // Analyze GPS data frequency characteristics
    fun analyzeFrequency(lapNumber: Int = -1): FrequencyAnalysis? {
        val allPoints = if (lapNumber == -1) {
            lapChunks.values.flatten().sortedBy { it.timestamp }
        } else {
            lapChunks[lapNumber]?.sortedBy { it.timestamp } ?: return null
        }
        
        if (allPoints.size < 2) return null
        
        val intervals = mutableListOf<Long>()
        for (i in 1 until allPoints.size) {
            intervals.add(allPoints[i].timestamp - allPoints[i-1].timestamp)
        }
        
        val avgInterval = intervals.average()
        val avgFrequency = 1000.0 / avgInterval  // Convert ms to Hz
        val minInterval = intervals.minOrNull() ?: 0L
        val maxInterval = intervals.maxOrNull() ?: 0L
        
        // Recommend sampling based on actual frequency (but cap at reasonable rates)
        val recommendedMs = when {
            avgFrequency >= 8.0 -> 125L   // 8Hz -> sample every 125ms
            avgFrequency >= 5.0 -> 200L   // 5Hz -> sample every 200ms
            avgFrequency >= 2.0 -> 500L   // 2Hz -> sample every 500ms
            else -> 1000L                 // 1Hz -> sample every 1000ms
        }
        
        return FrequencyAnalysis(avgFrequency, minInterval, maxInterval, recommendedMs)
    }
    
    // Classify racing zone based on dynamics
    fun classifyDynamicZone(current: GpsData, previous: GpsData): RacingZone {
        val timeDiffSeconds = (current.timestamp - previous.timestamp) / 1000.0
        if (timeDiffSeconds <= 0) return RacingZone.STRAIGHT
        
        // Calculate speed change rate (mph/second)
        val speedChangeRate = (current.speedMph - previous.speedMph) / timeDiffSeconds
        
        // Calculate heading change rate (degrees/second)
        val headingChange = abs(current.heading - previous.heading)
        val adjustedHeadingChange = if (headingChange > 180) 360 - headingChange else headingChange
        val headingChangeRate = adjustedHeadingChange / timeDiffSeconds
        
        // Classify based on dynamics with thresholds tuned for low-performance cars
        return when {
            speedChangeRate <= -GpsConstants.HIGH_DYNAMIC_ZONE_THRESHOLD * GpsConstants.MAX_BRAKING_MPS -> RacingZone.BRAKING
            speedChangeRate >= GpsConstants.HIGH_DYNAMIC_ZONE_THRESHOLD * GpsConstants.MAX_ACCELERATION_MPS -> RacingZone.ACCELERATION
            headingChangeRate >= GpsConstants.CORNER_HEADING_CHANGE_THRESHOLD -> RacingZone.CORNER
            else -> RacingZone.STRAIGHT
        }
    }
    
    // Apply adaptive sampling within a lap
    private fun applyAdaptiveSampling(lapPoints: List<GpsData>): List<GpsData> {
        if (lapPoints.size <= 2) return lapPoints
        
        val sampledPoints = mutableListOf<GpsData>()
        sampledPoints.add(lapPoints.first()) // Always keep first point
        
        var lastSampledPoint = lapPoints.first()
        var lastSampleTime = lastSampledPoint.timestamp
        
        // Analyze lap frequency
        val freqAnalysis = analyzeFrequency(lapPoints.first().lapNumber)
        val baseSamplingInterval = freqAnalysis?.recommendedSamplingMs ?: 200L
        
        for (i in 1 until lapPoints.size) {
            val currentPoint = lapPoints[i]
            val zone = classifyDynamicZone(currentPoint, lastSampledPoint)
            
            // Determine sampling interval based on zone
            val samplingInterval = when (zone) {
                RacingZone.BRAKING, RacingZone.ACCELERATION -> baseSamplingInterval / 2  // High frequency
                RacingZone.CORNER -> (baseSamplingInterval * 0.75).toLong()              // Medium-high frequency
                RacingZone.STRAIGHT -> baseSamplingInterval * 2                          // Low frequency
            }
            
            // Apply distance threshold as well
            val latDiff = abs(currentPoint.latitude - lastSampledPoint.latitude)
            val lonDiff = abs(currentPoint.longitude - lastSampledPoint.longitude)
            val timeSinceLastSample = currentPoint.timestamp - lastSampleTime
            
            // Sample if enough time has passed OR significant movement in dynamic zones
            val shouldSample = timeSinceLastSample >= samplingInterval ||
                    (zone != RacingZone.STRAIGHT && 
                     (latDiff >= GpsConstants.DISTANCE_THRESHOLD_DEGREES || lonDiff >= GpsConstants.DISTANCE_THRESHOLD_DEGREES))
            
            if (shouldSample) {
                sampledPoints.add(currentPoint)
                lastSampledPoint = currentPoint
                lastSampleTime = currentPoint.timestamp
            }
        }
        
        // Always keep last point
        if (sampledPoints.last() != lapPoints.last()) {
            sampledPoints.add(lapPoints.last())
        }
        
        return sampledPoints
    }

    fun addPoint(point: GpsData) {
        val lapNumber = point.lapNumber
        if (!lapChunks.containsKey(lapNumber)) {
            lapChunks[lapNumber] = mutableListOf()
        }
        lapChunks[lapNumber]!!.add(point)
    }

    // Build lap chunks with adaptive sampling and compression applied
    private fun buildLapChunks(): List<LapChunk> {
        val chunks = mutableListOf<LapChunk>()
        
        for ((lapNumber, lapPoints) in lapChunks.entries.sortedBy { it.key }) {
            if (lapPoints.isEmpty()) continue
            
            // Apply adaptive sampling first
            val sampledPoints = applyAdaptiveSampling(lapPoints.sortedBy { it.timestamp })
            if (sampledPoints.isEmpty()) continue
            
            val startPoint = sampledPoints.first()
            val deltas = mutableListOf<GpsDelta>()
            var lastPoint = startPoint
            // Error-feedback quantization: lat/lon deltas are measured against the
            // *reconstructed* previous position (what the decoder will actually
            // rebuild), not the original point. This bounds coordinate error to a
            // single quantization step forever instead of letting rounding error
            // random-walk (drift) across the sector. The decoder is unchanged.
            var reconLat = startPoint.latitude
            var reconLon = startPoint.longitude

            // Apply delta compression to sampled points
            for (i in 1 until sampledPoints.size) {
                val point = sampledPoints[i]

                // Store deltas for all fields
                val timeDelta = (point.timestamp - lastPoint.timestamp).toInt()
                val speedDelta = point.speedMph - lastPoint.speedMph
                val headingDelta = ((point.heading - lastPoint.heading) * 10).toInt()
                val latDelta = roundHalfUp((point.latitude - reconLat) * GpsConstants.COORD_SCALE).toInt()
                val lonDelta = roundHalfUp((point.longitude - reconLon) * GpsConstants.COORD_SCALE).toInt()
                // Advance the reconstructed position exactly as the decoder will.
                reconLat += latDelta / GpsConstants.COORD_SCALE
                reconLon += lonDelta / GpsConstants.COORD_SCALE

                deltas.add(GpsDelta(timeDelta, speedDelta, headingDelta, latDelta, lonDelta))
                lastPoint = point
            }
            
            chunks.add(LapChunk(lapNumber, startPoint, deltas))
        }
        
        return chunks
    }
    
    fun toBinary(): ByteArray {
        val chunks = buildLapChunks()
        val buffer = mutableListOf<Byte>()
        
        // Simplified header: magic number + version + number of chunks
        buffer.addAll("GPSC".encodeToByteArray().toList()) // Magic number (4 bytes)
        buffer.addAll(1.toBinaryBytes()) // Version (4 bytes) 
        buffer.addAll(chunks.size.toBinaryBytes()) // Number of chunks (4 bytes)
        
        // Write chunks sequentially
        for (chunk in chunks) {
            val chunkBytes = chunk.toBinary()
            // Write chunk size first, then chunk data
            buffer.addAll(chunkBytes.size.toBinaryBytes())
            buffer.addAll(chunkBytes.toList())
        }
        
        return buffer.toByteArray()
    }
    
    // Get statistics for testing
    fun getStats(): Pair<Int, Int> {
        val chunks = buildLapChunks()
        val totalOriginalPoints = lapChunks.values.sumOf { it.size }
        val totalCompressedDeltas = chunks.sumOf { it.deltas.size }
        return Pair(totalOriginalPoints, totalCompressedDeltas)
    }

    // Clear data to free memory after compression/upload
    fun clearData() {
        lapChunks.clear()
    }
    
    // Get compression efficiency metrics
    fun getCompressionMetrics(): CompressionMetrics {
        val uncompressedSize = lapChunks.values.sumOf { it.size } * 60 // 60 bytes per GpsData object
        val compressedSize = toBinary().size
        val compressionRatio = uncompressedSize.toDouble() / compressedSize
        val spaceSaved = uncompressedSize - compressedSize
        val spaceSavedPercent = (spaceSaved.toDouble() / uncompressedSize) * 100
        
        return CompressionMetrics(
            uncompressedSize = uncompressedSize,
            compressedSize = compressedSize,
            compressionRatio = compressionRatio,
            spaceSaved = spaceSaved,
            spaceSavedPercent = spaceSavedPercent
        )
    }
    
    companion object {
        fun fromBinary(binaryData: ByteArray): List<GpsData> {
            val buffer = ByteReader(binaryData)
            val allPoints = mutableListOf<GpsData>()
            
            // Read file header
            val magic = ByteArray(4)
            buffer.get(magic)
            if (magic.decodeToString() != "GPSC") {
                throw IllegalArgumentException("Invalid file format")
            }
            
            val version = buffer.getInt()
            if (version != 1) {
                throw IllegalArgumentException("Unsupported version: $version")
            }
            
            val chunkCount = buffer.getInt()
            
            // Read each chunk sequentially (simplified format)
            for (i in 0 until chunkCount) {
                try {
                    // Read chunk size
                    val chunkSize = buffer.getInt()
                    
                    // Read chunk data
                    val chunkBytes = ByteArray(chunkSize)
                    buffer.get(chunkBytes)
                    
                    // Parse chunk
                    val chunkPoints = parseChunk(chunkBytes)
                    allPoints.addAll(chunkPoints)
                    
                } catch (e: Exception) {
                    // Skip corrupted chunk, continue with others
                    println("Warning: Skipping corrupted chunk $i: ${e.message}")
                }
            }
            
            return allPoints.sortedBy { it.timestamp }
        }
        
        private fun parseChunk(chunkData: ByteArray): List<GpsData> {
            val buffer = ByteReader(chunkData)
            val points = mutableListOf<GpsData>()
            
            // Read chunk header
            val lapNumber = buffer.getInt()
            val startLat = buffer.getDouble()
            val startLon = buffer.getDouble()
            val startSpeed = buffer.getInt()
            val startHeading = buffer.getFloat()
            val startTimestamp = buffer.getLong()
            
            val deltaCount = buffer.getInt()
            
            // Add start point
            var currentPoint = GpsData(startLat, startLon, startSpeed, startHeading, startTimestamp, lapNumber)
            points.add(currentPoint)
            
            // Read deltas and reconstruct points
            for (i in 0 until deltaCount) {
                val latDelta = buffer.readVariableLength()
                val lonDelta = buffer.readVariableLength()
                val speedDelta = buffer.readVariableLength()
                val headingDelta = buffer.readVariableLength()
                val timeDelta = buffer.readUnsignedVariableLength()

                val lat = currentPoint.latitude + latDelta / GpsConstants.COORD_SCALE
                val lon = currentPoint.longitude + lonDelta / GpsConstants.COORD_SCALE
                val speed = currentPoint.speedMph + speedDelta
                val heading = currentPoint.heading + headingDelta / 10.0f
                val timestamp = currentPoint.timestamp + timeDelta

                currentPoint = GpsData(lat, lon, speed, heading, timestamp, lapNumber)
                points.add(currentPoint)
            }
            
            return points
        }
    }
    
}

// Same rounding as java.lang.Math.round: ties go towards positive infinity and
// NaN becomes 0. kotlin.math.round would round ties to even, changing the encoding.
internal fun roundHalfUp(value: Double): Long = if (value.isNaN()) 0L else value.roundToLong()

// Extension functions for binary serialization (big-endian)
fun Double.toBinaryBytes(): List<Byte> = toRawBits().toBinaryBytes()

fun Float.toBinaryBytes(): List<Byte> = toRawBits().toBinaryBytes()

fun Int.toBinaryBytes(): List<Byte> = (3 downTo 0).map { (this shr (it * 8)).toByte() }

fun Long.toBinaryBytes(): List<Byte> = (7 downTo 0).map { (this shr (it * 8)).toByte() }

// Minimal big-endian reader over a byte array; throws IndexOutOfBoundsException
// when it runs off the end, which fromBinary treats as a corrupted chunk.
internal class ByteReader(private val bytes: ByteArray) {
    private var position = 0

    fun get(): Byte {
        if (position >= bytes.size) throw IndexOutOfBoundsException("Read past end of data")
        return bytes[position++]
    }

    fun get(destination: ByteArray) {
        if (position + destination.size > bytes.size) throw IndexOutOfBoundsException("Read past end of data")
        bytes.copyInto(destination, 0, position, position + destination.size)
        position += destination.size
    }

    fun getInt(): Int {
        var result = 0
        repeat(4) { result = (result shl 8) or (get().toInt() and 0xFF) }
        return result
    }

    fun getLong(): Long {
        var result = 0L
        repeat(8) { result = (result shl 8) or (get().toLong() and 0xFF) }
        return result
    }

    fun getFloat(): Float = Float.fromBits(getInt())

    fun getDouble(): Double = Double.fromBits(getLong())
}

// Variable-length encoding for signed integers (zigzag encoding)
fun Int.toVariableLength(): ByteArray {
    val result = mutableListOf<Byte>()
    // ZigZag encode to handle negative values
    var value = (this shl 1) xor (this shr 31)
    
    while (value >= 0x80) {
        result.add(((value and 0x7F) or 0x80).toByte())
        value = value ushr 7
    }
    result.add(value.toByte())
    
    return result.toByteArray()
}

// Variable-length encoding for unsigned integers (no zigzag needed)
fun Int.toUnsignedVariableLength(): ByteArray {
    val result = mutableListOf<Byte>()
    var value = this
    
    while (value >= 0x80) {
        result.add(((value and 0x7F) or 0x80).toByte())
        value = value ushr 7
    }
    result.add(value.toByte())
    
    return result.toByteArray()
}

// Read variable-length integer with zigzag decoding
internal fun ByteReader.readVariableLength(): Int {
    val unsignedValue = this.readUnsignedVariableLength()
    // ZigZag decode to handle negative values
    return (unsignedValue ushr 1) xor (-(unsignedValue and 1))
}

// Read unsigned variable-length integer (no zigzag decoding)
internal fun ByteReader.readUnsignedVariableLength(): Int {
    var result = 0
    var shift = 0
    
    while (true) {
        val byte = this.get().toInt() and 0xFF
        result = result or ((byte and 0x7F) shl shift)
        
        if ((byte and 0x80) == 0) break
        
        shift += 7
    }
    
    return result
}

