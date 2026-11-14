package com.phiragen.app.ui

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.phiragen.app.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 动效规范. 动画时长由设计决定, 不跟随手指速度:
 * 用户滑得再快, 收尾动画也不会短于下限, 避免页面瞬间跳走
 */
object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val PUSH_MS = 400
    const val POP_MS = 360

    /** 手势返回确认后的收尾动画: 剩余距离越短越慢地走完, 但不少于下限 */
    const val COMMIT_MIN_MS = 300
    const val COMMIT_MAX_MS = 440

    /** 手势返回被取消时的回弹 */
    const val CANCEL_MIN_MS = 220
    const val CANCEL_MAX_MS = 320

    fun commitMs(progress: Float): Int = (COMMIT_MAX_MS * (1f - progress)).toInt().coerceIn(COMMIT_MIN_MS, COMMIT_MAX_MS)
    fun cancelMs(progress: Float): Int = (CANCEL_MAX_MS * progress).toInt().coerceIn(CANCEL_MIN_MS, CANCEL_MAX_MS)
}

private enum class Mode { Idle, Push, Pop, Drag }

private fun GraphicsLayerScope.applyBlur(radiusPx: Float) {
    renderEffect = if (radiusPx > 0.5f) BlurEffect(radiusPx, radiusPx, TileMode.Decal) else null
}

/**
 * 页面宿主, 负责页面切换动画与预测性返回.
 * 动画进度只在绘制阶段读取 (graphicsLayer), 转场期间页面内容不会因进度变化而重组;
 * 各页面以页面本身为 key, 切换前后保留滚动位置与输入状态
 */
@Composable
fun PageHost(
    screen: Screen,
    depth: Int,
    backTarget: Screen?,
    backEnabled: Boolean,
    blur: Boolean,
    onBack: () -> Unit,
    page: @Composable (Screen, Boolean) -> Unit,
) {
    var front by remember { mutableStateOf(screen) }
    var behind by remember { mutableStateOf<Screen?>(null) }
    var mode by remember { mutableStateOf(Mode.Idle) }
    var lastDepth by remember { mutableIntStateOf(depth) }
    var direction by remember { mutableFloatStateOf(1f) }
    val progress = remember { Animatable(0f) }

    /** 立即结束进行中的转场, 回到静止状态 */
    suspend fun settle() {
        if (mode == Mode.Pop || mode == Mode.Drag) front = behind ?: front
        behind = null
        mode = Mode.Idle
        progress.snapTo(0f)
    }

    // 跟随应用内导航: 层级变深为推入, 变浅为弹出, 相同层级的替换按推入处理
    LaunchedEffect(screen) {
        if (screen == front) {
            if (mode == Mode.Idle) lastDepth = depth
            return@LaunchedEffect
        }
        settle()
        val push = depth >= lastDepth
        lastDepth = depth
        direction = 1f
        if (push) {
            behind = front
            front = screen
            mode = Mode.Push
            progress.animateTo(1f, tween(Motion.PUSH_MS, easing = Motion.Emphasized))
        } else {
            behind = screen
            mode = Mode.Pop
            progress.animateTo(1f, tween(Motion.POP_MS, easing = Motion.Emphasized))
        }
        settle()
    }

    PredictiveBackHandler(enabled = backEnabled) { events ->
        val target = backTarget
        if (target == null || mode != Mode.Idle) {
            events.collect { }
            onBack()
            return@PredictiveBackHandler
        }
        try {
            behind = target
            mode = Mode.Drag
            events.collect { e ->
                if (progress.value < 0.001f) direction = if (e.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                progress.snapTo(e.progress)
            }
            // 手势确认返回: 无论滑得多快, 收尾动画都有最短时长
            progress.animateTo(1f, tween(Motion.commitMs(progress.value), easing = Motion.Emphasized))
            front = target
            behind = null
            mode = Mode.Idle
            progress.snapTo(0f)
            onBack()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                progress.animateTo(0f, tween(Motion.cancelMs(progress.value), easing = Motion.Emphasized))
                behind = null
                mode = Mode.Idle
            }
            throw cancelled
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val blurPx = if (blur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 8f * LocalDensity.current.density else 0f
        val below = behind
        val layers = if (below != null) listOf(below, front) else listOf(front)
        layers.forEach { s ->
            key(s) {
                val isFront = s == front
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        val p = progress.value
                        when (mode) {
                            Mode.Idle -> Unit
                            Mode.Push -> if (isFront) {
                                translationX = widthPx * 0.16f * (1f - p)
                                alpha = 0.6f + 0.4f * p
                            } else {
                                val sc = 1f - 0.035f * p
                                scaleX = sc
                                scaleY = sc
                                alpha = 1f - 0.12f * p
                                applyBlur(blurPx * p)
                            }
                            Mode.Pop, Mode.Drag -> if (isFront) {
                                translationX = widthPx * direction * p
                                val sc = 1f - 0.025f * p
                                scaleX = sc
                                scaleY = sc
                                alpha = 1f - 0.08f * p
                            } else {
                                val sc = 0.965f + 0.035f * p
                                scaleX = sc
                                scaleY = sc
                                alpha = 0.88f + 0.12f * p
                                applyBlur(blurPx * (1f - p))
                            }
                        }
                    },
                ) { page(s, !isFront) }
            }
        }
    }
}
