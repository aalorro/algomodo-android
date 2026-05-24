package com.artmondo.algomodo.generators.shader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

// ── Type aliases ────────────────────────────────────────────────────────
typealias SceneSDF = (px: Float, py: Float, pz: Float) -> Float

// ── Data classes ────────────────────────────────────────────────────────
data class RenderConfig(
    val scale: Float,
    val maxSteps: Int,
    val epsilon: Float,
    val maxDist: Float
)

class CameraBasis(
    val roX: Float, val roY: Float, val roZ: Float,
    val fwdX: Float, val fwdY: Float, val fwdZ: Float,
    val rightX: Float, val rightY: Float, val rightZ: Float,
    val upX: Float, val upY: Float, val upZ: Float,
    val fovFactor: Float
)

class MarchResult {
    var hit: Boolean = false
    var t: Float = 0f
    var steps: Int = 0
    var px: Float = 0f
    var py: Float = 0f
    var pz: Float = 0f
}

// ── Quality Configuration ───────────────────────────────────────────────
fun getQualityConfig(quality: Quality, tier: String, animating: Boolean = false): RenderConfig {
    val cfg = when (tier) {
        "light" -> when (quality) {
            Quality.DRAFT -> RenderConfig(0.33f, 120, 0.002f, 50f)
            Quality.BALANCED -> RenderConfig(0.50f, 80, 0.001f, 50f)
            Quality.ULTRA -> RenderConfig(0.75f, 128, 0.0005f, 60f)
        }
        "heavy" -> when (quality) {
            Quality.DRAFT -> RenderConfig(0.20f, 48, 0.003f, 30f)
            Quality.BALANCED -> RenderConfig(0.33f, 64, 0.001f, 40f)
            Quality.ULTRA -> RenderConfig(0.50f, 128, 0.0005f, 50f)
        }
        else -> when (quality) { // medium
            Quality.DRAFT -> RenderConfig(0.25f, 64, 0.002f, 40f)
            Quality.BALANCED -> RenderConfig(0.40f, 80, 0.001f, 50f)
            Quality.ULTRA -> RenderConfig(0.65f, 128, 0.0005f, 60f)
        }
    }
    return if (animating) cfg.copy(scale = cfg.scale * 0.75f) else cfg
}

// ── Vec3 helpers (zero-alloc via out param) ──────────────────────────────
fun vec3Normalize(out: FloatArray, x: Float, y: Float, z: Float) {
    val len = sqrt(x * x + y * y + z * z).let { if (it == 0f) 1f else it }
    out[0] = x / len; out[1] = y / len; out[2] = z / len
}

fun vec3Dot(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
    return ax * bx + ay * by + az * bz
}

fun vec3Cross(out: FloatArray, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) {
    out[0] = ay * bz - az * by
    out[1] = az * bx - ax * bz
    out[2] = ax * by - ay * bx
}

