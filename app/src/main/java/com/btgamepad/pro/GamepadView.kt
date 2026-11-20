package com.btgamepad.pro

import android.content.Context
import android.content.SharedPreferences
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

/**
 * Touchscreen gamepad with automatic HID input forwarding and an editable,
 * persistent control layout. Element positions are stored as normalized
 * screen coordinates, so they survive different landscape resolutions.
 */
class GamepadView(
    context: Context,
    private val preferences: SharedPreferences,
    private val onReport: (ByteArray) -> Unit,
    private val onConnect: () -> Unit,
    private val onSettings: () -> Unit
) : View(context) {

    var statusText: String = "Tekan HUBUNGKAN untuk menyiapkan Bluetooth HID."
        set(value) { field = value; invalidate() }

    var deadzone: Float = 0.08f
    var sensitivity: Float = 1.0f
    var stickScale: Float = 1.0f
        set(value) { field = value.coerceIn(0.5f, 1.5f); invalidate() }

    private data class LayoutItem(var x: Float, var y: Float, var scale: Float = 1f)
    private enum class TargetKind { STICK_LEFT, STICK_RIGHT, DPAD, BUTTON }
    private data class Target(val kind: TargetKind, val id: String)

    private val defaults = linkedMapOf(
        "L2" to LayoutItem(0.10f, 0.20f),
        "L1" to LayoutItem(0.25f, 0.20f),
        "SELECT" to LayoutItem(0.45f, 0.20f),
        "START" to LayoutItem(0.56f, 0.20f),
        "R1" to LayoutItem(0.72f, 0.20f),
        "R2" to LayoutItem(0.87f, 0.20f),
        "LEFT_STICK" to LayoutItem(0.16f, 0.68f),
        "DPAD" to LayoutItem(0.385f, 0.68f),
        "RIGHT_STICK" to LayoutItem(0.62f, 0.68f),
        "Y" to LayoutItem(0.84f, 0.45f),
        "X" to LayoutItem(0.77f, 0.57f),
        "B" to LayoutItem(0.91f, 0.57f),
        "A" to LayoutItem(0.84f, 0.69f)
    )
    private val items = linkedMapOf<String, LayoutItem>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttons = BooleanArray(16)
    private val owners = Array(16) { mutableSetOf<Int>() }
    private val pointerTargets = mutableMapOf<Int, Target>()
    private val pointerButtons = mutableMapOf<Int, Set<Int>>()
    private var leftX = 0f
    private var leftY = 0f
    private var rightX = 0f
    private var rightY = 0f

    private var autoInputEnabled = true
    private var editMode = false
    private var selectedControl = "LEFT_STICK"
    private var editPointerId: Int? = null

    private val connectRect = RectF()
    private val autoRect = RectF()
    private val editRect = RectF()
    private val settingsRect = RectF()
    private val density = resources.displayMetrics.density

    init {
        isFocusable = true
        loadLayout()
    }

    private fun loadLayout() {
        items.clear()
        defaults.forEach { (id, default) ->
            items[id] = LayoutItem(
                preferences.getFloat("${id}_x", default.x).coerceIn(0.04f, 0.96f),
                preferences.getFloat("${id}_y", default.y).coerceIn(0.12f, 0.92f),
                preferences.getFloat("${id}_scale", default.scale).coerceIn(0.5f, 1.6f)
            )
        }
    }

    private fun saveLayout() {
        val editor = preferences.edit()
        items.forEach { (id, item) ->
            editor.putFloat("${id}_x", item.x)
            editor.putFloat("${id}_y", item.y)
            editor.putFloat("${id}_scale", item.scale)
        }
        editor.apply()
    }

    fun resetLayout() {
        defaults.forEach { (id, item) ->
            items[id] = LayoutItem(item.x, item.y, item.scale)
        }
        selectedControl = "LEFT_STICK"
        saveLayout()
        invalidate()
    }

    fun selectedControlName(): String = selectedControl.replace("_", " ")
    fun selectedControlScale(): Float = items[selectedControl]?.scale ?: 1f

    fun setSelectedControlScale(value: Float) {
        items[selectedControl]?.scale = value.coerceIn(0.5f, 1.6f)
        saveLayout()
        invalidate()
    }

    fun setAutoInput(enabled: Boolean) {
        if (autoInputEnabled == enabled) return
        if (!enabled) {
            clearAllInput()
            emitReport(force = true)
        }
        autoInputEnabled = enabled
        invalidate()
    }

    fun isAutoInputEnabled(): Boolean = autoInputEnabled

    private fun setEditMode(enabled: Boolean) {
        editMode = enabled
        editPointerId = null
        pointerTargets.clear()
        clearAllInput()
        emitReport(force = true)
        if (!enabled) saveLayout()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val short = minOf(w, h)

        canvas.drawColor(0xFF10131A.toInt())
        paint.style = Paint.Style.FILL
        paint.color = 0xFF1A202B.toInt()
        canvas.drawRoundRect(18f, 14f, w - 18f, h - 14f, 22f, 22f, paint)

        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = 20f * density
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        canvas.drawText("BT GAMEPAD PRO", 24f * density, 34f * density, paint)
        paint.color = 0xFF9BA8B8.toInt()
        paint.textSize = 10f * density
        paint.typeface = android.graphics.Typeface.DEFAULT
        canvas.drawText(statusText.take(80), 24f * density, 55f * density, paint)

        val right = w - 12f * density
        settingsRect.set(right - 34f * density, 14f * density, right, 46f * density)
        editRect.set(settingsRect.left - 48f * density, 14f * density, settingsRect.left - 6f * density, 46f * density)
        autoRect.set(editRect.left - 56f * density, 14f * density, editRect.left - 6f * density, 46f * density)
        connectRect.set(autoRect.left - 88f * density, 14f * density, autoRect.left - 6f * density, 46f * density)

        drawToolbarButton(canvas, connectRect, "PAIR", 0xFF176F62.toInt())
        drawToolbarButton(
            canvas, autoRect,
            if (autoInputEnabled && !editMode) "AUTO ON" else "AUTO OFF",
            if (autoInputEnabled && !editMode) 0xFF176F62.toInt() else 0xFF6D4A35.toInt()
        )
        drawToolbarButton(canvas, editRect, if (editMode) "DONE" else "EDIT",
            if (editMode) 0xFF996B2D.toInt() else 0xFF303B4B.toInt())
        drawToolbarButton(canvas, settingsRect, "SET", 0xFF303B4B.toInt())

        items.forEach { (id, item) ->
            val cx = item.x * w
            val cy = item.y * h
            val selected = editMode && id == selectedControl
            when (id) {
                "LEFT_STICK" -> drawStick(canvas, cx, cy, short * 0.12f * item.scale * stickScale, leftX, leftY, "L3")
                "RIGHT_STICK" -> drawStick(canvas, cx, cy, short * 0.12f * item.scale * stickScale, rightX, rightY, "R3")
                "DPAD" -> drawDpad(canvas, cx, cy, short * 0.115f * item.scale)
                "A" -> drawFaceButton(canvas, cx, cy, "A", 0xFF48945B.toInt(), short * 0.047f * item.scale)
                "B" -> drawFaceButton(canvas, cx, cy, "B", 0xFFC94B55.toInt(), short * 0.047f * item.scale)
                "X" -> drawFaceButton(canvas, cx, cy, "X", 0xFF397EC3.toInt(), short * 0.047f * item.scale)
                "Y" -> drawFaceButton(canvas, cx, cy, "Y", 0xFFC49B3B.toInt(), short * 0.047f * item.scale)
                else -> drawPill(canvas, cx, cy, id, short * 0.095f * item.scale, short * 0.034f * item.scale)
            }
            if (selected) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.5f * density
                paint.color = 0xFF55D6BE.toInt()
                val radius = short * 0.14f * item.scale
                canvas.drawCircle(cx, cy, radius, paint)
                paint.style = Paint.Style.FILL
            }
        }

        if (editMode) {
            paint.color = 0xFF55D6BE.toInt()
            paint.textSize = 11f * density
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "EDIT LAYOUT: geser kontrol • pilih kontrol lalu ubah ukurannya di SET",
                w / 2f, h - 12f * density, paint
            )
        } else if (!autoInputEnabled) {
            paint.color = 0xFFFFC078.toInt()
            paint.textSize = 11f * density
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("AUTO INPUT PAUSED", w / 2f, h - 12f * density, paint)
        }
    }

    private fun drawToolbarButton(canvas: Canvas, rect: RectF, text: String, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = 9f * density
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText(text, rect.centerX(), rect.centerY() + 3.5f * density, paint)
    }

    private fun drawPill(canvas: Canvas, cx: Float, cy: Float, label: String, halfW: Float, halfH: Float) {
        val rect = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        paint.style = Paint.Style.FILL
        paint.color = 0xFF35465A.toInt()
        canvas.drawRoundRect(rect, 9f * density, 9f * density, paint)
        drawCentered(canvas, label, cx, cy + 4f * density, 10f, 0xFFFFFFFF.toInt())
    }

    private fun drawStick(canvas: Canvas, cx: Float, cy: Float, radius: Float, x: Float, y: Float, label: String) {
        paint.style = Paint.Style.FILL
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

    private fun drawDpad(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFF2C3544.toInt()
        canvas.drawCircle(cx, cy, radius, paint)
        paint.color = 0xFF8290A2.toInt()
        val arm = radius * 0.42f
        canvas.drawRoundRect(cx - arm * 0.45f, cy - radius * 0.86f,
            cx + arm * 0.45f, cy + radius * 0.86f, 7f, 7f, paint)
        canvas.drawRoundRect(cx - radius * 0.86f, cy - arm * 0.45f,
            cx + radius * 0.86f, cy + arm * 0.45f, 7f, 7f, paint)
    }

    private fun drawFaceButton(canvas: Canvas, cx: Float, cy: Float, label: String, color: Int, radius: Float) {
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(cx, cy, radius, paint)
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
                if (editRect.contains(x, y)) {
                    setEditMode(!editMode)
                    return true
                }
                if (autoRect.contains(x, y)) {
                    if (editMode) {
                        statusText = "Selesaikan EDIT terlebih dahulu."
                    } else {
                        setAutoInput(!autoInputEnabled)
                    }
                    return true
                }

                if (editMode) {
                    val target = hitTest(x, y)
                    if (target != null) {
                        selectedControl = target.id
                        editPointerId = id
                        invalidate()
                    }
                    return true
                }

                if (!autoInputEnabled) return true
                val target = hitTest(x, y) ?: return true
                pointerTargets[id] = target
                updateTarget(id, target, x, y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val id = event.getPointerId(index)
                    if (editMode && editPointerId == id) {
                        val item = items[selectedControl] ?: continue
                        item.x = (event.getX(index) / width.toFloat()).coerceIn(0.04f, 0.96f)
                        item.y = (event.getY(index) / height.toFloat()).coerceIn(0.12f, 0.90f)
                        invalidate()
                    } else {
                        pointerTargets[id]?.let { updateTarget(id, it, event.getX(index), event.getY(index)) }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (editMode && editPointerId == id) {
                    editPointerId = null
                    saveLayout()
                } else {
                    releaseTarget(id)
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (editMode) {
                    editPointerId = null
                    saveLayout()
                } else {
                    pointerTargets.keys.toList().forEach { releaseTarget(it) }
                }
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
        val short = minOf(w, h)
        val order = listOf(
            "LEFT_STICK", "RIGHT_STICK", "DPAD", "A", "B", "X", "Y",
            "L1", "L2", "R1", "R2", "SELECT", "START"
        )
        for (id in order) {
            val item = items[id] ?: continue
            val cx = item.x * w
            val cy = item.y * h
            val dx = x - cx
            val dy = y - cy
            val hit = when (id) {
                "LEFT_STICK", "RIGHT_STICK" -> short * 0.15f * item.scale * stickScale
                "DPAD" -> short * 0.14f * item.scale
                "A", "B", "X", "Y" -> short * 0.058f * item.scale
                else -> short * 0.105f * item.scale
            }
            if (hypot(dx, dy) <= hit) {
                return when (id) {
                    "LEFT_STICK" -> Target(TargetKind.STICK_LEFT, id)
                    "RIGHT_STICK" -> Target(TargetKind.STICK_RIGHT, id)
                    "DPAD" -> Target(TargetKind.DPAD, id)
                    else -> Target(TargetKind.BUTTON, id)
                }
            }
        }
        return null
    }

    private fun updateTarget(id: Int, target: Target, x: Float, y: Float) {
        when (target.kind) {
            TargetKind.STICK_LEFT -> {
                val center = items[target.id]!!
                val vector = stickVector(
                    x, y, center.x * width, center.y * height,
                    minOf(width, height) * 0.12f * center.scale * stickScale
                )
                leftX = vector.first
                leftY = vector.second
                setPointerButtons(id, setOf(10))
            }
            TargetKind.STICK_RIGHT -> {
                val center = items[target.id]!!
                val vector = stickVector(
                    x, y, center.x * width, center.y * height,
                    minOf(width, height) * 0.12f * center.scale * stickScale
                )
                rightX = vector.first
                rightY = vector.second
                setPointerButtons(id, setOf(11))
            }
            TargetKind.DPAD -> {
                val center = items[target.id]!!
                val dx = x - center.x * width
                val dy = y - center.y * height
                val angle = atan2(dy, dx)
                val selected = mutableSetOf<Int>()
                if (hypot(dx, dy) > minOf(width, height) * 0.035f) {
                    if (cos(angle) > 0.38f) selected.add(15)
                    if (cos(angle) < -0.38f) selected.add(14)
                    if (sin(angle) > 0.38f) selected.add(13)
                    if (sin(angle) < -0.38f) selected.add(12)
                }
                setPointerButtons(id, selected)
            }
            TargetKind.BUTTON -> {
                val index = when (target.id) {
                    "A" -> 0; "B" -> 1; "X" -> 2; "Y" -> 3
                    "L1" -> 4; "R1" -> 5; "L2" -> 6; "R2" -> 7
                    "SELECT" -> 8; "START" -> 9
                    else -> -1
                }
                setPointerButtons(id, if (index >= 0) setOf(index) else emptySet())
            }
        }
        emitReport()
        invalidate()
    }

    private fun stickVector(x: Float, y: Float, cx: Float, cy: Float, radius: Float): Pair<Float, Float> {
        val dx = x - cx
        val dy = y - cy
        val rawLength = hypot(dx, dy)
        if (rawLength < 0.001f) return Pair(0f, 0f)
        val length = rawLength.coerceAtMost(radius)
        return Pair(
            (dx / rawLength * length / radius).coerceIn(-1f, 1f),
            (dy / rawLength * length / radius).coerceIn(-1f, 1f)
        )
    }

    private fun setPointerButtons(id: Int, next: Set<Int>) {
        pointerButtons[id]?.forEach { owners[it].remove(id) }
        pointerButtons[id] = next
        next.forEach { if (it in 0..15) owners[it].add(id) }
        for (index in buttons.indices) buttons[index] = owners[index].isNotEmpty()
    }

    private fun releaseTarget(id: Int) {
        val released = pointerTargets.remove(id)
        pointerButtons.remove(id)?.forEach { owners[it].remove(id) }
        if (released?.kind == TargetKind.STICK_LEFT) { leftX = 0f; leftY = 0f }
        if (released?.kind == TargetKind.STICK_RIGHT) { rightX = 0f; rightY = 0f }
        if (owners[10].isEmpty()) { leftX = 0f; leftY = 0f }
        if (owners[11].isEmpty()) { rightX = 0f; rightY = 0f }
        for (index in buttons.indices) buttons[index] = owners[index].isNotEmpty()
        emitReport()
        invalidate()
    }

    private fun clearAllInput() {
        pointerTargets.clear()
        pointerButtons.clear()
        owners.forEach { it.clear() }
        buttons.fill(false)
        leftX = 0f
        leftY = 0f
        rightX = 0f
        rightY = 0f
    }

    private fun emitReport(force: Boolean = false) {
        if (!autoInputEnabled && !force) return
        val report = ByteArray(6)
        for (index in buttons.indices) {
            if (buttons[index]) {
                val byteIndex = index / 8
                report[byteIndex] = (report[byteIndex].toInt() or (1 shl (index % 8))).toByte()
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
        val safeDeadzone = deadzone.coerceIn(0f, 0.95f)
        val adjusted = if (magnitude <= safeDeadzone) 0f
            else ((magnitude - safeDeadzone) / (1f - safeDeadzone)) * sensitivity
        val signed = if (input < 0f) -adjusted else adjusted
        return (127.5f + signed.coerceIn(-1f, 1f) * 127.5f).roundToInt().coerceIn(0, 255)
    }
}
