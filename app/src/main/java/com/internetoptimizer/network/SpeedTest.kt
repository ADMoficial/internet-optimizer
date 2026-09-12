package com.internetoptimizer.network

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * Speed / latency benchmark module.
 *
 * Measures two metrics before and after tunnel activation:
 *
 * 1. **Latency**: HTTP HEAD request round-trip time to multiple DNS resolver
 *    endpoints. This directly shows the DoH optimization benefit.
 *
 * 2. **Throughput**: Download a small test file and measure bytes/second.
 *
 * The "before" measurement uses the device's default network. The "after"
 * measurement is taken once the tunnel is active (the app's own HTTP
 * traffic does NOT go through the tunnel, so it serves as a baseline).
 *
 * Privacy: No user data is sent — only synthetic HTTP requests to public
 * test endpoints. No telemetry is collected without opt-in.
 */
class SpeedTest(private val client: NetworkClient) {

    companion object {
        private const val TAG = "SpeedTest"

        // Test endpoints — public, no-auth HTTPS endpoints
        // These are chosen for reliability and global availability
        private val DNS_ENDPoints = listOf(
            "https://1.1.1.1",       // Cloudflare
            "https://8.8.8.8",       // Google
            "https://9.9.9.9",       // Quad9
            "https://dns.quad9.net", // Quad9 (hostname, for DNS resolution test)
        )

        // Small test files for throughput measurement (~1-5 MB)
        // These are public files with known sizes
        private val THROUGHPUT_URLS = listOf(
            "https://httpbin.org/bytes/1048576",  // 1 MB
            "https://httpbin.org/bytes/2097152",  // 2 MB
        )

        private const val WARMUP_REQUESTS = 2
    }

    /**
     * Run a complete benchmark: latency + throughput.
     * Measures against multiple endpoints for statistical significance.
     *
     * @param maxEndpoints How many DNS endpoints to test (top N fastest)
     * @return SpeedTestResult with before-tunnel measurements
     */
    suspend fun runBenchmark(
        maxEndpoints: Int = 3,
        mode: BenchmarkMode = BenchmarkMode.LATENCY_ONLY,
    ): SpeedTestResult = coroutineScope {
        val endpoints = DNS_ENDPoints.take(maxEndpoints)
        val results = mutableListOf<EndpointResult>()

        // Warm up: fire a few requests to establish connections
        for (i in 0 until WARMUP_REQUESTS) {
            val url = endpoints[i % endpoints.size]
            client.measureLatency(url)
            delay(50)
        }

        // Measure latency to each DNS endpoint
        val latencyDeferred = endpoints.map { url ->
            async {
                val result = client.measureLatency(url)
                EndpointResult(
                    url = url,
                    latencyMs = result.latencyMs,
                    success = result.success,
                    error = result.error,
                )
            }
        }

        val latencyResults = latencyDeferred.awaitAll()
        results.addAll(latencyResults)

        // Measure throughput if requested
        var throughputResult: ThroughputResult? = null
        if (mode == BenchmarkMode.LATENCY_AND_THROUGHPUT) {
            // Use the fastest endpoint for throughput test
            val fastest = latencyResults.filter { it.success }
                .minByOrNull { it.latencyMs }
            val throughputUrl = fastest?.url ?: THROUGHPUT_URLS[0]
            throughputResult = client.measureThroughput(throughputUrl)
        }

        SpeedTestResult(
            timestamp = System.currentTimeMillis(),
            endpointResults = results,
            throughput = throughputResult,
            avgLatencyMs = results.filter { it.success }
                .takeIf { it.isNotEmpty() }
                ?.map { it.latencyMs }
                ?.average()
                ?.toLong() ?: -1L,
        )
    }

    /**
     * Compare two benchmark results to show the improvement.
     */
    fun compareResults(
        before: SpeedTestResult,
        after: SpeedTestResult,
    ): ComparisonResult {
        val beforeAvg = before.avgLatencyMs
        val afterAvg = after.avgLatencyMs
        val latencyImprovement = if (beforeAvg > 0 && afterAvg > 0) {
            val pct = ((beforeAvg - afterAvg).toDouble() / beforeAvg) * 100
            Improvement(
                beforeMs = beforeAvg,
                afterMs = afterAvg,
                improvementPercent = pct,
                description = formatLatencyImprovement(pct),
            )
        } else {
            Improvement(-1L, -1L, 0.0, "Sem dados suficientes para comparação")
        }

        val beforeMbps = before.throughput?.speedMbps ?: 0.0
        val afterMbps = after.throughput?.speedMbps ?: 0.0
        val throughputImprovement = if (beforeMbps > 0 && afterMbps > 0) {
            val pct = ((afterMbps - beforeMbps).toDouble() / beforeMbps) * 100
            ThroughputImprovement(
                beforeMbps = beforeMbps,
                afterMbps = afterMbps,
                improvementPercent = pct,
                description = formatThroughputImprovement(pct),
            )
        } else {
            ThroughputImprovement(0.0, 0.0, 0.0, "Sem dados de throughput")
        }

        return ComparisonResult(
            latency = latencyImprovement,
            throughput = throughputImprovement,
            rawBefore = before,
            rawAfter = after,
        )
    }

    private fun formatLatencyImprovement(pct: Double): String {
        return if (pct > 0) {
            "Latência reduzida em ${String.format("%.1f", pct)}%"
        } else {
            "Latência ${if (pct < 0) "aumentada" else "mantida"}"
        }
    }

    private fun formatThroughputImprovement(pct: Double): String {
        return if (pct > 0) {
            "Throughput aumentado em ${String.format("%.1f", pct)}%"
        } else {
            "Throughput ${if (pct < 0) "reduzido" else "mantido"}"
        }
    }
}

enum class BenchmarkMode {
    LATENCY_ONLY,
    LATENCY_AND_THROUGHPUT,
}

data class EndpointResult(
    val url: String,
    val latencyMs: Long,
    val success: Boolean,
    val error: String?,
)

data class SpeedTestResult(
    val timestamp: Long,
    val endpointResults: List<EndpointResult>,
    val throughput: ThroughputResult?,
    val avgLatencyMs: Long,
)

data class ComparisonResult(
    val latency: Improvement,
    val throughput: ThroughputImprovement,
    val rawBefore: SpeedTestResult,
    val rawAfter: SpeedTestResult,
)

data class Improvement(
    val beforeMs: Long,
    val afterMs: Long,
    val improvementPercent: Double,
    val description: String,
)

data class ThroughputImprovement(
    val beforeMbps: Double,
    val afterMbps: Double,
    val improvementPercent: Double,
    val description: String,
)
