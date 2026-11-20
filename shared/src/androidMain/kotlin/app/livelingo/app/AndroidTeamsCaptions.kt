package app.livelingo.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.channels.Channel
import kotlin.math.roundToInt

/** MediaProjection and ML Kit callbacks run on main. At most one cropped image is in OCR. */
@RequiresApi(34)
internal class AndroidTeamsCaptions(
    private val context: Context,
    private val projection: MediaProjection,
    private val hint: (String) -> Unit,
    private val fail: (String) -> Unit,
) {
    val captions = Channel<CapturedCaption>(Channel.UNLIMITED)
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var preview: Bitmap? = null
    private var dialog: AlertDialog? = null
    private var region: Rect? = null
    private var window = CaptionWindow()
    private var lastText = ""
    private var lastRead = -1000L
    private var lastCaptionMs = 0L
    private var closed = false
    private var visible = true
    private var queuedFrame: Image? = null
    private val readFrame = Runnable { readLatestFrame() }
    private var processing = false
    private var generation = 0
    private val settle = Runnable { flush() }
    private val maxWait = Runnable { flush() }
    private val emptyHint = Runnable { hint("尚未辨識到字幕。請保持 Teams 原文字幕可見，確認框選位置；若預覽全黑，畫面可能受保護。") }
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() = Unit // AndroidMicrophone owns the grant and its stop action.
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible && !closed) scheduleRead()
            if (!isVisible && !closed) {
                queuedFrame?.close()
                queuedFrame = null
                generation++
                flush()
                handler.removeCallbacks(emptyHint)
                hint("Teams 畫面已隱藏。回到會議或用分割畫面顯示字幕即可繼續。")
            }
        }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (!closed && width > 0 && height > 0) runCatching { resize(width, height) }.onFailure {
                fail("Teams 分享畫面尺寸無法更新，請重新開始。")
            }
        }
    }

    fun start(bounds: Rect) {
        projection.registerCallback(callback, handler)
        resize(bounds.width(), bounds.height())
        hint("先讓 Teams 顯示原文字幕，再回到這裡點「框選字幕範圍」。可使用分割畫面。")
    }

    private fun resize(w: Int, h: Int) {
        val scale = minOf(1f, 1680f / maxOf(w, h))
        val width = (w * scale).roundToInt().coerceAtLeast(1)
        val height = (h * scale).roundToInt().coerceAtLeast(1)
        if (reader?.width == width && reader?.height == height) return
        resetRegion()
        val old = reader
        val next = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader = next
        try {
            next.setOnImageAvailableListener({ source ->
                if (closed || source !== reader) return@setOnImageAvailableListener
                try {
                    val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                    queuedFrame?.close()
                    queuedFrame = image
                    scheduleRead()
                }
                catch (_: Exception) { fail("Teams 字幕畫面擷取中斷，已辨識的原文會保留。") }
            }, handler)
            val density = context.resources.configuration.densityDpi
            val existing = display
            if (existing == null) {
                display = projection.createVirtualDisplay("LiveLingo Teams captions", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, next.surface, null, handler)
            } else {
                existing.resize(width, height, density)
                existing.surface = next.surface
                hint("Teams 畫面大小已變更，請重新框選字幕範圍。")
            }
        } finally { old?.close() }
    }

    // Keep the final frame even if the caption changes only once during the throttle interval.
    private fun scheduleRead() {
        if (!visible || processing || queuedFrame == null || handler.hasCallbacks(readFrame)) return
        handler.postDelayed(readFrame, (lastRead + 650L - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }

    private fun readLatestFrame() {
        val frame = queuedFrame ?: return
        queuedFrame = null
        try {
            frame.use { if (!closed && visible) accept(it) }
        } catch (_: Exception) { fail("無法讀取 Teams 字幕畫面。請重新分享 Teams 視窗。") }
    }

    private fun accept(image: Image) {
        lastRead = SystemClock.elapsedRealtime()
        val area = region
        if (area == null && dialog?.isShowing == true) return
        val plane = image.planes.first()
        // Read only the selected RGBA rows. Some devices omit trailing padding on the last row.
        check(plane.pixelStride == 4)
        val crop = area ?: Rect(0, 0, image.width, image.height)
        val bitmap = Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
        val packed = java.nio.ByteBuffer.allocateDirect(bitmap.byteCount)
        val source = plane.buffer.duplicate()
        for (y in crop.top until crop.bottom) {
            val offset = y * plane.rowStride + crop.left * 4
            source.limit(source.capacity()).position(offset)
            source.limit(offset + crop.width() * 4)
            packed.put(source)
        }
        packed.flip()
        bitmap.copyPixelsFromBuffer(packed)
        if (area == null) {
            preview = bitmap
            return
        }
        recognize(bitmap)
    }

    private fun recognize(bitmap: Bitmap) {
        val token = generation
        processing = true
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { result ->
            processing = false
            bitmap.recycle()
            if (!closed) scheduleRead()
            if (closed || token != generation) return@addOnCompleteListener
            if (!result.isSuccessful) {
                fail("裝置上的字幕辨識無法執行，請停止後重新開始。")
                return@addOnCompleteListener
            }
            val value = result.result.text.trim().take(4096)
            if (value == lastText) return@addOnCompleteListener
            lastText = value
            handler.removeCallbacks(settle)
            val previousMs = lastCaptionMs
            lastCaptionMs = System.currentTimeMillis()
            window.update(value)?.let { captions.trySend(CapturedCaption(it, previousMs)) }
            if (value.isBlank()) handler.removeCallbacks(maxWait)
            if (value.isNotBlank()) {
                hint("正在讀取框選範圍的 Teams 原文字幕。")
                handler.removeCallbacks(emptyHint)
                handler.postDelayed(settle, 1400)
                if (!handler.hasCallbacks(maxWait)) handler.postDelayed(maxWait, 6_000)
            } else {
                handler.removeCallbacks(emptyHint)
                handler.postDelayed(emptyHint, 12_000)
            }
        }
    }

    private fun flush() {
        handler.removeCallbacks(settle)
        handler.removeCallbacks(maxWait)
        window.flush()?.let { captions.trySend(CapturedCaption(it, lastCaptionMs)) }
    }

    private fun resetRegion() {
        generation++
        handler.removeCallbacks(readFrame)
        queuedFrame?.close()
        queuedFrame = null
        flush()
        window = CaptionWindow()
        lastText = ""
        region = null
        preview = null
        dialog?.dismiss()
        dialog = null
        handler.removeCallbacks(emptyHint)
    }

    fun selectRegion(activity: Activity) {
        if (region != null) {
            resetRegion()
            hint("已暫停辨識。切到 Teams 顯示原文字幕，再點「框選字幕範圍」。")
            return
        }
        val frame = preview ?: run { hint("尚未取得 Teams 畫面。請讓 Teams 顯示原文字幕後再框選。"); return }
        val picker = CaptionRegionView(activity, frame)
        val pickerDialog = AlertDialog.Builder(activity).setTitle("拖曳框選 Teams 原文字幕")
            .setMessage("請確認預覽是 Teams，只框選發言文字。框內辨識出的文字會送至已選的翻譯服務。")
            .setView(picker).setNegativeButton("取消", null).setPositiveButton("開始讀取", null).create()
        dialog = pickerDialog
        pickerDialog.setOnDismissListener { if (dialog === pickerDialog) dialog = null }
        pickerDialog.show()
        val apply = pickerDialog.getButton(AlertDialog.BUTTON_POSITIVE)
        apply.isEnabled = false
        picker.onSelection = { apply.isEnabled = it != null }
        apply.setOnClickListener {
            val chosen = picker.selection ?: return@setOnClickListener
            if (closed || reader?.width != frame.width || reader?.height != frame.height) return@setOnClickListener
            region = Rect(chosen)
            preview = null
            lastRead = -1000
            hint("已框選。請保持 Teams 字幕可見，可用分割畫面查看譯文。")
            handler.postDelayed(emptyHint, 12_000)
            pickerDialog.dismiss()
            val sample = Bitmap.createBitmap(frame, chosen.left, chosen.top, chosen.width(), chosen.height())
            recognize(sample)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        resetRegion()
        captions.close()
        handler.removeCallbacksAndMessages(null)
        projection.unregisterCallback(callback)
        display?.release()
        reader?.close()
        recognizer.close()
    }
}

private class CaptionRegionView(context: Context, private val frame: Bitmap) : View(context) {
    var selection: Rect? = null
        private set
    var onSelection: (Rect?) -> Unit = {}
    private val target = RectF()
    private val box = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var x0 = 0f
    private var y0 = 0f

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val maxHeight = (resources.displayMetrics.heightPixels * .46f).toInt()
        setMeasuredDimension(width, resolveSize(minOf(maxHeight, (width.toFloat() * frame.height / frame.width).toInt()), heightSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val scale = minOf(width.toFloat() / frame.width, height.toFloat() / frame.height)
        val w = frame.width * scale
        val h = frame.height * scale
        target.set((width-w)/2, (height-h)/2, (width+w)/2, (height+h)/2)
        canvas.drawColor(Color.DKGRAY)
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        canvas.drawBitmap(frame, null, target, paint)
        paint.color = Color.CYAN
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3 * resources.displayMetrics.density
        canvas.drawRect(box, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (target.isEmpty) return false
        val x = event.x.coerceIn(target.left, target.right)
        val y = event.y.coerceIn(target.top, target.bottom)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!target.contains(event.x, event.y)) return false
                x0 = x; y0 = y
                selection = null; onSelection(null)
                box.setEmpty()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                box.set(minOf(x0,x), minOf(y0,y), maxOf(x0,x), maxOf(y0,y))
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    fun px(value: Float) = ((value-target.left)*frame.width/target.width()).roundToInt().coerceIn(0, frame.width)
                    fun py(value: Float) = ((value-target.top)*frame.height/target.height()).roundToInt().coerceIn(0, frame.height)
                    selection = Rect(px(box.left), py(box.top), px(box.right), py(box.bottom)).takeIf { it.width() >= 20 && it.height() >= 12 }
                    onSelection(selection)
                    performClick()
                }
            }
        }
        invalidate()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
