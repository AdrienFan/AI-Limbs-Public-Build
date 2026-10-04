package com.ai.limbs.plugins.artstudio

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.fonts.FontFamily
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import org.json.JSONObject

/** A real in-canvas IME surface. Only the focused text rectangle consumes input. UI process only. */
internal class StudioCanvasTextInput(context: Context, val canvas: View) : FrameLayout(context) {
    private class FaceSpan(private val face: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(paint: android.text.TextPaint) { paint.typeface = face }
        override fun updateMeasureState(paint: android.text.TextPaint) { paint.typeface = face }
    }
    private val faces = linkedMapOf<String, Typeface>()
    private fun face(id: String): Typeface = faces[id] ?: Typeface.CustomFallbackBuilder(
        FontFamily.Builder(ArtText.face(id).font).build()).build().also { faces[id] = it }
    private var token = ""
    private var changing = false
    private var fields: JSONObject? = null
    private var styleSignature = ""
    private val viewport = Matrix()
    var onChange: (String, String, Int, Int) -> Unit = { _, _, _, _ -> }
    var onDone: (String) -> Unit = {}
    var onError: (Exception) -> Unit = {}
    private val editor = object : EditText(context) {
        override fun onSelectionChanged(start: Int, end: Int) {
            super.onSelectionChanged(start, end)
            if (!changing && token.isNotEmpty()) onChange(token, text.toString(), start, end)
        }
    }.apply {
        visibility = View.GONE
        setPadding(0, 0, 0, 0)
        setBackgroundColor(Color.TRANSPARENT)
        includeFontPadding = false
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        filters = arrayOf(InputFilter.LengthFilter(4096), InputFilter { source, start, end, dest, dstart, dend ->
            val next = dest.substring(0, dstart) + source.subSequence(start, end) + dest.substring(dend)
            if (next.count { it == '\n' } >= 128) "" else null
        })
        isSingleLine = false
        maxLines = 128
        setHorizontallyScrolling(false)
        isFocusableInTouchMode = true
        textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.rgb(185, 149, 255)); setSize(2, 1)
        }
        contentDescription = "画布文字输入"
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(value: Editable?) {
                if (!changing && token.isNotEmpty()) onChange(token, value.toString(), selectionStart, selectionEnd)
            }
        })
        setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { onDone(token); true } else false
        }
    }
    init {
        clipChildren = true
        clipToPadding = true
        addView(canvas, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(editor, LayoutParams(1, LayoutParams.WRAP_CONTENT))
    }

    fun viewport(matrix: Matrix) {
        if (viewport != matrix) { viewport.set(matrix); position() }
    }
    private fun position() {
        fields?.let { source ->
            val transform = ArtShapes.matrix(source.getJSONArray("inputMatrix"))
            transform.postConcat(viewport)
            // Native View transformation retains zoom, rotation, mirror and ancestor transforms.
            editor.animationMatrix = transform
        }
    }
    fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(editor.windowToken, 0)
    }
    fun focusInput() {
        if (token.isEmpty() || !editor.isEnabled) return
        editor.requestFocus()
        editor.post {
            if (token.isNotEmpty() && editor.isAttachedToWindow && editor.hasFocus())
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }
    fun bind(source: JSONObject?, enabled: Boolean) {
        if (source == null) {
            if (token.isNotEmpty()) hideKeyboard()
            changing = true
            token = ""; fields = null; styleSignature = ""
            editor.clearFocus(); editor.visibility = View.GONE
            changing = false
            return
        }
        fields = source
        val fresh = token != source.getString("inputSession")
        changing = true
        try {
            token = source.getString("inputSession")
            editor.visibility = View.VISIBLE
            editor.isEnabled = enabled
            if (!enabled) hideKeyboard()
            val content = source.getString("content")
            // Never replace a live Editable for an echo: Android owns the composing spans.
            if (fresh || editor.text.toString() != content) {
                editor.setText(content)
                editor.setSelection(source.optInt("selectionStart", content.length).coerceIn(0, content.length),
                    source.optInt("selectionEnd", content.length).coerceIn(0, content.length))
            }
            val signature = StudioTextInputSpec.defaults(source).toString() + source.optJSONArray("spans")
            if (fresh || signature != styleSignature || editor.text.getSpans(0, editor.length(), FaceSpan::class.java).size != source.optJSONArray("spans")?.length()) {
                editor.typeface = face(source.getString("fontId"))
                editor.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, source.getDouble("fontSize").toFloat())
                editor.setTextColor(Color.parseColor(source.getString("color")))
                editor.setLineSpacing(0f, source.getDouble("lineSpacing").toFloat())
                editor.letterSpacing = (source.optDouble("letterSpacing", 0.0) / source.getDouble("fontSize")).toFloat()
                editor.fontFeatureSettings = source.optString("fontFeatures").split(',').filter { it.isNotBlank() }
                    .joinToString(",") { val p = it.trim().split('='); "'${p[0]}' ${p[1]}" }
                editor.textDirection = when (source.optString("direction", "auto")) {
                    "rtl" -> View.TEXT_DIRECTION_RTL; "ltr" -> View.TEXT_DIRECTION_LTR
                    else -> View.TEXT_DIRECTION_FIRST_STRONG
                }
                editor.textAlignment = when (source.optString("align", "left")) {
                    "center" -> View.TEXT_ALIGNMENT_CENTER; "right" -> View.TEXT_ALIGNMENT_TEXT_END
                    "end" -> View.TEXT_ALIGNMENT_VIEW_END; "start" -> View.TEXT_ALIGNMENT_VIEW_START
                    else -> View.TEXT_ALIGNMENT_TEXT_START
                }
                editor.paintFlags = editor.paintFlags and (android.graphics.Paint.UNDERLINE_TEXT_FLAG or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG).inv()
                if (source.optBoolean("underline")) editor.paintFlags = editor.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                if (source.optBoolean("strike")) editor.paintFlags = editor.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                val editable = editor.text
                // Remove only our style spans; keep IME composing, selection and suggestion spans.
                for (kind in listOf(FaceSpan::class.java, AbsoluteSizeSpan::class.java, ForegroundColorSpan::class.java,
                    UnderlineSpan::class.java, StrikethroughSpan::class.java)) {
                    editable.getSpans(0, editable.length, kind).forEach { editable.removeSpan(it) }
                }
                source.optJSONArray("spans")?.let { spans ->
                    for (n in 0 until spans.length()) {
                        val span = spans.getJSONObject(n); val start = span.getInt("start"); val end = span.getInt("end")
                        require(start in 0..editable.length && end in start..editable.length)
                        fun apply(value: Any) { editable.setSpan(value, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                        apply(FaceSpan(face(span.getString("fontId"))))
                        apply(AbsoluteSizeSpan(span.getDouble("fontSize").toInt()))
                        apply(ForegroundColorSpan(Color.parseColor(span.getString("color"))))
                        if (span.optBoolean("underline")) apply(UnderlineSpan())
                        if (span.optBoolean("strike")) apply(StrikethroughSpan())
                    }
                }
                styleSignature = signature
            }
            val width = source.getInt("boxWidth")
            if (editor.layoutParams.width != width) editor.layoutParams = LayoutParams(width, LayoutParams.WRAP_CONTENT)
            position()
        } catch (error: Exception) {
            editor.visibility = View.GONE; editor.clearFocus(); hideKeyboard(); onError(error)
        } finally { changing = false }
        if (fresh && editor.visibility == View.VISIBLE) focusInput()
    }

    override fun onDetachedFromWindow() { hideKeyboard(); super.onDetachedFromWindow() }
}
