package com.BalancedLight.WindyWeather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.TextView

class InfoTextView(context: Context, _width: Int, _height: Int) : TextView(context) {
    private val ClearColor = 0
    private var mCanvas: Canvas? = null
    private var mHeight: Int
    private var mTextColor: Int
    private var mTextFont: Typeface? = null
    private var mTextGravity = 17
    private var mTextSize = 35.0f
    private var mTxtBmp: Bitmap? = null
    private var mWidth: Int

    enum class eFontStyle {
        FONT_STYLE_CLOCLOPIA,
        FONT_STYLE_DROIDSANS,
        FONT_STYLE_DROIDSANS_BOLD,
        FONT_STYLE_DROIDSANS_ARABIC,
        FONT_STYLE_DROIDSANS_SUBSET,
        FONT_STYLE_DROIDSANS_HEBREW,
        FONT_STYLE_DROIDSANS_MONO,
        FONT_STYLE_DROIDSANS_FALLBACK,
        FONT_STYLE_DROIDSANS_THAI,
        FONT_STYLE_DROIDSERIF_BOLD,
        FONT_STYLE_DROIDSERIF_BOLD_ITALIC,
        FONT_STYLE_DROIDSERIF_ITALIC,
        FONT_STYLE_DROIDSERIF_REGULAR
    }

    fun setTextFont(style: eFontStyle) {
        var family = "sans-serif"
        var typefaceStyle: Int = Typeface.NORMAL
        when (style) {
            eFontStyle.FONT_STYLE_CLOCLOPIA -> family = "sans-serif-light"
            eFontStyle.FONT_STYLE_DROIDSANS -> family = "sans-serif"
            eFontStyle.FONT_STYLE_DROIDSANS_BOLD -> {
                family = "sans-serif"
                typefaceStyle = Typeface.BOLD
            }

            eFontStyle.FONT_STYLE_DROIDSANS_ARABIC -> family = "sans-serif"
            eFontStyle.FONT_STYLE_DROIDSANS_SUBSET -> family = "sans-serif"
            eFontStyle.FONT_STYLE_DROIDSANS_HEBREW -> family = "sans-serif"
            eFontStyle.FONT_STYLE_DROIDSANS_MONO -> family = "monospace"
            eFontStyle.FONT_STYLE_DROIDSANS_FALLBACK -> family = "sans-serif"
            eFontStyle.FONT_STYLE_DROIDSANS_THAI -> family = "sans-serif"
            eFontStyle.FONT_STYLE_DROIDSERIF_BOLD -> {
                family = "serif"
                typefaceStyle = Typeface.BOLD
            }

            eFontStyle.FONT_STYLE_DROIDSERIF_BOLD_ITALIC -> {
                family = "serif"
                typefaceStyle = Typeface.BOLD_ITALIC
            }

            eFontStyle.FONT_STYLE_DROIDSERIF_ITALIC -> {
                family = "serif"
                typefaceStyle = Typeface.ITALIC
            }

            eFontStyle.FONT_STYLE_DROIDSERIF_REGULAR -> family = "serif"
        }
        this.mTextFont = Typeface.create(family, typefaceStyle)
    }

    init {
        this.mWidth = -1
        this.mHeight = -1
        this.mTextColor = -16777216
        this.mWidth = _width
        this.mHeight = _height
        this.mTxtBmp = Bitmap.createBitmap(_width, _height, Bitmap.Config.ARGB_8888)
        this.mTxtBmp?.eraseColor(0)
        this.mCanvas = Canvas(this.mTxtBmp ?: throw IllegalStateException("Text bitmap unavailable"))
    }

    fun resetCanavsColor(color: Int) {
        this.mTxtBmp?.eraseColor(color)
        this.mCanvas?.setBitmap(this.mTxtBmp)
    }

    override fun setTextSize(_size: Float) {
        this.mTextSize = _size
    }

    override fun setTextColor(color: Int) {
        this.mTextColor = color
    }

    fun setTextGravity(_gravity: Int) {
        this.mTextGravity = _gravity
    }

    fun GetBitmapWithText(_width: Int, _height: Int, contactNameText: String?): Bitmap? {
        resetCanavsColor(this.ClearColor)
        setTextForView(contactNameText)
        measure(
            View.MeasureSpec.makeMeasureSpec(this.mWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(this.mHeight, View.MeasureSpec.EXACTLY)
        )
        val w: Int = getMeasuredWidth()
        val h: Int = getMeasuredHeight()
        layout(0, 0, w, h)
        draw(GetCanvas() ?: return this.mTxtBmp)
        return this.mTxtBmp
    }

    fun setTextForView(text: CharSequence?) {
        super.setMinimumWidth(this.mWidth)
        super.setMinimumHeight(this.mHeight)
        super.setHeight(this.mHeight)
        super.setWidth(this.mWidth)
        super.setHorizontallyScrolling(false)
        super.setSingleLine(true)
        super.setMaxLines(1)
        super.setTextColor(this.mTextColor)
        super.setTextSize(TypedValue.COMPLEX_UNIT_SP, this.mTextSize)
        super.setTypeface(this.mTextFont)
        super.setText(text, TextView.BufferType.SPANNABLE)
        super.setIncludeFontPadding(false)
        super.setGravity(this.mTextGravity)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(this.mCanvas ?: canvas)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w: Int = getSuggestedMinimumWidth()
        val h: Int = getSuggestedMinimumHeight()
        setMeasuredDimension(w, h)
    }

    fun GetCanvas(): Canvas? {
        return this.mCanvas
    }
}




