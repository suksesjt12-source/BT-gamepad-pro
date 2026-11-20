package com.btgamepad.pro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

class GamepadView(
    context: Context,
    private val onReport: (ByteArray) -> Unit,
    private val onConnect: () -> Unit,
    private val onSettings: () -> Unit
) : View(context) {
    var statusText: String = "Tekan HUBUNGKAN untuk menyiapkan Bluetooth HID."
        set(value) { field = value; invalidate() }

    var deadzone: Float = 0.08f
    var sensitivity: Float = 1.0f
    var stickScale: Float = 1.0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttons = BooleanArray(16)
    private val owners = Array(16) { mutableSetOf<Int>() }
    private val pointerTargets = mutableMapOf<Int, Target>()
    private val pointerButtons = mutableMapOf<Int, Set<Int>>()
    private var leftX = 0f
    private var leftY = 0f
    private var rightX = 0f
    private var rightY = 0f
    private val connectRect = RectF()
    private val settingsRect = RectF()
    private val density = resources.displayMetrics.density

    private enum class TargetKind { STICK_LEFT, STICK_RIGHT, DPAD, BUTTON }
    private data class Target(val kind: TargetKind, val button: Int = -1)

    init { isFocusable = true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(0xFF10131A.toInt())
        paint.style = Paint.Style.FILL
        paint.color = 0xFF1A202B.toInt()
        canvas.drawRoundRect(18f, 14f, w - 18f, h - 14f, 22f, 22f, paint)

        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = 20f * density
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        canvas.drawText("BT GAMEPAD PRO", 34f, 43f * density, paint)
        paint.color = 0xFF9BA8B8.toInt()
        paint.textSize = 11f * density
        paint.typeface = android.graphics.Typeface.DEFAULT
        canvas.drawText(statusText.take(100), 34f, 64f * density, paint)

        connectRect.set(w - 164f * density, 20f * density, w - 64f * density, 54f * density)
        paint.color = 0xFF176F62.toInt()
        canvas.drawRoundRect(connectRect, 12f, 12f, paint)
        drawCentered(canvas, "HUBUNGKAN", connectRect.centerX(), connectRect.centerY() + 4f * density, 10f, 0xFFFFFFFF.toInt())

        settingsRect.set(w - 52f * density, 20f * density, w - 20f * density, 54f * density)
        paint.color = 0xFF303B4B.toInt()
        canvas.drawRoundRect(settingsRect, 10f, 10f, paint)
        drawCentered(canvas, "⚙", settingsRect.centerX(), settingsRect.centerY() + 5f * density, 18f, 0xFFFFFFFF.toInt())

        drawPill(canvas, w * 0.10f, h * 0.19f, "L2", 0xFF35465A.toInt())
        drawPill(canvas, w * 0.25f, h * 0.19f, "L1", 0xFF35465A.toInt())
        drawPill(canvas, w * 0.72f, h * 0.19f, "R1", 0xFF35465A.toInt())
        drawPill(canvas, w * 0.87f, h * 0.19f, "R2", 0xFF35465A.toInt())
        drawPill(canvas, w * 0.45f, h * 0.19f, "SELECT", 0xFF303B4B.toInt())
        drawPill(canvas, w * 0.56f, h * 0.19f, "START", 0xFF303B4B.toInt())

        val stickR = h * 0.125f * stickScale
        drawStick(canvas, w * 0.16f, h * 0.68f, stickR, leftX, leftY, "L3")
        drawStick(canvas, w * 0.62f, h * 0.68f, stickR, rightX, rightY, "R3")
        drawDpad(canvas, w * 0.385f, h * 0.68f, h * 0.115f)
        drawFaceButton(canvas, w * 0.84f, h * 0.45f, "Y", 0xFFC49B3B.toInt())
        drawFaceButton(canvas, w * 0.77f, h * 0.57f, "X", 0xFF397EC3.toInt())
        drawFaceButton(canvas, w * 0.91f, h * 0.57f, "B", 0xFFC94B55.toInt())
        drawFaceButton(canvas, w * 0.84f, h * 0.69f, "A", 0xFF48945B.toInt())
    }

    private fun drawPill(canvas: Canvas, cx: Float, cy: Float, label: String, color: Int) {
        val rect = RectF(cx - 34f * density, cy - 13f * density, cx + 34f * density, cy + 13f * density)
        paint.color = color
        canvas.drawRoundRect(rect, 9f, 9f, paint)
        drawCentered(canvas, label, cx, cy + 4f * density, 10f, 0xFFFFFFFF.toInt())
    }

    private fun drawStick(canvas: Canvas, cx: Float, cy: Float, radius: Float, x: Float, y: Float, label: String) {
        paint.color = 0xFF2C3544.toInt()
        canvas.drawCircle(cx, cy, radius, paint)
        paint.color = 0xFF526174.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.FILL
        val knobR = radius * 0.43f
        paint.color = 0xFF62758D.toInt()
        canvas.drawCircle(cx + x * (radius - knobR), cy + y * (radius - knobR), knobR, paint)
        drawCentered(canvas, label, cx, cy + radius + 15f * density, 9f, 0xFFFFFFFF.toInt())
    }

    private fun drawDpad(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.color = 0xFF2C3544.toInt()
        canvas.drawCircle(cx, cy, r, paint)
        paint.color = 0xFF8290A2.toInt()
        val arm = r * 0.42f
        canvas.drawRoundRect(cx - arm * 0.45f, cy - r * 0.86f, cx + arm * 0.45f, cy + r * 0.86f, 7f, 7f, paint)
        canvas.drawRoundRect(cx - r * 0.86f, cy - arm * 0.45f, cx + r * 0.86f, cy + arm * 0.45f, 7f, 7f, paint)
    }

    private fun drawFaceButton(canvas: Canvas, cx: Float, cy: Float, label: String, color: Int) {
        paint.color = color
        canvas.drawCircle(cx, cy, 20f * density, paint)
        drawCentered(canvas, label, cx, cy + 5f * density, 14f, 0xFFFFFFFF.toInt())
    }

    private fun drawCentered(canvas: Canvas, text: String, x: Float, y: Float, sizeSp: Float, color: Int) {
        paint.color = color
        paint.textSize = sizeSp * density
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText(text, x, y, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)
                if (connectRect.contains(x, y)) { onConnect(); return true }
                if (settingsRect.contains(x, y)) { onSettings(); return true }
                val target = hitTest(x, y) ?: return true
                pointerTargets[id] = target
                updateTarget(id, target, x, y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val id = event.getPointerId(index)
                    pointerTargets[id]?.let { updateTarget(id, it, event.getX(index), event.getY(index)) }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                releaseTarget(id)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerTargets.keys.toList().forEach { releaseTarget(it) }
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun hitTest(x: Float, y: Float): Target? {
        val w = width.toFloat()
        val h = height.toFloat()
        val stickR = h * 0.15f * stickScale
        if (distance(x, y, w * 0.16f, h * 0.68f) <= stickR) return Target(TargetKind.STICK_LEFT)
        if (distance(x, y, w * 0.62f, h * 0.68f) <= stickR) return Target(TargetKind.STICK_RIGHT)
        if (distance(x, y, w * 0.385f, h * 0.68f) <= h * 0.13f) return Target(TargetKind.DPAD)

        val face = listOf(
            Triple(w * 0.84f, h * 0.45f, 3),
            Triple(w * 0.77f, h * 0.57f, 2),
            Triple(w * 0.91f, h * 0.57f, 1),
            Triple(w * 0.84f, h * 0.69f, 0)
        )
        face.firstOrNull { distance(x, y, it.first, it.second) < 27f * density }?.let {
            return Target(TargetKind.BUTTON, it.third)
        }

        val other = listOf(
            Triple(w * 0.10f, h * 0.19f, 6),
            Triple(w * 0.25f, h * 0.19f, 4),
            Triple(w * 0.72f, h * 0.19f, 5),
            Triple(w * 0.87f, h * 0.19f, 7),
            Triple(w * 0.45f, h * 0.19f, 8),
            Triple(w * 0.56f, h * 0.19f, 9)
        )
        other.firstOrNull { maxOf(kotlin.math.abs(x - it.first), kotlin.math.abs(y - it.second)) < 40f * density }?.let {
            return Target(TargetKind.BUTTON, it.third)
        }
        return null
    }

    private fun updateTarget(id: Int, target: Target, x: Float, y: Float) {
        when (target.kind) {
            TargetKind.STICK_LEFT -> {
                val v = stickVector(x, y, width * 0.16f, height * 0.68f, height * 0.125f * stickScale)
                leftX = v.first; leftY = v.second
                setPointerButtons(id, setOf(10))
            }
            TargetKind.STICK_RIGHT -> {
                val v = stickVector(x, y, width * 0.62f, height * 0.68f, height * 0.125f * stickScale)
                rightX = v.first; rightY = v.second
                setPointerButtons(id, setOf(11))
            }
            TargetKind.DPAD -> {
                val dx = x - width * 0.385f
                val dy = y - height * 0.68f
                val angle = atan2(dy, dx)
                val selected = mutableSetOf<Int>()
                if (hypot(dx, dy) > height * 0.035f) {
                    if (cos(angle) > 0.38f) selected.add(15)
                    if (cos(angle) < -0.38f) selected.add(14)
                    if (sin(angle) > 0.38f) selected.add(13)
                    if (sin(angle) < -0.38f) selected.add(12)
                }
                setPointerButtons(id, selected)
            }
            TargetKind.BUTTON -> setPointerButtons(id, setOf(target.button))
        }
        emitReport()
        invalidate()
    }

    private fun stickVector(x: Float, y: Float, cx: Float, cy: Float, radius: Float): Pair<Float, Float> {
        val dx = x - cx
        val dy = y - cy
        val rawLength = hypot(dx, dy)
        val length = rawLength.coerceAtMost(radius)
        if (rawLength < 0.001f) return Pair(0f, 0f)
        return Pair(
            (dx / rawLength * length / radius).coerceIn(-1f, 1f),
            (dy / rawLength * length / radius).coerceIn(-1f, 1f)
        )
    }

    private fun setPointerButtons(id: Int, next: Set<Int>) {
        pointerButtons[id]?.forEach { owners[it].remove(id) }
        pointerButtons[id] = next
        next.forEach { if (it in 0..15) owners[it].add(id) }
        for (i in buttons.indices) buttons[i] = owners[i].isNotEmpty()
    }

    private fun releaseTarget(id: Int) {
        val releasedTarget = pointerTargets.remove(id)
        pointerButtons.remove(id)?.forEach { owners[it].remove(id) }
        if (releasedTarget?.kind == TargetKind.STICK_LEFT) { leftX = 0f; leftY = 0f }
        if (releasedTarget?.kind == TargetKind.STICK_RIGHT) { rightX = 0f; rightY = 0f }
        // Reset stick axes when their corresponding click button is no longer held.
        if (owners[10].isEmpty()) { leftX = 0f; leftY = 0f }
        if (owners[11].isEmpty()) { rightX = 0f; rightY = 0f }
        for (i in buttons.indices) buttons[i] = owners[i].isNotEmpty()
        emitReport()
        invalidate()
    }

    private fun emitReport() {
        val report = ByteArray(6)
        for (i in buttons.indices) {
            if (buttons[i]) {
                val byteIndex = i / 8
                report[byteIndex] = (report[byteIndex].toInt() or (1 shl (i % 8))).toByte()
            }
        }
        report[2] = axisValue(leftX).toByte()
        report[3] = axisValue(-leftY).toByte()
        report[4] = axisValue(rightX).toByte()
        report[5] = axisValue(-rightY).toByte()
        onReport(report)
    }

    private fun axisValue(input: Float): Int {
        val magnitude = kotlin.math.abs(input)
        val adjusted = if (magnitude <= deadzone) 0f else
            ((magnitude - deadzone) / (1f - deadzone.coerceIn(0f, 0.95f))) * sensitivity
        val signed = if (input < 0f) -adjusted else adjusted
        return (127.5f + signed.coerceIn(-1f, 1f) * 127.5f).roundToInt().coerceIn(0, 255)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        hypot(x1 - x2, y1 - y2)
}
