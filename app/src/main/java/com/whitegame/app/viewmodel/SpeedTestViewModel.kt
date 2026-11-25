package com.whitegame.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whitegame.app.network.CloudflareSpeedResult
import com.whitegame.app.network.CloudflareSpeedTest
import com.whitegame.app.network.SpeedStage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class SpeedTestViewModel @Inject constructor() : ViewModel() {
    private val speedTest = CloudflareSpeedTest()
    private var job: Job? = null

    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var result by mutableStateOf(CloudflareSpeedResult())
        private set

    fun start() {
        if (busy) return
        busy = true
        result = CloudflareSpeedResult()
        job = viewModelScope.launch {
            try {
                result = withTimeout(35_000) {
                    speedTest.measure { stage, measurements ->
                        result = measurements
                        status = when (stage) {
                            SpeedStage.LATENCY -> "Measuring latency…"
                            SpeedStage.DOWNLOAD -> "Measuring download…"
                            SpeedStage.UPLOAD -> "Measuring upload…"
                        }
                    }
                }
                status = if (result.downloadMbps == null || result.uploadMbps == null || result.jitterMs == null) {
                    "Some measurements were unavailable."
                } else {
                    "Speed test complete."
                }
            } catch (_: TimeoutCancellationException) {
                status = "Speed test timed out."
            } catch (e: IOException) {
                status = e.message ?: "Couldn’t reach Cloudflare."
            } finally {
                busy = false
            }
        }
    }

    fun stop() {
        if (!busy) return
        status = "Speed test stopped."
        job?.cancel()
    }
}