fun vec3Length(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

// ── Rotation (out-param) ────────────────────────────────────────────────
fun rotateX(out: FloatArray, px: Float, py: Float, pz: Float, c: Float, s: Float) {
    out[0] = px; out[1] = py * c - pz * s; out[2] = py * s + pz * c
}

fun rotateY(out: FloatArray, px: Float, py: Float, pz: Float, c: Float, s: Float) {
    out[0] = px * c + pz * s; out[1] = py; out[2] = -px * s + pz * c
}

fun rotateZ(out: FloatArray, px: Float, py: Float, pz: Float, c: Float, s: Float) {
    out[0] = px * c - py * s; out[1] = px * s + py * c; out[2] = pz
}

// ── Camera ──────────────────────────────────────────────────────────────
fun buildCamera(
    roX: Float, roY: Float, roZ: Float,
    tgtX: Float, tgtY: Float, tgtZ: Float,
    fovDeg: Float, aspect: Float
): CameraBasis {
    val fx = tgtX - roX; val fy = tgtY - roY; val fz = tgtZ - roZ
    val fLen = sqrt(fx * fx + fy * fy + fz * fz).let { if (it == 0f) 1f else it }
    val fwdX = fx / fLen; val fwdY = fy / fLen; val fwdZ = fz / fLen

    // right = forward × (0,1,0)
    var rx = fwdY * 0f - fwdZ * 1f
    var ry = fwdZ * 0f - fwdX * 0f
    var rz = fwdX * 1f - fwdY * 0f
    // Actually: forward × up = (fwdY*0 - fwdZ*1, fwdZ*0 - fwdX*0, fwdX*1 - fwdY*0)
    // Correct cross: forward × (0,1,0) = (fz, 0, -fx) ... let me redo
    // cross(fwd, worldUp) where worldUp=(0,1,0)
    rx = fwdZ  // fwdY*0 - fwdZ*0 ... no
    // a×b = (ay*bz - az*by, az*bx - ax*bz, ax*by - ay*bx)
    // fwd×(0,1,0) = (fwdY*0 - fwdZ*1, fwdZ*0 - fwdX*0, fwdX*1 - fwdY*0)
    rx = -fwdZ; ry = 0f; rz = fwdX
    val rLen = sqrt(rx * rx + ry * ry + rz * rz).let { if (it == 0f) 1f else it }
    rx /= rLen; ry /= rLen; rz /= rLen

    // up = right × forward
    val ux = ry * fwdZ - rz * fwdY
    val uy = rz * fwdX - rx * fwdZ
    val uz = rx * fwdY - ry * fwdX

    val fovFactor = tan(fovDeg * PI.toFloat() / 360f)

    return CameraBasis(
        roX, roY, roZ,
        fwdX, fwdY, fwdZ,
        rx * aspect, ry * aspect, rz * aspect,
        ux, uy, uz,
        fovFactor
    )
}

fun getRayDir(out: FloatArray, u: Float, v: Float, cam: CameraBasis) {
    val f = cam.fovFactor
    val dx = cam.fwdX + u * f * cam.rightX + v * f * cam.upX
    val dy = cam.fwdY + u * f * cam.rightY + v * f * cam.upY
    val dz = cam.fwdZ + u * f * cam.rightZ + v * f * cam.upZ
    val len = sqrt(dx * dx + dy * dy + dz * dz).let { if (it == 0f) 1f else it }
    out[0] = dx / len; out[1] = dy / len; out[2] = dz / len
}

fun cameraFromParams(camDist: Float, camAngle: Float, camHeight: Float, out: FloatArray) {
    val angle = camAngle * PI.toFloat() / 180f
    out[0] = sin(angle) * camDist
    out[1] = camHeight
    out[2] = cos(angle) * camDist
}

fun lightDirFromParams(lightAngle: Float, lightHeight: Float, out: FloatArray) {
    val angle = lightAngle * PI.toFloat() / 180f
    val x = sin(angle); val z = cos(angle)
    val len = sqrt(x * x + lightHeight * lightHeight + z * z).let { if (it == 0f) 1f else it }
    out[0] = x / len; out[1] = lightHeight / len; out[2] = z / len
}

// ── Ray March ───────────────────────────────────────────────────────────
fun rayMarch(
    result: MarchResult,
    rox: Float, roy: Float, roz: Float,
    rdx: Float, rdy: Float, rdz: Float,
    sdf: SceneSDF,
    maxSteps: Int, epsilon: Float, maxDist: Float
) {
    var t = 0f
    var i = 0
    while (i < maxSteps) {
        val px = rox + rdx * t
        val py = roy + rdy * t
        val pz = roz + rdz * t
        val d = sdf(px, py, pz)
        if (d < epsilon) {
            result.hit = true
            result.t = t
            result.steps = i
            result.px = px; result.py = py; result.pz = pz
            return
        }
        t += d
        if (t > maxDist) break
        i++
    }
    result.hit = false
    result.t = t
    result.steps = i
    result.px = rox + rdx * t
    result.py = roy + rdy * t
    result.pz = roz + rdz * t
}

// ── Surface Normal (central difference, 6-tap) ─────────────────────────
fun calcNormal(out: FloatArray, px: Float, py: Float, pz: Float, sdf: SceneSDF, eps: Float) {
    val nx = sdf(px + eps, py, pz) - sdf(px - eps, py, pz)
    val ny = sdf(px, py + eps, pz) - sdf(px, py - eps, pz)
    val nz = sdf(px, py, pz + eps) - sdf(px, py, pz - eps)
    val len = sqrt(nx * nx + ny * ny + nz * nz).let { if (it == 0f) 1f else it }
    out[0] = nx / len; out[1] = ny / len; out[2] = nz / len
}

// ── SDF Primitives ──────────────────────────────────────────────────────
fun sdSphere(px: Float, py: Float, pz: Float, r: Float): Float {
    return sqrt(px * px + py * py + pz * pz) - r
}

fun sdBox(px: Float, py: Float, pz: Float, bx: Float, by: Float, bz: Float): Float {
    val dx = abs(px) - bx; val dy = abs(py) - by; val dz = abs(pz) - bz
    val ex = maxOf(dx, 0f); val ey = maxOf(dy, 0f); val ez = maxOf(dz, 0f)
    return sqrt(ex * ex + ey * ey + ez * ez) + minOf(maxOf(dx, dy, dz), 0f)
}

fun sdRoundBox(px: Float, py: Float, pz: Float, bx: Float, by: Float, bz: Float, r: Float): Float {
    return sdBox(px, py, pz, bx - r, by - r, bz - r) - r
}

fun sdTorus(px: Float, py: Float, pz: Float, majorR: Float, minorR: Float): Float {
    val qx = sqrt(px * px + pz * pz) - majorR
    return sqrt(qx * qx + py * py) - minorR
}

fun sdCone(px: Float, py: Float, pz: Float, angle: Float, height: Float): Float {
    val sinA = sin(angle); val cosA = cos(angle)
    val q = sqrt(px * px + pz * pz)
    val tip = sinA * q + cosA * py
    if (tip < 0f) return sqrt(q * q + py * py)
    if (py > height) return sqrt(q * q + (py - height) * (py - height))
    return tip
}

fun sdCylinder(px: Float, py: Float, pz: Float, radius: Float, height: Float): Float {
    val d = sqrt(px * px + pz * pz) - radius
    val h = abs(py) - height
    return sqrt(maxOf(d, 0f).pow(2) + maxOf(h, 0f).pow(2)) + minOf(maxOf(d, h), 0f)
}

fun sdCapsule(
    px: Float, py: Float, pz: Float,
    ax: Float, ay: Float, az: Float,
    bx: Float, by: Float, bz: Float, r: Float
): Float {
    val abx = bx - ax; val aby = by - ay; val abz = bz - az
    val apx = px - ax; val apy = py - ay; val apz = pz - az
    val abLenSq = abx * abx + aby * aby + abz * abz
    var t = (apx * abx + apy * aby + apz * abz) / (if (abLenSq == 0f) 1f else abLenSq)
    t = t.coerceIn(0f, 1f)
    val cx = ax + t * abx - px; val cy = ay + t * aby - py; val cz = az + t * abz - pz
    return sqrt(cx * cx + cy * cy + cz * cz) - r
}

fun sdOctahedron(px: Float, py: Float, pz: Float, s: Float): Float {
    val apx = abs(px); val apy = abs(py); val apz = abs(pz)
    val m = apx + apy + apz - s
    val qx: Float; val qy: Float; val qz: Float
    if (3f * apx < m) { qx = apx; qy = apy; qz = apz }
    else if (3f * apy < m) { qx = apy; qy = apz; qz = apx }
    else if (3f * apz < m) { qx = apz; qy = apx; qz = apy }
    else return m * 0.57735027f
    val k = maxOf(0f, minOf(0.5f * (qz - qy + s), s))
    return sqrt(qx * qx + (qy - s + k) * (qy - s + k) + (qz - k) * (qz - k))
}

fun sdHexPrism(px: Float, py: Float, pz: Float, h: Float, r: Float): Float {
    val apx = abs(px); val apy = abs(py); val apz = abs(pz)
    val k = 0.8660254f
    val dx = apx - minOf(apx, r) * k * 2f
    val dy = apz - r
    val outside2d = sqrt(maxOf(dx, 0f).pow(2) + maxOf(dy, 0f).pow(2))
    val inside2d = minOf(maxOf(dx, dy), 0f)
    val d2d = outside2d + inside2d
    val dh = apy - h
    return sqrt(maxOf(d2d, 0f).pow(2) + maxOf(dh, 0f).pow(2)) + minOf(maxOf(d2d, dh), 0f)
}

fun sdPyramid(px: Float, py: Float, pz: Float, h: Float): Float {
    val apx = abs(px); val apz = abs(pz)
    val base = maxOf(apx, apz) - 1f
    val slope = py + maxOf(apx, apz) * (h / 1f) - h
    val d = maxOf(base, slope)
    return maxOf(d, -py)
}

// ── SDF Boolean Operations ──────────────────────────────────────────────
fun opUnion(a: Float, b: Float): Float = minOf(a, b)
fun opIntersect(a: Float, b: Float): Float = maxOf(a, b)
fun opSubtract(a: Float, b: Float): Float = maxOf(a, -b)

fun opSmoothUnion(a: Float, b: Float, k: Float): Float {
    val h = (0.5f + 0.5f * (b - a) / k).coerceIn(0f, 1f)
    return a * h + b * (1f - h) - k * h * (1f - h)
}

fun opSmoothIntersect(a: Float, b: Float, k: Float): Float {
    val h = (0.5f - 0.5f * (b - a) / k).coerceIn(0f, 1f)
    return a * h + b * (1f - h) + k * h * (1f - h)
}

fun opSmoothSubtract(a: Float, b: Float, k: Float): Float {
    val h = (0.5f - 0.5f * (a + b) / k).coerceIn(0f, 1f)
    return a * (1f - h) + (-b) * h + k * h * (1f - h)
}

// ── Domain Operations ───────────────────────────────────────────────────
fun opRepeat(p: Float, cellSize: Float): Float {
    return ((p % cellSize) + cellSize * 1.5f) % cellSize - cellSize * 0.5f
}

fun opTwist(out: FloatArray, px: Float, py: Float, pz: Float, rate: Float) {
    val a = py * rate; val c = cos(a); val s = sin(a)
    out[0] = px * c - pz * s; out[1] = py; out[2] = px * s + pz * c
}

fun opBend(out: FloatArray, px: Float, py: Float, pz: Float, rate: Float) {
    val a = px * rate; val c = cos(a); val s = sin(a)
    out[0] = px * c - py * s; out[1] = px * s + py * c; out[2] = pz
}

// ── Lighting ────────────────────────────────────────────────────────────
fun phongShade(
    nx: Float, ny: Float, nz: Float,
    vx: Float, vy: Float, vz: Float,
    lx: Float, ly: Float, lz: Float,
    ambient: Float, diffuse: Float, specular: Float, shininess: Float
): Float {
    val NdotL = maxOf(0f, nx * lx + ny * ly + nz * lz)
    // Half-vector
    val hx = lx + vx; val hy = ly + vy; val hz = lz + vz
    val hLen = sqrt(hx * hx + hy * hy + hz * hz).let { if (it == 0f) 1f else it }
    val NdotH = maxOf(0f, (nx * hx + ny * hy + nz * hz) / hLen)
    val spec = if (NdotL > 0f) NdotH.pow(shininess) else 0f
    return ambient + diffuse * NdotL + specular * spec
}

fun softShadow(
    px: Float, py: Float, pz: Float,
    lx: Float, ly: Float, lz: Float,
    sdf: SceneSDF, mint: Float, maxt: Float, k: Float
): Float {
    var res = 1f
    var t = mint
    for (i in 0 until 24) {
        if (t >= maxt) break
        val d = sdf(px + lx * t, py + ly * t, pz + lz * t)
        if (d < 0.001f) return 0f
        res = minOf(res, k * d / t)
        t += maxOf(d, 0.02f)
    }
    return res.coerceIn(0f, 1f)
}

fun ambientOcclusion(
    px: Float, py: Float, pz: Float,
    nx: Float, ny: Float, nz: Float,
    sdf: SceneSDF, steps: Int
): Float {
    var occ = 0f
    var scale = 1f
    for (i in 1..steps) {
        val t = 0.02f * i
        val d = sdf(px + nx * t, py + ny * t, pz + nz * t)
        occ += (t - d) * scale
        scale *= 0.75f
    }
    return maxOf(0f, 1f - 3f * occ)
}

// ── Fresnel/Refraction ──────────────────────────────────────────────────
fun fresnelSchlick(cosTheta: Float, ior: Float): Float {
    var r0 = (1f - ior) / (1f + ior)
    r0 *= r0
    return r0 + (1f - r0) * (1f - cosTheta).pow(5)
}

fun refractVec(out: FloatArray, ix: Float, iy: Float, iz: Float, nx: Float, ny: Float, nz: Float, eta: Float): Boolean {
    val NdotI = ix * nx + iy * ny + iz * nz
    val k = 1f - eta * eta * (1f - NdotI * NdotI)
    if (k < 0f) return false // total internal reflection
    val sq = sqrt(k)
    out[0] = eta * ix - (eta * NdotI + sq) * nx
    out[1] = eta * iy - (eta * NdotI + sq) * ny
    out[2] = eta * iz - (eta * NdotI + sq) * nz
    return true
}

fun reflectVec(out: FloatArray, ix: Float, iy: Float, iz: Float, nx: Float, ny: Float, nz: Float) {
    val d = 2f * (ix * nx + iy * ny + iz * nz)
    out[0] = ix - d * nx; out[1] = iy - d * ny; out[2] = iz - d * nz
}

// ── Tone Mapping (ACES filmic + gamma 2.2) ──────────────────────────────
fun toneMapACES(out: FloatArray, r: Float, g: Float, b: Float, exposure: Float) {
    val er = r * exposure; val eg = g * exposure; val eb = b * exposure
    val a = 2.51f; val c = 0.03f; val d = 2.43f; val e = 0.59f; val f = 0.14f
    val inv = 1f / 2.2f
    out[0] = ((er * (a * er + c)) / (er * (d * er + e) + f)).coerceIn(0f, 1f).pow(inv) * 255f
    out[1] = ((eg * (a * eg + c)) / (eg * (d * eg + e) + f)).coerceIn(0f, 1f).pow(inv) * 255f
    out[2] = ((eb * (a * eb + c)) / (eb * (d * eb + e) + f)).coerceIn(0f, 1f).pow(inv) * 255f
}

// ── Sky Gradient ────────────────────────────────────────────────────────
fun skyGradient(out: FloatArray, rdy: Float, topR: Float, topG: Float, topB: Float, botR: Float, botG: Float, botB: Float) {
    val t = rdy * 0.5f + 0.5f
    out[0] = botR + (topR - botR) * t
    out[1] = botG + (topG - botG) * t
    out[2] = botB + (topB - botB) * t
}

// ── Hash ────────────────────────────────────────────────────────────────
fun hash3(ix: Int, iy: Int, iz: Int): Float {
    var h = ix * 374761393 + iy * 668265263 + iz * 1274126177
    h = ((h xor (h ushr 13)) * 1103515245)
    return (h and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat()
}

// ── 3D Perlin Noise ─────────────────────────────────────────────────────
class Noise3D(seed: Int) {
    private val perm = IntArray(512)

    init {
        val base = IntArray(256) { it }
        var s = seed or 1
        for (i in 255 downTo 1) {
            s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
            val j = ((s.toLong() and 0xFFFFFFFFL) % (i + 1)).toInt()
            val tmp = base[i]; base[i] = base[j]; base[j] = tmp
        }
        for (i in 0 until 256) {
            perm[i] = base[i]; perm[i + 256] = base[i]
        }
    }

    fun noise3D(x: Float, y: Float, z: Float): Float {
        val xi = floor(x).toInt() and 255
        val yi = floor(y).toInt() and 255
        val zi = floor(z).toInt() and 255
        val xf = x - floor(x)
        val yf = y - floor(y)
        val zf = z - floor(z)
        val u = fade(xf); val v = fade(yf); val w = fade(zf)

        val A = perm[xi] + yi
        val AA = perm[A] + zi; val AB = perm[A + 1] + zi
        val B = perm[xi + 1] + yi
        val BA = perm[B] + zi; val BB = perm[B + 1] + zi

        return lerp(
            lerp(
                lerp(grad(perm[AA], xf, yf, zf), grad(perm[BA], xf - 1f, yf, zf), u),
                lerp(grad(perm[AB], xf, yf - 1f, zf), grad(perm[BB], xf - 1f, yf - 1f, zf), u), v
            ),
            lerp(
                lerp(grad(perm[AA + 1], xf, yf, zf - 1f), grad(perm[BA + 1], xf - 1f, yf, zf - 1f), u),
                lerp(grad(perm[AB + 1], xf, yf - 1f, zf - 1f), grad(perm[BB + 1], xf - 1f, yf - 1f, zf - 1f), u), v
            ), w
        )
    }

    fun fbm3D(x: Float, y: Float, z: Float, octaves: Int, lacunarity: Float = 2f, gain: Float = 0.5f): Float {
        var sum = 0f; var amp = 1f; var freq = 1f; var maxAmp = 0f
        for (i in 0 until octaves) {
            sum += noise3D(x * freq, y * freq, z * freq) * amp
            maxAmp += amp; amp *= gain; freq *= lacunarity
        }
        return sum / maxAmp
    }

    companion object {
        private val GRAD3 = intArrayOf(
            1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1, 0,
            1, 0, 1, -1, 0, 1, 1, 0, -1, -1, 0, -1,
            0, 1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1
        )

        private fun fade(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)
        private fun lerp(a: Float, b: Float, t: Float): Float = a + t * (b - a)
        private fun grad(hash: Int, gx: Float, gy: Float, gz: Float): Float {
            val h = (hash and 11) * 3
            return GRAD3[h] * gx + GRAD3[h + 1] * gy + GRAD3[h + 2] * gz
        }
    }
}

// ── Rendering helpers ───────────────────────────────────────────────────
private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)

fun renderToCanvas(
    canvas: Canvas, bitmap: Bitmap,
    renderW: Int, renderH: Int, pixels: IntArray
) {
    val w = bitmap.width; val h = bitmap.height
    if (renderW == w && renderH == h) {
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    } else {
        val sb = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
        sb.setPixels(pixels, 0, renderW, 0, 0, renderW, renderH)
        canvas.drawBitmap(sb, null, Rect(0, 0, w, h), filterPaint)
        sb.recycle()
    }
}

fun renderMultithreaded(
    renderW: Int, renderH: Int, pixels: IntArray,
    renderRow: (y0: Int, y1: Int, rd: FloatArray, normal: FloatArray, tm: FloatArray, mr: MarchResult) -> Unit
) {
    val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    val threads = Array(cores) { t ->
        Thread {
            val y0 = t * renderH / cores
            val y1 = (t + 1) * renderH / cores
            val rd = FloatArray(3)
            val normal = FloatArray(3)
            val tm = FloatArray(3)
            val mr = MarchResult()
            renderRow(y0, y1, rd, normal, tm, mr)
        }.also { it.start() }
    }
    threads.forEach { it.join() }
}

// ── Common parameter extraction helpers ─────────────────────────────────
fun extractFloat(params: Map<String, Any>, key: String, default: Float): Float =
    (params[key] as? Number)?.toFloat() ?: default

fun extractString(params: Map<String, Any>, key: String, default: String): String =
    (params[key] as? String) ?: default
