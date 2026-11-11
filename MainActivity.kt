package com.mitv.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import kotlin.math.abs
import kotlin.math.max

/**
 * PALETA - cambia estos colores y todo el launcher se recolorea.
 * Los iconos de las apps se convierten a un duotono entre INK (sombras) y PEARL (luces).
 */
object Pal {
    val INK: Int = Color.rgb(21, 20, 32)       // negro tinta, ligeramente violeta
    val PEARL: Int = Color.rgb(246, 243, 252)  // blanco perla
    val ICE: Int = Color.rgb(214, 205, 255)    // lila hielo (aro de enfoque)
}

/**
 * Cristal esmerilado estilo iOS: dibuja el fondo YA DESENFOCADO (fondo_blur.jpg)
 * recortado a la forma del panel, + tinte ahumado + brillo especular + borde luminoso.
 * Funciona en cualquier Android (no depende de RenderEffect / Android 12).
 */
class GlassDrawable(
    private val host: View,
    private val blur: Bitmap,
    private val screenW: Int,
    private val screenH: Int,
    private val radius: Float,
    private val rimWidth: Float
) : Drawable() {

    private val loc = IntArray(2)
    private val rect = RectF()
    private val m = Matrix()
    private val blurShader = BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        blurPaint.isFilterBitmap = true
        rimPaint.style = Paint.Style.STROKE
        rimPaint.strokeWidth = rimWidth
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        tintPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.argb(112, 18, 17, 28), Color.argb(146, 10, 10, 18),
            Shader.TileMode.CLAMP
        )
        sheenPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.55f,
            Color.argb(80, 255, 255, 255), Color.argb(0, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
        rimPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                Color.argb(235, 255, 255, 255),
                Color.argb(40, 255, 255, 255),
                Color.argb(120, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        host.getLocationInWindow(loc)

        // misma logica que CENTER_CROP del fondo
        val scale = max(screenW / blur.width.toFloat(), screenH / blur.height.toFloat())
        val ox = (screenW - blur.width * scale) / 2f
        val oy = (screenH - blur.height * scale) / 2f
        m.reset()
        m.postScale(scale, scale)
        m.postTranslate(ox - loc[0], oy - loc[1])
        blurShader.setLocalMatrix(m)
        blurPaint.shader = blurShader

        val inset = rimWidth / 2f
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        canvas.drawRoundRect(rect, radius, radius, blurPaint)
        canvas.drawRoundRect(rect, radius, radius, tintPaint)
        canvas.drawRoundRect(rect, radius, radius, sheenPaint)
        canvas.drawRoundRect(rect, radius, radius, rimPaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Icono de app: tile redondeado (squircle) con brillo de cristal arriba,
 * borde luminoso y un aro lila hielo cuando esta enfocado.
 */
class IconView(context: Context, private val bmp: Bitmap) : View(context) {

    private val tile = RectF()
    private var r = 0f
    private var margin = 0f
    private val sm = Matrix()
    private val bmpShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var anim: ValueAnimator? = null
    private var level = 0f

    init {
        tilePaint.isFilterBitmap = true
        rimPaint.style = Paint.Style.STROKE
        ringPaint.style = Paint.Style.STROKE
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    tile.left.toInt(), tile.top.toInt(),
                    tile.right.toInt(), tile.bottom.toInt(), r
                )
                outline.alpha = 1f
            }
        }
    }

    fun setFocusedLook(on: Boolean) {
        anim?.cancel()
        val a = ValueAnimator.ofFloat(level, if (on) 1f else 0f)
        a.duration = 200
        a.addUpdateListener {
            level = it.animatedValue as Float
            invalidate()
        }
        a.start()
        anim = a
        translationZ = if (on) 26f else 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        margin = w * 0.045f
        tile.set(margin, margin, w - margin, h - margin)
        r = tile.width() * 0.235f

        sm.reset()
        sm.postScale(tile.width() / bmp.width, tile.height() / bmp.height)
        sm.postTranslate(tile.left, tile.top)
        bmpShader.setLocalMatrix(sm)
        tilePaint.shader = bmpShader

        sheenPaint.shader = LinearGradient(
            0f, tile.top, 0f, tile.top + tile.height() * 0.6f,
            Color.argb(95, 255, 255, 255), Color.argb(0, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
        rimPaint.strokeWidth = max(2f, w * 0.018f)
        rimPaint.shader = LinearGradient(
            tile.left, tile.top, tile.right, tile.bottom,
            intArrayOf(
                Color.argb(235, 255, 255, 255),
                Color.argb(45, 255, 255, 255),
                Color.argb(120, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        ringPaint.strokeWidth = w * 0.03f
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(tile, r, r, tilePaint)
        canvas.drawRoundRect(tile, r, r, sheenPaint)
        canvas.drawRoundRect(tile, r, r, rimPaint)
        if (level > 0.01f) {
            ringPaint.color = Color.argb((level * 255).toInt(), Color.red(Pal.ICE), Color.green(Pal.ICE), Color.blue(Pal.ICE))
            val h = ringPaint.strokeWidth / 2f
            canvas.drawRoundRect(h, h, width - h, height - h, r + margin, r + margin, ringPaint)
        }
    }
}

class MainActivity : Activity() {

    data class AppItem(
        val pkg: String,
        val label: String,
        val ri: ResolveInfo,
        val rank: Int
    )

    // Apps que van primero (en este orden). El resto va en orden alfabetico.
    private val order = listOf(
        "netflix", "youtube", "disney", "primevideo", "avod", "hbo", "crunchyroll",
        "spotify", "twitch", "android.vending", "minecraft", "xbox", "fortnite",
        "hdmi", "settings"
    )

    private var sw = 0
    private var sh = 0
    private var panelH = 0
    private var tileSize = 0
    private lateinit var video: VideoView
    private lateinit var cover: ImageView
    private lateinit var row: LinearLayout
    private lateinit var scroll: HorizontalScrollView
    private lateinit var bold: Typeface
    private lateinit var blurBmp: Bitmap
    private var signature = ""
    private val popStickers = listOf("s_kitty", "s_miffy", "s_blackstar", "s_headphones", "s_p_hearts", "s_pucca", "s_ghost", "s_camera")

    private val prefs by lazy { getSharedPreferences("mihome", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()
        val dm = resources.displayMetrics
        sw = dm.widthPixels
        sh = dm.heightPixels
        panelH = (sh * 0.34f).toInt()
        tileSize = (panelH * 0.46f).toInt()
        bold = resources.getFont(R.font.poppins_bold)
        blurBmp = BitmapFactory.decodeResource(resources, R.drawable.fondo_blur)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.WHITE)
        root.clipChildren = false

        video = VideoView(this)
        root.addView(
            video,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        cover = ImageView(this)
        cover.setImageResource(R.drawable.fondo_img)
        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        root.addView(
            cover,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setupVideo()

        val panelLp = FrameLayout.LayoutParams(
            (sw * 0.94f).toInt(),
            panelH,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        panelLp.bottomMargin = (sh * 0.025f).toInt()
        root.addView(buildPanel(), panelLp)

        addTopBar(root)
        addStickers(root)
        setContentView(root)
        rebuildRow()
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun setupVideo() {
        video.setVideoURI(Uri.parse("android.resource://" + packageName + "/" + R.raw.fondo_video))
        video.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    cover.animate().alpha(0f).setDuration(400).start()
                    true
                } else {
                    false
                }
            }
        }
        video.setOnErrorListener { _, _, _ -> true }
        video.start()
    }

    /** Cristal esmerilado para cualquier vista (panel, pastillas...). */
    private fun glass(host: View, radius: Float): GlassDrawable {
        return GlassDrawable(host, blurBmp, sw, sh, radius, max(2f, sw * 0.0014f))
    }

    private fun softText(tv: TextView) {
        tv.setShadowLayer(sh * 0.004f, 0f, sh * 0.002f, Color.argb(140, 0, 0, 0))
    }

    private fun addTopBar(root: FrameLayout) {
        val pad = (sh * 0.012f).toInt()
        val iconSize = (sh * 0.07f).toInt()

        // Reloj arriba a la derecha
        val clockPill = LinearLayout(this)
        clockPill.orientation = LinearLayout.HORIZONTAL
        clockPill.gravity = Gravity.CENTER_VERTICAL
        clockPill.background = glass(clockPill, sh * 0.06f)
        clockPill.setPadding((sh * 0.03f).toInt(), pad, (sh * 0.035f).toInt(), pad)

        val star = ImageView(this)
        star.setImageResource(R.drawable.s_p_star)
        clockPill.addView(star, LinearLayout.LayoutParams(iconSize, iconSize))

        val clock = TextClock(this)
        clock.format12Hour = "h:mm"
        clock.format24Hour = "H:mm"
        clock.typeface = bold
        clock.includeFontPadding = false
        clock.setTextColor(Pal.PEARL)
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, sh * 0.055f)
        softText(clock)
        val clockLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        clockLp.leftMargin = (sh * 0.015f).toInt()
        clockPill.addView(clock, clockLp)

        val date = TextClock(this)
        date.format12Hour = "EEE d MMM"
        date.format24Hour = "EEE d MMM"
        date.typeface = bold
        date.includeFontPadding = false
        date.setTextColor(Color.rgb(207, 203, 224))
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, sh * 0.026f)
        softText(date)
        val dateLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dateLp.leftMargin = (sh * 0.02f).toInt()
        clockPill.addView(date, dateLp)

        val cLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        )
        cLp.topMargin = (sh * 0.035f).toInt()
        cLp.rightMargin = (sw * 0.03f).toInt()
        root.addView(clockPill, cLp)

        // Marca arriba a la izquierda
        val brand = LinearLayout(this)
        brand.orientation = LinearLayout.HORIZONTAL
        brand.gravity = Gravity.CENTER_VERTICAL
        brand.background = glass(brand, sh * 0.06f)
        brand.setPadding((sh * 0.02f).toInt(), pad, (sh * 0.035f).toInt(), pad)
        val kitty = ImageView(this)
        kitty.setImageResource(R.drawable.s_kitty)
        brand.addView(kitty, LinearLayout.LayoutParams(iconSize, iconSize))
        val name = TextView(this)
        name.text = "mi home"
        name.typeface = bold
        name.setTextColor(Pal.PEARL)
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sh * 0.034f)
        softText(name)
        val nLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        nLp.leftMargin = (sh * 0.015f).toInt()
        brand.addView(name, nLp)
        val bLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        )
        bLp.topMargin = (sh * 0.035f).toInt()
        bLp.leftMargin = (sw * 0.03f).toInt()
        root.addView(brand, bLp)
    }

    private fun buildPanel(): LinearLayout {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.clipChildren = false
        panel.clipToPadding = false
        panel.background = glass(panel, panelH * 0.22f)
        val padX = (sw * 0.022f).toInt()
        panel.setPadding(padX, (panelH * 0.03f).toInt(), padX, (panelH * 0.03f).toInt())

        scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.clipChildren = false
        scroll.clipToPadding = false

        row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.clipChildren = false
        row.setPadding(
            (sw * 0.012f).toInt(), (panelH * 0.22f).toInt(),
            (sw * 0.02f).toInt(), (panelH * 0.06f).toInt()
        )
        scroll.addView(
            row,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return panel
    }

    private fun addStickers(root: FrameLayout) {
        val names = listOf("s_bow_gray", "s_sparkles", "s_p_star", "s_camera", "s_cherry")
        val xs = listOf(0.34f, 0.50f, 0.64f, 0.78f, 0.92f)
        val ss = (panelH * 0.30f).toInt()
        val top = sh - (sh * 0.025f).toInt() - panelH - (ss * 0.70f).toInt()
        for (i in names.indices) {
            val id = resources.getIdentifier(names[i], "drawable", packageName)
            if (id == 0) continue
            val iv = ImageView(this)
            iv.setImageResource(id)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            val lp = FrameLayout.LayoutParams(ss, ss)
            lp.leftMargin = (sw * xs[i]).toInt() - ss / 2
            lp.topMargin = top
            root.addView(iv, lp)

            val bob = ObjectAnimator.ofFloat(iv, View.TRANSLATION_Y, -ss * 0.06f, ss * 0.06f)
            bob.duration = 1800L + i * 250L
            bob.repeatCount = ValueAnimator.INFINITE
            bob.repeatMode = ValueAnimator.REVERSE
            bob.interpolator = AccelerateDecelerateInterpolator()
            bob.start()

            val tilt = ObjectAnimator.ofFloat(iv, View.ROTATION, -6f, 6f)
            tilt.duration = 2400L + i * 200L
            tilt.repeatCount = ValueAnimator.INFINITE
            tilt.repeatMode = ValueAnimator.REVERSE
            tilt.interpolator = AccelerateDecelerateInterpolator()
            tilt.start()
        }
    }

    // ---------------------------------------------------------------- apps

    private fun loadApps(): List<AppItem> {
        val pm = packageManager
        val hidden: Set<String> = prefs.getStringSet("hidden", emptySet()) ?: emptySet()
        val seen = HashSet<String>()
        val all = ArrayList<AppItem>()
        val cats = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        for (cat in cats) {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(cat)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val p = ri.activityInfo.packageName
                if (p == packageName || hidden.contains(p) || !seen.add(p)) continue
                val lower = p.lowercase()
                val idx = order.indexOfFirst { lower.contains(it) }
                all.add(AppItem(p, ri.loadLabel(pm).toString(), ri, if (idx < 0) 999 else idx))
            }
        }
        all.sortWith(compareBy<AppItem>({ it.rank }, { it.label.lowercase() }))
        return all
    }

    // ------------------------------------------------- iconos recoloreados

    /** Dibuja el icono normal de la app a pantalla completa (sin la mascara del sistema). */
    private fun renderIcon(d: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (d is AdaptiveIconDrawable) {
            // las capas miden 108dp y solo se ven 72dp: 25% extra por lado
            val pad = (size * 0.25f).toInt()
            val bg = d.background
            val fg = d.foreground
            if (bg != null) {
                bg.setBounds(-pad, -pad, size + pad, size + pad)
                bg.draw(c)
            }
            if (fg != null) {
                fg.setBounds(-pad, -pad, size + pad, size + pad)
                fg.draw(c)
            }
        } else {
            d.setBounds(0, 0, size, size)
            d.draw(c)
        }
        return bmp
    }

    /**
     * Duotono INK -> PEARL con auto-contraste: cada icono se estira para usar todo el rango
     * (fondo y simbolo quedan uno tinta y otro perla), asi NUNCA se pierde contra el cristal
     * y los logos de color (rojo sobre negro, blanco sobre morado...) siguen leyendose.
     */
    private fun duotone(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val tv = FloatArray(px.size)
        val hist = IntArray(256)
        var cnt = 0
        for (i in px.indices) {
            val p = px[i]
            val a = Color.alpha(p)
            if (a == 0) continue
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            val l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            val v = max(r, max(g, b)) / 255f
            val t = 0.65f * l + 0.35f * v
            tv[i] = t
            if (a > 40) {
                hist[(t * 255f).toInt().coerceIn(0, 255)]++
                cnt++
            }
        }
        // percentiles 1% y 99% (ignora pixeles sueltos)
        fun pct(q: Float): Float {
            val target = (cnt * q).toInt()
            var acc = 0
            for (i in 0..255) {
                acc += hist[i]
                if (acc >= target) return i / 255f
            }
            return 1f
        }
        var lo = pct(0.01f)
        var hi = pct(0.99f)
        val span = max(hi - lo, 0.25f)
        val mid = (hi + lo) / 2f
        lo = mid - span / 2f

        val ir = Color.red(Pal.INK)
        val ig = Color.green(Pal.INK)
        val ib = Color.blue(Pal.INK)
        val pr = Color.red(Pal.PEARL)
        val pg = Color.green(Pal.PEARL)
        val pb = Color.blue(Pal.PEARL)
        for (i in px.indices) {
            val a = Color.alpha(px[i])
            if (a == 0) continue
            var t = ((tv[i] - lo) / span).coerceIn(0f, 1f)
            val sm = t * t * (3f - 2f * t)
            t = 0.45f * t + 0.55f * sm
            px[i] = Color.argb(
                a,
                (ir + (pr - ir) * t).toInt(),
                (ig + (pg - ig) * t).toInt(),
                (ib + (pb - ib) * t).toInt()
            )
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun buildIcon(a: AppItem, size: Int): Bitmap {
        val d: Drawable? = try {
            a.ri.loadIcon(packageManager)
        } catch (e: Exception) {
            null
        }
        val raw: Bitmap = if (d != null) {
            renderIcon(d, size)
        } else {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        }

        // medimos cuanto del icono es opaco y que tan claro es
        val small = Bitmap.createScaledBitmap(raw, 32, 32, true)
        val px = IntArray(32 * 32)
        small.getPixels(px, 0, 32, 0, 0, 32, 32)
        var opaque = 0
        var lumaSum = 0f
        var lumaN = 0
        for (p in px) {
            val al = Color.alpha(p)
            if (al > 215) opaque++
            if (al > 40) {
                lumaSum += (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
                lumaN++
            }
        }
        val opaqueFrac = opaque / px.size.toFloat()
        val luma = if (lumaN > 0) lumaSum / lumaN else 0.5f

        val composed: Bitmap = if (opaqueFrac >= 0.92f) {
            raw
        } else {
            // icono con transparencia: lo pongo sobre un fondo que contraste
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val oc = Canvas(out)
            oc.drawColor(if (luma > 0.62f) Pal.INK else Pal.PEARL)
            val inset = size * 0.14f
            oc.drawBitmap(raw, null, RectF(inset, inset, size - inset, size - inset), Paint(Paint.FILTER_BITMAP_FLAG))
            out
        }
        return duotone(composed)
    }

    // ----------------------------------------------------------------- UI

    private fun rebuildRow() {
        val apps = loadApps()
        signature = apps.joinToString(",") { it.pkg }
        row.removeAllViews()
        var first: View? = null
        for ((idx, a) in apps.withIndex()) {
            val v = makeItem(a, idx)
            row.addView(v)
            if (first == null) first = v
        }
        first?.requestFocus()
    }

    /** Efecto "dock": el enfocado crece, los vecinos crecen un poco. */
    private fun applyDock(focusedIdx: Int) {
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            val d = abs(i - focusedIdx)
            val s = when (d) {
                0 -> 1.20f
                1 -> 1.07f
                else -> 1f
            }
            c.animate()
                .scaleX(s)
                .scaleY(s)
                .translationY(if (d == 0) -tileSize * 0.07f else 0f)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun makeItem(a: AppItem, idx: Int): View {
        val tile = tileSize
        val labelH = (panelH * 0.14f).toInt()
        val gap = (panelH * 0.02f).toInt()

        val f = FrameLayout(this)
        f.isFocusable = true
        f.isClickable = true
        f.clipChildren = false
        f.clipToPadding = false
        f.setDefaultFocusHighlightEnabled(false)

        val icon = IconView(this, buildIcon(a, (tile * 1.3f).toInt()))
        f.addView(
            icon,
            FrameLayout.LayoutParams(tile, tile, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        )

        val label = TextView(this)
        label.text = a.label
        label.typeface = bold
        label.maxLines = 1
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.gravity = Gravity.CENTER
        label.setTextColor(Pal.PEARL)
        label.alpha = 0.88f
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, panelH * 0.085f)
        softText(label)
        val llp = FrameLayout.LayoutParams(
            (tile * 1.2f).toInt(), labelH, Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        llp.topMargin = tile + gap
        f.addView(label, llp)

        val lp = LinearLayout.LayoutParams(tile, tile + gap + labelH)
        lp.rightMargin = (sw * 0.02f).toInt()
        f.layoutParams = lp

        // Sticker que aparece con rebote al enfocar la app
        val popId = resources.getIdentifier(popStickers[idx % popStickers.size], "drawable", packageName)
        val pop = ImageView(this)
        if (popId != 0) pop.setImageResource(popId)
        pop.scaleType = ImageView.ScaleType.FIT_CENTER
        pop.alpha = 0f
        pop.scaleX = 0f
        pop.scaleY = 0f
        val popSize = (tile * 0.55f).toInt()
        val popLp = FrameLayout.LayoutParams(popSize, popSize, Gravity.TOP or Gravity.END)
        popLp.topMargin = -(popSize * 0.35f).toInt()
        popLp.rightMargin = -(popSize * 0.30f).toInt()
        f.addView(pop, popLp)

        f.setOnFocusChangeListener { v, has ->
            icon.setFocusedLook(has)
            label.animate().alpha(if (has) 1f else 0.88f).setDuration(160).start()
            if (has) {
                applyDock(idx)
                pop.animate().cancel()
                pop.rotation = -20f
                pop.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .rotation(10f)
                    .setDuration(320)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .start()
                scroll.smoothScrollTo(v.left - (scroll.width - v.width) / 2, 0)
            } else {
                pop.animate().cancel()
                pop.animate().alpha(0f).scaleX(0f).scaleY(0f).setDuration(140).start()
            }
        }
        f.setOnClickListener { launch(a) }
        f.setOnLongClickListener {
            hide(a)
            true
        }
        return f
    }

    private fun launch(a: AppItem) {
        try {
            val i = Intent(Intent.ACTION_MAIN)
            i.setClassName(a.pkg, a.ri.activityInfo.name)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir " + a.label, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hide(a: AppItem) {
        val s = HashSet<String>(prefs.getStringSet("hidden", emptySet()) ?: emptySet())
        s.add(a.pkg)
        prefs.edit().putStringSet("hidden", s).apply()
        Toast.makeText(this, a.label + " oculta", Toast.LENGTH_SHORT).show()
        rebuildRow()
    }

    override fun onResume() {
        super.onResume()
        val now = loadApps().joinToString(",") { it.pkg }
        if (now != signature) rebuildRow()
        if (!video.isPlaying) video.start()
    }

    override fun onPause() {
        super.onPause()
        if (video.isPlaying) video.pause()
    }

    @Deprecated("Un launcher no debe cerrarse con Atrás")
    override fun onBackPressed() {
        // no hacer nada
    }
}
