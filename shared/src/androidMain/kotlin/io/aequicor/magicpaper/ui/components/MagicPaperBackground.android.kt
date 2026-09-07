package io.aequicor.magicpaper.ui.components

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.RuntimeShader
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal actual fun rememberPaperEnvironment(): State<PaperEnvironment> {
    val context = LocalContext.current.applicationContext
    val view = LocalView.current
    val hardwareCapable = remember(context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        Build.VERSION.SDK_INT >= 33 && !manager.isLowRamDevice &&
            isPaperHardwareCapable(Runtime.getRuntime().availableProcessors(), memory.totalMem, android = true)
    }
    if (!hardwareCapable) return remember { mutableStateOf(PaperEnvironment()) }
    val battery = remember { mutableStateOf<Intent?>(null) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { battery.value = intent }
        }
        battery.value = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    val batteryIntent = battery.value
    return produceState(PaperEnvironment(), context, batteryIntent, hardwareCapable) {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        while (isActive) {
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val valid = scale > 0 && level in 0..scale
            value = PaperEnvironment(
                capable = hardwareCapable && view.isHardwareAccelerated,
                powerKnown = valid,
                batteryPercent = if (valid) (level.toLong() * 100 / scale).toInt() else null,
                powerSave = power.isPowerSaveMode ||
                    (Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE),
                reduceMotion = Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled(),
            )
            delay(2_000)
        }
    }
}

@Composable
internal actual fun rememberPaperRenderer(): PaperRenderer? = remember {
    if (Build.VERSION.SDK_INT >= 33) runCatching { AndroidPaperRenderer() }.getOrNull() else null
}

@RequiresApi(33)
private class AndroidPaperRenderer : PaperRenderer {
    private val shader = RuntimeShader(PaperShaderSource)
    private val brush = ShaderBrush(shader)

    override fun draw(scope: DrawScope, timeSeconds: Float) {
        shader.setFloatUniform("resolution", scope.size.width, scope.size.height)
        shader.setFloatUniform("time", timeSeconds)
        shader.setFloatUniform("density", scope.density)
        with(scope) { drawRect(brush) }
    }
}
