package com.focusassistant.app.platform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.core.graphics.PathParser
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 求职遮罩的动态壁纸（与 .tools/overlay-preview.html 同构图）：在 900×1600 虚拟画布上逐帧绘制，按 cover 铺满屏幕。
 * 系统「移除动画」时只画一帧静态画面；离开窗口后不再重绘。
 */
@SuppressLint("ViewConstructor")
internal class OverlayWallpaperView(context: Context) : View(context) {
    private val reduceMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    private val startMs = SystemClock.uptimeMillis()
    private val rng = Random(SystemClock.elapsedRealtime())

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private val skyShader = LinearGradient(0f, 0f, 400f, H, intArrayOf(c(0xFF7DB8EC), c(0xFFA9D3F7), c(0xFFD4EAFD), c(0xFFEEF7FF)), floatArrayOf(0f, .45f, .8f, 1f), Shader.TileMode.CLAMP)
    private val vignetteShader = RadialGradient(450f, 720f, 975f, intArrayOf(0x004F86C9, 0x004F86C9, 0x404F86C9), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
    private val sunHaloShader = RadialGradient(0f, 0f, 160f, intArrayOf(c(0xF2FFFBE8), c(0x73FFF0C2), 0x00FFF0C2), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
    private val atmoShader = RadialGradient(0f, 0f, 650f, intArrayOf(c(0xD9FFFFFF), c(0xD9FFFFFF), 0x00FFFFFF), floatArrayOf(0f, .88f, 1f), Shader.TileMode.CLAMP)
    private val planetShader = RadialGradient(-240f, -456f, 840f, intArrayOf(Color.WHITE, c(0xFFD3E4FF), c(0xFFA3B3F0), c(0xFF7D86DA)), floatArrayOf(0f, .35f, .7f, 1f), Shader.TileMode.CLAMP)
    private val glowShader = RadialGradient(0f, 0f, 10f, intArrayOf(c(0x99FFE9A8), 0x00FFE9A8), null, Shader.TileMode.CLAMP)
    private val beamShader = LinearGradient(0f, 10f, 0f, 205f, c(0xD9FFFBE0), 0x00FFFBE0, Shader.TileMode.CLAMP)
    private val meteorShader = LinearGradient(-170f, 0f, 0f, 0f, 0x00FFFFFF, Color.WHITE, Shader.TileMode.CLAMP)
    private val whaleShader = LinearGradient(0f, -56f, 0f, 46f, c(0xFF6F9AE0), c(0xFF9EC0F2), Shader.TileMode.CLAMP)
    private val dashNear = DashPathEffect(floatArrayOf(2f, 14f), 0f)
    private val dashFar = DashPathEffect(floatArrayOf(1f, 24f), 0f)

    private val twinkle = svg("M0,-10 C1,-2 2,-1 10,0 C2,1 1,2 0,10 C-1,2 -2,1 -10,0 C-2,-1 -1,-2 0,-10Z")
    private val tinyStar = svg("M0,-9 L2.6,-2.8 9,-2.8 3.9,1.2 5.6,8 0,4.2 -5.6,8 -3.9,1.2 -9,-2.8 -2.6,-2.8Z")
    private val crescent = Path().apply {
        addCircle(0f, 0f, 62f, Path.Direction.CW)
        op(Path().apply { addCircle(30f, -18f, 56f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
    }
    private val moonEye = svg("M-34,-6 q7,7 14,0")
    private val moonMouth = svg("M-40,22 q8,6 16,2")
    private val zPaths = Array(3) { i -> val s = 10f + i * 4f; Path().apply { moveTo(0f, 0f); rLineTo(s, 0f); rLineTo(-s, s); rLineTo(s, 0f) } }
    private val cloud = Path().apply {
        for (e in CLOUD_PUFFS) addOval(RectF(e[0] - e[2], e[1] - e[3], e[0] + e[2], e[1] + e[3]), Path.Direction.CW)
    }
    private val heartLine = Path().apply {
        HEART_POINTS.forEachIndexed { i, p -> if (i == 0) moveTo(p[0], p[1]) else lineTo(p[0], p[1]) }
        close()
    }
    private val heartMeasure = PathMeasure(heartLine, true)
    private val heartSegment = Path()
    private val heartPixels = Path().apply {
        for (p in HEART_PIXELS) addRect((p[0] - 3.5f) * 4f, (p[1] - 3f) * 4f, (p[0] - 2.5f) * 4f, (p[1] - 2f) * 4f, Path.Direction.CW)
    }
    private val planetClip = Path().apply { addCircle(0f, 0f, 600f, Path.Direction.CW) }
    private val ufoDome = svg("M-30,-4 A30,30 0 0 1 30,-4 Z")
    private val ufoDomeEdge = svg("M-30,-4 A30,30 0 0 1 30,-4")
    private val ufoBeam = svg("M-26,10 L26,10 70,205 -70,205Z")
    private val satelliteDish = svg("M-9,-26 A9,6 0 0 0 9,-26 Z")
    private val rocketFlame = svg("M-30,-8 Q-62,0 -30,8 Z")
    private val rocketFlameCore = svg("M-30,-5 Q-46,0 -30,5 Z")
    private val rocketFins = svg("M-24,-12 L-38,-26 L-12,-12Z M-24,12 L-38,26 L-12,12Z")
    private val rocketBody = svg("M-30,-12 L14,-12 Q42,0 14,12 L-30,12 Z")
    private val helmetGlint = svg("M-30,-28 A38,38 0 0 1 -4,-40")
    private val whaleTail = svg("M0,-12 C14,-20 30,-44 46,-46 C54,-46 54,-38 48,-30 L34,0 L48,30 C54,38 54,46 46,46 C30,44 14,20 0,12Z")
    private val whaleBody = svg("M-104,4 C-104,-38 -52,-56 0,-52 C50,-48 84,-26 98,-8 C102,0 102,8 96,14 C72,38 30,46 -14,44 C-66,42 -104,32 -104,4Z")
    private val whaleBelly = svg("M-100,14 C-82,34 -34,40 14,36 C50,33 76,22 92,12 C60,20 20,24 -20,22 C-56,20 -84,16 -100,14Z")
    private val whaleFin = svg("M-24,26 C-14,48 6,56 16,44 C6,38 -6,32 -24,26Z")
    private val whaleMouth = svg("M-84,8 q8,6 16,0")
    private val whaleGrooves = svg("M-54,24 q4,8 0,14 M-34,24 q4,8 0,14 M-14,24 q4,8 0,14 M6,24 q4,8 0,14")

    private class Star(val x: Float, val y: Float, val size: Float, val low: Float, val period: Float, val phase: Float)
    private class Band(val path: Path, val color: Int, val width: Float, val alpha: Float, val shift: Float, val period: Float)
    private class Meteor(val start: Float, val x: Float, val y: Float, val angle: Float, val travel: Float, val duration: Float)
    private class Rocket(val start: Float, val sx: Float, val sy: Float, val ex: Float, val ey: Float, val duration: Float, val puffs: FloatArray)
    private class Spray(val at: Float, val stars: FloatArray)
    private class Whale(val start: Float, val y: Float, val endY: Float, val duration: Float, val sprays: List<Spray>)
    private class Astronaut(val start: Float, val y: Float, val endY: Float, val duration: Float)

    private val farStars = stars(70, 5f, 10f)
    private val nearStars = stars(20, 11f, 18f)
    private val bands = buildList {
        var y = -620f
        while (y <= -60f) {
            add(Band(wave(y, r(8f, 16f)), BAND_COLORS[rng.nextInt(BAND_COLORS.size)], r(26f, 50f), r(.2f, .4f),
                if (rng.nextBoolean()) 300f else -300f, r(18f, 40f)))
            y += r(55f, 85f)
        }
    }

    private val meteors = ArrayList<Meteor>()
    private val rockets = ArrayList<Rocket>()
    private val whales = ArrayList<Whale>()
    private val astronauts = ArrayList<Astronaut>()
    private val hearts = ArrayList<Float>()
    private var nextMeteor = 1.5f
    private var nextHeart = 2.5f
    private var nextRocket = 5f
    private var nextWhale = 1f
    private var nextAstronaut = 12f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val t = if (reduceMotion) STATIC_TIME else (SystemClock.uptimeMillis() - startMs) / 1000f
        val scale = max(width / W, height / H)
        canvas.save()
        canvas.translate((width - W * scale) / 2f, (height - H * scale) / 2f)
        canvas.scale(scale, scale)
        if (!reduceMotion) spawn(t)
        drawScene(canvas, t)
        canvas.restore()
        if (!reduceMotion && isAttachedToWindow) postInvalidateDelayed(FRAME_MS)
    }

    private fun drawScene(canvas: Canvas, t: Float) {
        shaded(skyShader) { canvas.drawRect(0f, 0f, W, H, fill) }
        drawClouds(canvas, t)
        drawStars(canvas, farStars, t, 600f)
        drawStars(canvas, nearStars, t, 320f)
        drawMiniSparks(canvas, t)
        drawSun(canvas, t)
        drawMoon(canvas, t)
        drawConstellation(canvas, t)
        drawSatellite(canvas, t)
        drawPlanet(canvas, t)
        drawFishingLine(canvas, t)
        drawFisher(canvas, t)
        drawUfo(canvas, t)
        meteors.forEach { drawMeteor(canvas, it, t) }
        rockets.forEach { drawRocket(canvas, it, t) }
        whales.forEach { drawWhale(canvas, it, t) }
        astronauts.forEach { drawAstronaut(canvas, it, t) }
        shaded(vignetteShader) { canvas.drawRect(0f, 0f, W, H, fill) }
    }

    private fun spawn(t: Float) {
        meteors.removeAll { t - it.start >= it.duration }
        rockets.removeAll { t - it.start >= it.duration + 1.5f }
        whales.removeAll { t - it.start >= it.duration }
        astronauts.removeAll { t - it.start >= it.duration }
        hearts.removeAll { t - it >= HEART_SECONDS }
        if (t >= nextMeteor) {
            meteors += Meteor(t, r(-100f, 600f), r(-50f, 450f), r(18f, 32f), r(500f, 800f), r(1f, 1.5f))
            nextMeteor = t + r(5f, 11f)
        }
        if (t >= nextHeart) {
            hearts += t
            nextHeart = t + r(6f, 11f)
        }
        if (t >= nextRocket) {
            val sx = r(160f, 260f)
            val sy = PLANET_Y - sqrt(PLANET_R * PLANET_R - (sx - PLANET_X) * (sx - PLANET_X)) - 6f
            val puffs = FloatArray(ROCKET_PUFFS * 4) { i ->
                when (i % 4) { 0 -> r(6f, 12f); 1 -> r(-40f, 30f); 2 -> r(-6f, 10f); else -> r(.9f, 1.4f) }
            }
            rockets += Rocket(t, sx, sy, 1040f, r(120f, 380f), r(6f, 8f), puffs)
            nextRocket = t + r(12f, 20f)
        }
        if (t >= nextWhale) {
            val duration = r(30f, 36f)
            val sprays = ArrayList<Spray>()
            var at = 2f
            while (at < duration) {
                sprays += Spray(at, FloatArray(SPRAY_STARS * 3) { i -> when (i % 3) { 0 -> r(10f, 18f); 1 -> r(-40f, 40f); else -> r(1.3f, 1.9f) } })
                at += r(3.5f, 5.5f)
            }
            val y = r(300f, 420f)
            whales += Whale(t, y, y + r(-40f, 40f), duration, sprays)
            nextWhale = t + r(40f, 55f)
        }
        if (t >= nextAstronaut) {
            val y = r(560f, 760f)
            astronauts += Astronaut(t, y, y + r(-60f, 60f), r(24f, 30f))
            nextAstronaut = t + r(32f, 42f)
        }
    }

    private fun drawClouds(canvas: Canvas, t: Float) {
        for (c in CLOUDS) {
            canvas.save()
            canvas.translate(-220f + 1340f * frac((t - c[3]) / c[2]), c[0])
            canvas.scale(c[1], c[1])
            canvas.drawPath(cloud, fill.tone(Color.WHITE, .55f + c[1] * .25f))
            canvas.restore()
        }
    }

    private fun drawStars(canvas: Canvas, list: List<Star>, t: Float, period: Float) {
        canvas.save()
        canvas.rotate(360f * t / period, 450f, 600f)
        for (s in list) {
            drawShape(canvas, twinkle, s.x, s.y, s.size, fill.tone(Color.WHITE, s.low + (1f - s.low) * osc(t + s.phase, s.period)))
        }
        canvas.restore()
    }

    private fun drawMiniSparks(canvas: Canvas, t: Float) {
        MINI_SPARKS.forEachIndexed { i, m ->
            canvas.save()
            canvas.translate(m[0], m[1])
            canvas.rotate((if (i % 2 == 1) -360f else 360f) * t / (20f + i * 4f))
            drawRays(canvas, m[2], SPARK_COLORS[i % SPARK_COLORS.size], .25f + .75f * osc(t, 3f + i * .7f))
            canvas.restore()
        }
    }

    private fun drawSun(canvas: Canvas, t: Float) {
        canvas.save()
        canvas.translate(170f, 230f)
        shaded(sunHaloShader) { canvas.drawCircle(0f, 0f, 160f, fill) }
        val pulse = 1f + .08f * osc(t, 7f)
        canvas.scale(pulse, pulse)
        canvas.save()
        canvas.rotate(4f * t)
        drawRays(canvas, 66f, c(0xFFFFD27A), 1f)
        canvas.restore()
        canvas.drawCircle(0f, 0f, 30f, fill.tone(c(0xFFFFF8DE)))
        canvas.drawCircle(0f, 0f, 22f, fill.tone(c(0xFFFFE7A0)))
        canvas.restore()
    }

    private fun drawMoon(canvas: Canvas, t: Float) {
        canvas.save()
        canvas.translate(730f, 430f)
        canvas.save()
        val k = osc(t, 8f)
        canvas.translate(0f, -18f * k)
        canvas.rotate(-6f + 10f * k)
        lifted { canvas.drawPath(crescent, fill.tone(c(0xFFFFF3C4))) }
        canvas.drawPath(moonEye, line(c(0xFF8A6656), 3f))
        canvas.drawCircle(-30f, 10f, 6f, fill.tone(c(0xFFFF9FB2), .6f))
        canvas.drawPath(moonMouth, line(c(0xFF8A6656), 2.5f))
        canvas.restore()
        for (i in 0 until 3) {
            val local = t - i * 1.3f
            if (local < 0f) continue
            val p = frac(local / 4f)
            canvas.save()
            canvas.translate(-10f + 40f * p, -50f - 100f * p)
            canvas.drawPath(zPaths[i], line(c(0xFF6F92D6), 3f, .9f * (1f - abs(2f * p - 1f))))
            canvas.restore()
        }
        canvas.restore()
    }

    private fun drawConstellation(canvas: Canvas, t: Float) {
        canvas.save()
        canvas.translate(215f, 760f)
        val p = frac(t / 12f)
        val drawn = when {
            p < .45f -> p / .45f
            p < .8f -> 1f
            else -> 1f - (p - .8f) / .2f
        }
        if (drawn > 0f) {
            heartSegment.reset()
            heartMeasure.getSegment(0f, heartMeasure.length * drawn, heartSegment, true)
            canvas.drawPath(heartSegment, line(c(0xFF6F9BE0), 2f, .7f))
        }
        HEART_POINTS.forEachIndexed { i, pt ->
            drawShape(canvas, twinkle, pt[0], pt[1], if (i % 3 == 0) 20f else 14f, fill.tone(Color.WHITE, .5f + .5f * osc(t, 2f + (i % 4) * .6f)))
        }
        canvas.restore()
    }

    private fun drawSatellite(canvas: Canvas, t: Float) {
        val p = frac(t / 90f)
        canvas.save()
        canvas.translate(-160f + 1220f * p, 720f - 180f * p)
        canvas.rotate(360f * t / 24f)
        for (x in PANEL_X) {
            canvas.drawRect(x, -9f, x + 44f, 9f, fill.tone(c(0xFF3E5FA8)))
            canvas.drawRect(x, -9f, x + 44f, 9f, line(Color.WHITE, 1.5f))
        }
        val grid = line(c(0xFFA9C4FF), 1f)
        for (x in PANEL_GRID_X) canvas.drawLine(x, -9f, x, 9f, grid)
        canvas.drawRoundRect(-18f, -13f, 18f, 13f, 4f, 4f, fill.tone(c(0xFFF2C066)))
        canvas.drawLine(0f, -13f, 0f, -26f, line(Color.WHITE, 2f))
        canvas.drawPath(satelliteDish, fill.tone(Color.WHITE))
        canvas.drawCircle(10f, 6f, 2.5f, fill.tone(c(0xFFFF5D6C), 1f - osc(t, 1.4f)))
        canvas.restore()
    }

    private fun drawPlanet(canvas: Canvas, t: Float) {
        canvas.save()
        canvas.translate(PLANET_X, PLANET_Y)

        canvas.save()
        val atmo = (640f + 28f * osc(t, 9f)) / 650f
        canvas.scale(atmo, atmo)
        shaded(atmoShader) { canvas.drawCircle(0f, 0f, 650f, fill) }
        canvas.restore()

        drawOrbitRing(canvas, 700f, dashNear, .7f, 10f * t / 700f)
        drawOrbitRing(canvas, 790f, dashFar, .5f, -400f / 60f * t / 790f)

        for (i in ORBITER_TAIL downTo 0) {
            val angle = PI.toFloat() + 2f * PI.toFloat() * (t + 4f - i * .22f) / 36f
            val x = 790f * cos(angle)
            val y = 790f * sin(angle)
            if (i == 0) {
                drawGlow(canvas, x, y, 34f)
                canvas.drawCircle(x, y, 15f, fill.tone(c(0xFFFFC94D)))
                canvas.drawCircle(x - 4f, y - 4f, 6f, fill.tone(Color.WHITE, .75f))
            } else {
                canvas.drawCircle(x, y, 11f * (1f - i / 8f), fill.tone(c(0xFFFFC94D), .6f - i * .07f))
            }
        }

        shaded(planetShader) { canvas.drawCircle(0f, 0f, PLANET_R, fill) }
        canvas.save()
        canvas.clipPath(planetClip)
        canvas.rotate(-8f)
        for (b in bands) {
            canvas.save()
            canvas.translate(b.shift * frac(t / b.period), 0f)
            canvas.drawPath(b.path, line(b.color, b.width, b.alpha))
            canvas.restore()
        }
        val swirlX = -520f + 1040f * osc(t, 68f)
        canvas.drawOval(swirlX - 90f, -500f, swirlX + 90f, -440f, fill.tone(Color.WHITE, .5f))
        canvas.restore()
        canvas.drawCircle(0f, 0f, PLANET_R, line(Color.WHITE, 10f, .75f))
        canvas.restore()
    }

    private fun drawOrbitRing(canvas: Canvas, radius: Float, dash: DashPathEffect, alpha: Float, radians: Float) {
        canvas.save()
        canvas.rotate(Math.toDegrees(radians.toDouble()).toFloat())
        stroke.pathEffect = dash
        canvas.drawCircle(0f, 0f, radius, line(Color.WHITE, 2.5f, alpha))
        stroke.pathEffect = null
        canvas.restore()
    }

    private fun drawFishingLine(canvas: Canvas, t: Float) {
        canvas.save()
        canvas.translate(319f, 969f)
        canvas.rotate(-7f + 14f * osc(t, 5f))
        canvas.drawLine(0f, 0f, 0f, 168f, line(c(0xFF4F6FA8), 1.4f, .65f))
        val y = 168f + 12f - 9f * osc(t, 2.2f)
        drawGlow(canvas, 0f, y, 30f)
        drawStarShape(canvas, 0f, y, 28f)
        canvas.restore()
    }

    private fun drawFisher(canvas: Canvas, t: Float) {
        canvas.save()
        canvas.translate(560f, 1117f)
        canvas.rotate(-13.5f)
        canvas.drawLine(-44f, -36f, -200f, -200f, line(c(0xFFA8724E), 4.5f))
        canvas.drawCircle(-62f, -55f, 6f, fill.tone(Color.WHITE))
        canvas.drawCircle(-62f, -55f, 6f, line(c(0xFFA8724E), 2f))
        drawClawd(canvas, -48f, -61f - 3f * osc(t, 3.2f), 8f, t)
        for (start in hearts) {
            val e = easeOut(((t - start) / HEART_SECONDS).coerceIn(0f, 1f))
            canvas.save()
            canvas.translate(0f, -86f + keyframe(e, HEART_KEYS, HEART_Y))
            val s = keyframe(e, HEART_KEYS, HEART_SCALE)
            canvas.scale(s, s)
            canvas.drawPath(heartPixels, fill.tone(c(0xFFFF6B8B), keyframe(e, HEART_KEYS, HEART_ALPHA)))
            canvas.restore()
        }
        canvas.restore()
    }

    private fun drawUfo(canvas: Canvas, t: Float) {
        canvas.save()
        canvas.translate(720f, 860f)
        val k = osc(t, 5f)
        canvas.translate(-12f * k, 14f * k)
        canvas.rotate(-4f + 8f * k)
        fill.tone(Color.WHITE, .2f + .65f * osc(t, 4f))
        fill.shader = beamShader
        canvas.drawPath(ufoBeam, fill)
        fill.shader = null
        val p = frac(t / 4f)
        val starAlpha = when { p < .2f -> p / .2f; p < .8f -> 1f; else -> (1f - p) / .2f }
        drawStarShape(canvas, 0f, 200f - 178f * p, 20f, starAlpha)
        canvas.drawPath(ufoDome, fill.tone(c(0xFFE8F7FF), .55f))
        canvas.drawCircle(0f, -14f, 11f, fill.tone(c(0xFFB48CFF)))
        canvas.drawCircle(-4f, -15f, 2.2f, fill.tone(c(0xFF1A1020)))
        canvas.drawCircle(4f, -15f, 2.2f, fill)
        canvas.drawLine(0f, -25f, 0f, -33f, line(c(0xFFB48CFF), 2f))
        canvas.drawCircle(0f, -35f, 3f, fill.tone(c(0xFFFF8FB0), 1f - .7f * osc(t, 1.2f)))
        canvas.drawPath(ufoDomeEdge, line(Color.WHITE, 2f, .8f))
        lifted { canvas.drawOval(-72f, -17f, 72f, 17f, fill.tone(c(0xFFEEF1FF))) }
        canvas.drawOval(-58f, -12f, 58f, 4f, fill.tone(Color.WHITE, .9f))
        canvas.drawOval(-44f, 0f, 44f, 18f, fill.tone(c(0xFF8F9BD6)))
        UFO_LIGHTS.forEachIndexed { i, color ->
            canvas.drawCircle(-52f + i * 26f, 3f, 4f, fill.tone(color, .3f + .7f * osc(t - i * .3f, 1.5f)))
        }
        canvas.restore()
    }

    private fun drawMeteor(canvas: Canvas, m: Meteor, t: Float) {
        val e = ((t - m.start) / m.duration).coerceIn(0f, 1f).let { it * it }
        val alpha = if (e < .15f) e / .15f else 1f - (e - .15f) / .85f
        canvas.save()
        canvas.translate(m.x, m.y)
        canvas.rotate(m.angle)
        canvas.translate(m.travel * e, 0f)
        fill.tone(Color.WHITE, alpha)
        fill.shader = meteorShader
        canvas.drawRoundRect(-170f, -1.6f, 0f, 1.6f, 1.6f, 1.6f, fill)
        fill.shader = null
        drawStarShape(canvas, 0f, 0f, 18f, alpha)
        canvas.restore()
    }

    private fun drawRocket(canvas: Canvas, rocket: Rocket, t: Float) {
        val local = t - rocket.start
        for (i in 0 until ROCKET_PUFFS) {
            val duration = rocket.puffs[i * 4 + 3]
            val q = (local - i * .06f) / duration
            if (q < 0f || q >= 1f) continue
            val e = easeOut(q)
            canvas.save()
            canvas.translate(rocket.sx + rocket.puffs[i * 4 + 1] * e, rocket.sy + rocket.puffs[i * 4 + 2] * e)
            canvas.drawCircle(0f, 0f, rocket.puffs[i * 4] * (.4f + 1.2f * e), fill.tone(Color.WHITE, .9f * (1f - e)))
            canvas.restore()
        }
        if (local >= rocket.duration) return
        val e = (local / rocket.duration).let { it * it }
        val midX = rocket.sx + (rocket.ex - rocket.sx) * .1f
        val midY = rocket.sy + (rocket.ey - rocket.sy) * .1f
        val x: Float
        val y: Float
        val s: Float
        if (e < .12f) {
            val k = e / .12f
            x = rocket.sx + (midX - rocket.sx) * k; y = rocket.sy + (midY - rocket.sy) * k; s = .4f + .6f * k
        } else {
            val k = (e - .12f) / .88f
            x = midX + (rocket.ex - midX) * k; y = midY + (rocket.ey - midY) * k; s = 1f
        }
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(s, s)
        canvas.rotate(Math.toDegrees(atan2((rocket.ey - rocket.sy).toDouble(), (rocket.ex - rocket.sx).toDouble())).toFloat())
        canvas.save()
        val flame = .75f + .5f * osc(t, .28f)
        canvas.translate(-30f, 0f); canvas.scale(flame, 1f); canvas.translate(30f, 0f)
        canvas.drawPath(rocketFlame, fill.tone(c(0xFFFFB84D)))
        canvas.drawPath(rocketFlameCore, fill.tone(c(0xFFFFF4D6)))
        canvas.restore()
        canvas.drawPath(rocketFins, fill.tone(c(0xFFFF7A9C)))
        lifted { canvas.drawPath(rocketBody, fill.tone(Color.WHITE)) }
        canvas.drawPath(rocketBody, line(c(0xFF6F86C4), 1.5f))
        canvas.drawRect(-16f, -12f, -10f, 12f, fill.tone(c(0xFFD97757)))
        canvas.drawCircle(4f, 0f, 6f, fill.tone(c(0xFF8FD0FF)))
        canvas.drawCircle(4f, 0f, 6f, line(c(0xFF5A6FA8), 2f))
        canvas.restore()
    }

    private fun drawWhale(canvas: Canvas, whale: Whale, t: Float) {
        val local = t - whale.start
        val p = local / whale.duration
        canvas.save()
        canvas.translate(1100f - 1360f * p, whale.y + (whale.endY - whale.y) * p)
        canvas.scale(.9f, .9f)
        val k = osc(t, 4.5f)
        canvas.translate(0f, -10f + 22f * k)
        canvas.rotate(-3f + 6f * k)
        canvas.save()
        canvas.translate(92f, 0f)
        canvas.rotate(-10f + 22f * osc(t, 1.8f))
        canvas.drawPath(whaleTail, fill.tone(c(0xFF7EA6E6)))
        canvas.restore()
        fill.tone(Color.WHITE)
        fill.shader = whaleShader
        lifted { canvas.drawPath(whaleBody, fill) }
        fill.shader = null
        canvas.drawPath(whaleBelly, fill.tone(c(0xFFF2F7FF)))
        canvas.drawPath(whaleGrooves, line(c(0xFFC9DBF7), 2f))
        canvas.drawPath(whaleFin, fill.tone(c(0xFF6F98DC)))
        canvas.drawCircle(-66f, -8f, 4.5f, fill.tone(c(0xFF1A1020)))
        canvas.drawCircle(-67.5f, -9.5f, 1.5f, fill.tone(Color.WHITE))
        canvas.drawOval(-65f, 2f, -51f, 10f, fill.tone(c(0xFFFF9FB2), .65f))
        canvas.drawPath(whaleMouth, line(c(0xFF2A3A66), 2.5f))
        fill.tone(Color.WHITE, .35f)
        canvas.drawCircle(18f, -26f, 6f, fill)
        canvas.drawCircle(40f, -10f, 4f, fill)
        canvas.drawCircle(8f, -6f, 5f, fill)
        for (spray in whale.sprays) {
            if (local < spray.at) break
            for (j in 0 until SPRAY_STARS) {
                val size = spray.stars[j * 3]
                val dx = spray.stars[j * 3 + 1]
                val q = (local - spray.at - j * .09f) / spray.stars[j * 3 + 2]
                if (q < 0f || q >= 1f) continue
                val e = easeOut(q)
                val alpha = keyframe(e, SPRAY_KEYS, SPRAY_ALPHA)
                val sx = -40f + dx * keyframe(e, SPRAY_KEYS, SPRAY_DX)
                val sy = -56f + keyframe(e, SPRAY_KEYS, SPRAY_Y)
                val scaled = size * keyframe(e, SPRAY_KEYS, SPRAY_SCALE)
                if (j % 2 == 1) drawShape(canvas, twinkle, sx, sy, scaled, fill.tone(Color.WHITE, alpha))
                else drawStarShape(canvas, sx, sy, scaled, alpha)
            }
        }
        canvas.restore()
    }

    private fun drawAstronaut(canvas: Canvas, a: Astronaut, t: Float) {
        val e = .5f - .5f * cos(PI.toFloat() * ((t - a.start) / a.duration))
        val x: Float
        val y: Float
        if (e < .5f) {
            val k = e / .5f; x = 1000f - 550f * k; y = a.y - 100f * k
        } else {
            val k = (e - .5f) / .5f; x = 450f - 570f * k; y = a.y - 100f + (a.endY - a.y + 100f) * k
        }
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(360f * t / 14f)
        canvas.drawCircle(0f, 0f, 46f, fill.tone(Color.WHITE, .35f))
        drawClawd(canvas, -30f, -20f, 5f, t)
        canvas.drawCircle(0f, 0f, 46f, line(Color.WHITE, 3.5f))
        canvas.drawPath(helmetGlint, line(Color.WHITE, 4f))
        canvas.restore()
    }

    private fun drawClawd(canvas: Canvas, left: Float, top: Float, unit: Float, t: Float) {
        fill.tone(c(0xFFD97757))
        lifted {
            for (r in CLAWD_BODY) canvas.drawRect(left + r[0] * unit, top + r[1] * unit, left + (r[0] + r[2]) * unit, top + (r[1] + r[3]) * unit, fill)
        }
        val phase = frac(t / 4.6f)
        val open = when {
            phase < .9f -> 1f
            phase < .94f -> 1f - .9f * (phase - .9f) / .04f
            else -> .1f + .9f * (phase - .94f) / .06f
        }
        val half = .8f * open * unit
        val centerY = top + 2.4f * unit
        fill.tone(c(0xFF1A1020))
        for (x in CLAWD_EYES_X) canvas.drawRect(left + x * unit, centerY - half, left + (x + 1f) * unit, centerY + half, fill)
    }

    private fun drawRays(canvas: Canvas, size: Float, color: Int, alpha: Float) {
        val paint = line(color, size * .17f, alpha)
        RAY_LENGTHS.forEachIndexed { i, len ->
            val a = 2f * PI.toFloat() * i / RAY_LENGTHS.size
            canvas.drawLine(cos(a) * size * .12f, sin(a) * size * .12f, cos(a) * size * len, sin(a) * size * len, paint)
        }
    }

    private fun drawStarShape(canvas: Canvas, x: Float, y: Float, size: Float, alpha: Float = 1f) {
        drawShape(canvas, tinyStar, x, y, size, fill.tone(c(0xFFFFD45E), alpha))
        stroke.strokeWidth = .8f
        drawShape(canvas, tinyStar, x, y, size, stroke.tone(c(0xFFF2A93B), alpha))
    }

    private fun drawShape(canvas: Canvas, path: Path, x: Float, y: Float, size: Float, paint: Paint) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(size / 20f, size / 20f)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private fun drawGlow(canvas: Canvas, x: Float, y: Float, radius: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(radius / 10f, radius / 10f)
        fill.tone(Color.WHITE)
        fill.shader = glowShader
        canvas.drawCircle(0f, 0f, 10f, fill)
        fill.shader = null
        canvas.restore()
    }

    private inline fun shaded(shader: Shader, draw: () -> Unit) {
        fill.tone(Color.WHITE)
        fill.shader = shader
        draw()
        fill.shader = null
    }

    private inline fun lifted(draw: () -> Unit) {
        fill.setShadowLayer(12f, 0f, 6f, SHADOW)
        draw()
        fill.clearShadowLayer()
    }

    private fun line(color: Int, width: Float, alpha: Float = 1f): Paint {
        stroke.strokeWidth = width
        return stroke.tone(color, alpha)
    }

    private fun Paint.tone(color: Int, alpha: Float = 1f): Paint {
        this.color = color
        this.alpha = (Color.alpha(color) * alpha.coerceIn(0f, 1f)).roundToInt()
        return this
    }

    private fun stars(count: Int, minSize: Float, maxSize: Float) = List(count) {
        val period = r(2.5f, 7f)
        Star(r(-300f, 1200f), 1250f * Math.pow(rng.nextDouble(), 1.4).toFloat() - 150f, r(minSize, maxSize), r(.15f, .4f), period, r(0f, period))
    }

    /** 宽 150 的半波交替起伏，周期 300，平移一个周期即可无缝循环。 */
    private fun wave(y: Float, amp: Float) = Path().apply {
        moveTo(-1200f, y)
        var x = -1200f
        var up = true
        while (x < 1200f) {
            val peak = if (up) y - amp else y + amp
            cubicTo(x + 50f, peak, x + 100f, peak, x + 150f, y)
            x += 150f
            up = !up
        }
    }

    private fun r(min: Float, max: Float) = min + rng.nextFloat() * (max - min)

    private companion object {
        const val W = 900f
        const val H = 1600f
        const val FRAME_MS = 33L
        const val STATIC_TIME = 6f
        const val PLANET_X = 700f
        const val PLANET_Y = 1700f
        const val PLANET_R = 600f
        const val ORBITER_TAIL = 6
        const val ROCKET_PUFFS = 6
        const val SPRAY_STARS = 5
        const val HEART_SECONDS = 2.2f
        val SHADOW = c(0x383C6AA8)

        val CLOUDS = arrayOf(
            floatArrayOf(150f, 1.1f, 120f, -20f), floatArrayOf(560f, .85f, 150f, -70f),
            floatArrayOf(880f, 1.25f, 110f, -45f), floatArrayOf(1080f, .95f, 135f, -100f)
        )
        val CLOUD_PUFFS = arrayOf(
            floatArrayOf(0f, 0f, 70f, 28f), floatArrayOf(-50f, 8f, 45f, 20f), floatArrayOf(52f, 6f, 50f, 22f),
            floatArrayOf(-12f, -20f, 42f, 30f), floatArrayOf(28f, -14f, 34f, 24f)
        )
        val MINI_SPARKS = arrayOf(
            floatArrayOf(95f, 560f, 9f), floatArrayOf(830f, 170f, 11f), floatArrayOf(470f, 520f, 7f),
            floatArrayOf(360f, 110f, 7f), floatArrayOf(860f, 690f, 9f), floatArrayOf(70f, 980f, 7f)
        )
        val SPARK_COLORS = intArrayOf(Color.WHITE, c(0xFFFFE7A0), c(0xFFFFD27A))
        val RAY_LENGTHS = floatArrayOf(1f, .72f, .9f, .64f, .96f, .7f, .86f, .62f, 1f, .76f, .88f, .66f)
        val HEART_POINTS = arrayOf(
            floatArrayOf(0f, -20f), floatArrayOf(-30f, -52f), floatArrayOf(-68f, -40f), floatArrayOf(-76f, 0f), floatArrayOf(-40f, 44f),
            floatArrayOf(0f, 82f), floatArrayOf(40f, 44f), floatArrayOf(76f, 0f), floatArrayOf(68f, -40f), floatArrayOf(30f, -52f)
        )
        val HEART_PIXELS = arrayOf(
            floatArrayOf(1f, 0f), floatArrayOf(2f, 0f), floatArrayOf(4f, 0f), floatArrayOf(5f, 0f),
            floatArrayOf(0f, 1f), floatArrayOf(1f, 1f), floatArrayOf(2f, 1f), floatArrayOf(3f, 1f), floatArrayOf(4f, 1f), floatArrayOf(5f, 1f), floatArrayOf(6f, 1f),
            floatArrayOf(0f, 2f), floatArrayOf(1f, 2f), floatArrayOf(2f, 2f), floatArrayOf(3f, 2f), floatArrayOf(4f, 2f), floatArrayOf(5f, 2f), floatArrayOf(6f, 2f),
            floatArrayOf(1f, 3f), floatArrayOf(2f, 3f), floatArrayOf(3f, 3f), floatArrayOf(4f, 3f), floatArrayOf(5f, 3f),
            floatArrayOf(2f, 4f), floatArrayOf(3f, 4f), floatArrayOf(4f, 4f), floatArrayOf(3f, 5f)
        )
        val HEART_KEYS = floatArrayOf(0f, .25f, .7f, 1f)
        val HEART_Y = floatArrayOf(0f, -14f, -26f, -46f)
        val HEART_SCALE = floatArrayOf(.2f, 1.15f, 1f, .9f)
        val HEART_ALPHA = floatArrayOf(0f, 1f, 1f, 0f)
        val SPRAY_KEYS = floatArrayOf(0f, .35f, 1f)
        val SPRAY_Y = floatArrayOf(0f, -40f, -70f)
        val SPRAY_SCALE = floatArrayOf(.3f, 1f, .8f)
        val SPRAY_ALPHA = floatArrayOf(0f, 1f, 0f)
        val SPRAY_DX = floatArrayOf(0f, .4f, 1f)
        val PANEL_X = floatArrayOf(-62f, 18f)
        val PANEL_GRID_X = floatArrayOf(-51f, -40f, -29f, 29f, 40f, 51f)
        val CLAWD_EYES_X = floatArrayOf(3f, 8f)
        val CLAWD_BODY = arrayOf(
            floatArrayOf(1f, 0f, 10f, 6f), floatArrayOf(0f, 2f, 1f, 2f), floatArrayOf(11f, 2f, 1f, 2f),
            floatArrayOf(2f, 6f, 1f, 2f), floatArrayOf(4f, 6f, 1f, 2f), floatArrayOf(7f, 6f, 1f, 2f), floatArrayOf(9f, 6f, 1f, 2f)
        )
        val UFO_LIGHTS = intArrayOf(c(0xFFFFC94D), c(0xFFFF8FB0), c(0xFF6FB8FF), c(0xFFFF8FB0), c(0xFFFFC94D))
        val BAND_COLORS = intArrayOf(Color.WHITE, c(0xFFD6E6FF), c(0xFFA9C4FF), c(0xFFC9B8FF), c(0xFFFFDCCB), c(0xFF8FA6EC), c(0xFFE9E0FF))

        fun c(argb: Long) = argb.toInt()
        fun svg(data: String): Path = PathParser.createPathFromPathData(data)
        fun frac(x: Float) = x - floor(x)
        fun osc(x: Float, period: Float) = .5f - .5f * cos(2f * PI.toFloat() * x / period)
        fun easeOut(p: Float) = 1f - (1f - p) * (1f - p)

        fun keyframe(e: Float, keys: FloatArray, values: FloatArray): Float {
            for (i in 1 until keys.size) {
                if (e <= keys[i]) {
                    val k = (e - keys[i - 1]) / (keys[i] - keys[i - 1])
                    return values[i - 1] + (values[i] - values[i - 1]) * k
                }
            }
            return values.last()
        }
    }
}
