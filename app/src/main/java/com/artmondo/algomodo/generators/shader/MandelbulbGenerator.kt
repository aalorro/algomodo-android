package com.artmondo.algomodo.generators.shader

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU ray-marched 3D fractal generator. Supports six distance estimators
 * (Mandelbulb, MandelbulbVar, Mandelbox, MengerSponge, Sierpinski3D,
 * KaleidoscopicIFS) with selectable orbit-trap colouring and proper
 * Phong + AO shading. Matches the parameter surface of the web version.
 *
 * Audio reactivity: bass nudges the bulb power, mids drift the colour
 * lookup along the palette, highs add fresnel rim glow.
 */
class MandelbulbGenerator : GpuGenerator {

    override val id: String = "shader-mandelbulb"
    override val family: String = "shader"
    override val styleName: String = "Mandelbulb"
    override val definition: String =
        "3D fractal rendering with multiple formulas and orbit trap coloring."
    override val algorithmNotes: String =
        "Six distance estimators in a single fragment-shader sphere-tracer: Mandelbulb " +
        "(spherical-coordinate power iteration), MandelbulbVar (sinusoidally-animated " +
        "power), Mandelbox (box+sphere folding), MengerSponge (recursive cross folding), " +
        "Sierpinski3D (tetrahedral kifs), KaleidoscopicIFS (sorted abs reflections). " +
        "Orbit-trap modes record minimum distance to a point/line/plane across iterations " +
        "to drive palette colouring. Surface lighting is Phong (ambient + diffuse + " +
        "specular) modulated by a multi-tap ambient occlusion term and a fresnel rim. " +
        "Audio bass nudges the bulb power, mids shift the colour, highs boost rim glow."
    override val supportsVector: Boolean = false
    override val supportsAnimation: Boolean = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Camera Dist", "cameraDistance", ParamGroup.COMPOSITION,
            "Distance of camera from origin", 1f, 12f, 0.5f, 5f),
        Parameter.NumberParam("FOV", "fov", ParamGroup.COMPOSITION,
            "Field of view in degrees", 20f, 120f, 5f, 60f),
        Parameter.NumberParam("Camera Angle", "cameraAngle", ParamGroup.COMPOSITION,
            "Horizontal camera orbit angle", 0f, 360f, 5f, 0f),
        Parameter.NumberParam("Camera Height", "cameraHeight", ParamGroup.COMPOSITION,
            "Vertical camera position", -2f, 3f, 0.1f, 0.5f),
        Parameter.NumberParam("Light Angle", "lightAngle", ParamGroup.TEXTURE,
            "Horizontal light direction", 0f, 360f, 5f, 45f),
        Parameter.NumberParam("Light Height", "lightHeight", ParamGroup.TEXTURE,
            "Vertical light direction", 0.1f, 2f, 0.1f, 0.8f),
        Parameter.NumberParam("Exposure", "exposure", ParamGroup.TEXTURE,
            "Tone mapping exposure", 0.5f, 3f, 0.1f, 1.2f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation speed", 0.1f, 2f, 0.1f, 0.5f),
        Parameter.SelectParam("Formula", "formula", ParamGroup.GEOMETRY,
            "3D fractal formula",
            listOf("Mandelbulb", "MandelbulbVar", "Mandelbox", "MengerSponge",
                "Sierpinski3D", "KaleidoscopicIFS"),
            "Mandelbulb"),
        Parameter.NumberParam("Power", "power", ParamGroup.GEOMETRY,
            "Mandelbulb power exponent (also fold scale for non-bulb formulas)",
            2f, 12f, 0.5f, 8f),
        Parameter.NumberParam("Iterations", "fractalIterations", ParamGroup.GEOMETRY,
            "Fractal iteration count", 4f, 20f, 1f, 10f),
        Parameter.SelectParam("Orbit Trap", "orbitTrap", ParamGroup.COLOR,
            "Orbit trap coloring mode",
            listOf("none", "point", "line", "plane"), "point"),
        Parameter.NumberParam("AO Strength", "aoStrength", ParamGroup.TEXTURE,
            "Ambient occlusion intensity", 0f, 1f, 0.05f, 0.6f),
        Parameter.NumberParam("Audio React", "audioReact", ParamGroup.FLOW_MOTION,
            "How strongly audio modulates the look", 0f, 1f, 0.05f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 5f,
        "fov" to 60f,
        "cameraAngle" to 0f,
        "cameraHeight" to 0.5f,
        "lightAngle" to 45f,
        "lightHeight" to 0.8f,
        "exposure" to 1.2f,
        "speed" to 0.5f,
        "formula" to "Mandelbulb",
        "power" to 8f,
        "fractalIterations" to 10f,
        "orbitTrap" to "point",
        "aoStrength" to 0.6f,
        "audioReact" to 0.5f
    )

    override fun bindUniforms(
        programId: Int,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float,
        width: Int,
        height: Int
    ) {
        val camDist = (params["cameraDistance"] as? Float) ?: 5f
        val fov = (params["fov"] as? Float) ?: 60f
        val camAngle = (params["cameraAngle"] as? Float) ?: 0f
        val camHeight = (params["cameraHeight"] as? Float) ?: 0.5f
        val lightAngle = (params["lightAngle"] as? Float) ?: 45f
        val lightHeight = (params["lightHeight"] as? Float) ?: 0.8f
        val exposure = (params["exposure"] as? Float) ?: 1.2f
        val speed = (params["speed"] as? Float) ?: 0.5f
        val formula = (params["formula"] as? String) ?: "Mandelbulb"
        val power = (params["power"] as? Float) ?: 8f
        val iterations = ((params["fractalIterations"] as? Float) ?: 10f).toInt().coerceIn(4, 20)
        val orbitTrap = (params["orbitTrap"] as? String) ?: "point"
        val aoStrength = (params["aoStrength"] as? Float) ?: 0.6f
        val audioReact = (params["audioReact"] as? Float) ?: 0.5f

        val formulaIdx = when (formula) {
            "MandelbulbVar" -> 1
            "Mandelbox" -> 2
            "MengerSponge" -> 3
            "Sierpinski3D" -> 4
            "KaleidoscopicIFS" -> 5
            else -> 0 // Mandelbulb
        }
        val trapIdx = when (orbitTrap) {
            "point" -> 1
            "line" -> 2
            "plane" -> 3
            else -> 0 // none
        }

        // Quality-driven raymarch step budget.
        val maxSteps = when (quality) {
            Quality.DRAFT -> 64
            Quality.BALANCED -> 96
            Quality.ULTRA -> 160
        }

        // Seed-driven phase offset so different seeds produce visually different
        // patterns without affecting parameter semantics.
        val phaseOffset = ((seed and 0xFFFF) / 65535f) * (2f * Math.PI.toFloat())

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCamDist"), camDist)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uFov"), fov)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCamAngle"), camAngle)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCamHeight"), camHeight)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLightAngle"), lightAngle)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLightHeight"), lightHeight)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uExposure"), exposure)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSpeed"), speed)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uFormula"), formulaIdx)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPower"), power)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uIterations"), iterations)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uOrbitTrap"), trapIdx)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAoStrength"), aoStrength)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAudioReact"), audioReact)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPhase"), phaseOffset)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMaxSteps"), maxSteps)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        uniform float uCamDist;
        uniform float uFov;
        uniform float uCamAngle;
        uniform float uCamHeight;
        uniform float uLightAngle;
        uniform float uLightHeight;
        uniform float uExposure;
        uniform float uSpeed;
        uniform int uFormula;
        uniform float uPower;
        uniform int uIterations;
        uniform int uOrbitTrap;
        uniform float uAoStrength;
        uniform float uAudioReact;
        uniform float uPhase;
        uniform int uMaxSteps;

        out vec4 fragColor;

        const float PI = 3.14159265359;

        // ---- Box SDF (used by Menger / Kaleidoscopic) --------------------
        float sdBox(vec3 p, vec3 b) {
            vec3 q = abs(p) - b;
            return min(max(q.x, max(q.y, q.z)), 0.0) + length(max(q, 0.0));
        }

        // ---- Fractal distance estimators (port from CPU versions) --------
        // Returns both a distance and a trap value (point/line/plane min) so
        // surface colouring can use the orbit trap.
        // out.x = distance, out.y = trap accumulator (smaller is "closer to trap")
        vec2 deMandelbulb(vec3 p, float power, int maxIter, int trapMode) {
            vec3 z = p;
            float dr = 1.0;
            float r = 0.0;
            float trap = 1e9;
            float iter = 0.0;
            for (int i = 0; i < 24; i++) {
                if (i >= maxIter) break;
                r = length(z);
                // Track orbit trap before escape.
                if (trapMode == 1) trap = min(trap, r);
                else if (trapMode == 2) trap = min(trap, length(z.xz));
                else if (trapMode == 3) trap = min(trap, abs(z.y));
                if (r > 2.0) { iter = float(i) + 1.0 - log2(log2(r)); break; }
                float theta = acos(z.z / r);
                float phi = atan(z.y, z.x);
                dr = pow(r, power - 1.0) * power * dr + 1.0;
                float zr = pow(r, power);
                theta *= power;
                phi *= power;
                z = zr * vec3(sin(theta) * cos(phi), sin(phi) * sin(theta), cos(theta)) + p;
                iter = float(i + 1);
            }
            float d = 0.5 * log(max(r, 1e-6)) * r / max(dr, 1e-6);
            // For "none" we use iter / maxIter as colour key; pack into trap.
            if (trapMode == 0) trap = iter / float(maxIter);
            return vec2(d, trap);
        }

        vec2 deMandelbox(vec3 p, int iters, float scale, int trapMode) {
            vec3 z = p;
            float dr = 1.0;
            float trap = 1e9;
            const float foldLimit = 1.0;
            const float fixedR2 = 1.0;
            const float minR2 = 0.25;
            for (int i = 0; i < 24; i++) {
                if (i >= iters) break;
                z = clamp(z, -foldLimit, foldLimit) * 2.0 - z;
                float r2 = dot(z, z);
                if (r2 < minR2) {
                    float t = fixedR2 / minR2;
                    z *= t; dr *= t;
                } else if (r2 < fixedR2) {
                    float t = fixedR2 / r2;
                    z *= t; dr *= t;
                }
                z = z * scale + p;
                dr = dr * abs(scale) + 1.0;
                if (trapMode == 1) trap = min(trap, length(z));
                else if (trapMode == 2) trap = min(trap, length(z.xz));
                else if (trapMode == 3) trap = min(trap, abs(z.y));
            }
            float d = length(z) / abs(dr);
            if (trapMode == 0) trap = clamp(length(z) * 0.2, 0.0, 1.0);
            return vec2(d, trap);
        }

        vec2 deMenger(vec3 p, int iters, float scale, int trapMode) {
            float d = sdBox(p, vec3(1.0));
            float s = 1.0;
            float trap = 1e9;
            for (int i = 0; i < 12; i++) {
                if (i >= iters) break;
                vec3 a = mod(p * s + 1.0, 2.0) - 1.0;
                s *= scale;
                vec3 r = 1.0 - 3.0 * abs(a);
                float crossD = max(r.x, max(r.y, r.z)) / s;
                d = max(d, -crossD);
                if (trapMode == 1) trap = min(trap, length(a));
                else if (trapMode == 2) trap = min(trap, length(a.xz));
                else if (trapMode == 3) trap = min(trap, abs(a.y));
            }
            if (trapMode == 0) trap = clamp(log2(s) / float(iters * 2), 0.0, 1.0);
            return vec2(d, trap);
        }

        vec2 deSierpinski(vec3 p, int iters, float scale, int trapMode) {
            float trap = 1e9;
            for (int i = 0; i < 12; i++) {
                if (i >= iters) break;
                if (p.x + p.y < 0.0) { float tmp = -p.y; p.y = -p.x; p.x = tmp; }
                if (p.x + p.z < 0.0) { float tmp = -p.z; p.z = -p.x; p.x = tmp; }
                if (p.y + p.z < 0.0) { float tmp = -p.z; p.z = -p.y; p.y = tmp; }
                p = p * scale - (scale - 1.0);
                if (trapMode == 1) trap = min(trap, length(p));
                else if (trapMode == 2) trap = min(trap, length(p.xz));
                else if (trapMode == 3) trap = min(trap, abs(p.y));
            }
            float d = (length(p) - 1.5) * pow(scale, -float(iters));
            if (trapMode == 0) trap = clamp(length(p) * 0.1, 0.0, 1.0);
            return vec2(d, trap);
        }

        vec2 deKaleidoscopic(vec3 p, int iters, float scale, int trapMode) {
            float trap = 1e9;
            for (int i = 0; i < 12; i++) {
                if (i >= iters) break;
                p = abs(p);
                if (p.x < p.y) { float tmp = p.x; p.x = p.y; p.y = tmp; }
                if (p.x < p.z) { float tmp = p.x; p.x = p.z; p.z = tmp; }
                if (p.y < p.z) { float tmp = p.y; p.y = p.z; p.z = tmp; }
                p = p * scale - (scale - 1.0) * 0.5;
                if (trapMode == 1) trap = min(trap, length(p));
                else if (trapMode == 2) trap = min(trap, length(p.xz));
                else if (trapMode == 3) trap = min(trap, abs(p.y));
            }
            float d = sdBox(p, vec3(0.5)) * pow(scale, -float(iters));
            if (trapMode == 0) trap = clamp(length(p) * 0.1, 0.0, 1.0);
            return vec2(d, trap);
        }

        // Dispatch — picks the right DE for the active formula and folds the
        // animated MandelbulbVar power into the bulb DE.
        vec2 fractalDE(vec3 p, float power, int iters, int trapMode) {
            if (uFormula == 1) {
                // MandelbulbVar: power oscillates with time.
                float varPower = power + sin(uTime * uSpeed + uPhase) * 2.0;
                return deMandelbulb(p, varPower, iters, trapMode);
            } else if (uFormula == 2) {
                return deMandelbox(p, iters, power, trapMode);
            } else if (uFormula == 3) {
                return deMenger(p, iters, max(power * 0.4, 1.5), trapMode);
            } else if (uFormula == 4) {
                return deSierpinski(p, iters, max(power * 0.25, 1.6), trapMode);
            } else if (uFormula == 5) {
                return deKaleidoscopic(p, iters, max(power * 0.25, 1.6), trapMode);
            }
            return deMandelbulb(p, power, iters, trapMode);
        }

        // 4-tap central-difference normal — distance only.
        vec3 calcNormal(vec3 p, float power, int iters) {
            const float h = 0.0015;
            const vec2 k = vec2(1.0, -1.0);
            return normalize(
                k.xyy * fractalDE(p + k.xyy * h, power, iters, 0).x +
                k.yyx * fractalDE(p + k.yyx * h, power, iters, 0).x +
                k.yxy * fractalDE(p + k.yxy * h, power, iters, 0).x +
                k.xxx * fractalDE(p + k.xxx * h, power, iters, 0).x
            );
        }

        // Multi-tap occlusion along the normal — matches the CPU AO sampling.
        float ambientOcclusion(vec3 p, vec3 n, float power, int iters) {
            float ao = 0.0;
            float scale = 0.5;
            for (int i = 1; i <= 5; i++) {
                float d = float(i) * 0.04;
                float sd = fractalDE(p + n * d, power, iters, 0).x;
                ao += (d - sd) * scale;
                scale *= 0.55;
            }
            return clamp(1.0 - uAoStrength * 8.0 * ao, 0.0, 1.0);
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;

            // Animated camera angle so the fractal slowly orbits.
            float t = uTime * uSpeed + uPhase;
            float camAng = (uCamAngle + t * 8.0) * PI / 180.0;
            vec3 ro = vec3(sin(camAng) * uCamDist, uCamHeight, cos(camAng) * uCamDist);

            vec3 fw = normalize(-ro);
            vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
            vec3 up = cross(fw, rt);
            float focal = 1.0 / tan(uFov * PI / 360.0);
            // Negate uv.y so on-screen up matches world up (compensates for the
            // GpuShaderRunner vertex-shader Y-flip).
            vec3 rd = normalize(uv.x * rt - uv.y * up + focal * fw);

            // Light direction in world space.
            float la = uLightAngle * PI / 180.0;
            vec3 ld = normalize(vec3(sin(la), uLightHeight, cos(la)));

            // Audio-modulated bulb power (kept subtle).
            float power = uPower + uAudio.x * 1.2 * uAudioReact;

            // Sphere-trace.
            float total = 0.0;
            bool hit = false;
            vec3 hitPos = vec3(0.0);
            float trapVal = 0.0;
            float minDist = 1e9;
            for (int s = 0; s < 256; s++) {
                if (s >= uMaxSteps) break;
                vec3 p = ro + rd * total;
                if (length(p) > 12.0) break;
                vec2 de = fractalDE(p, power, uIterations, uOrbitTrap);
                float d = de.x;
                trapVal = de.y;
                minDist = min(minDist, d);
                total += max(d, 0.0006);
                if (d < 0.001) { hit = true; hitPos = p; break; }
                if (total > 20.0) break;
            }

            vec3 col;
            if (hit) {
                vec3 n = calcNormal(hitPos, power, uIterations);
                vec3 viewDir = normalize(ro - hitPos);

                // Map trap value into a 0..1 palette index. For the orbit-trap
                // modes (1..3) the value is a "min radius" so we re-normalise;
                // for "none" (0) we passed iteration/maxIter already.
                float colorT;
                if (uOrbitTrap == 0) {
                    colorT = clamp(trapVal, 0.0, 1.0);
                } else {
                    colorT = clamp(trapVal * 0.6, 0.0, 1.0);
                }
                // Mid-band audio drifts the colour along the palette.
                colorT = clamp(colorT + 0.12 * uAudio.y * uAudioReact, 0.0, 1.0);
                vec3 base = palette_color(colorT);

                // Phong shading: ambient + diffuse + specular.
                float ndl = max(dot(n, ld), 0.0);
                vec3 halfV = normalize(ld + viewDir);
                float spec = pow(max(dot(n, halfV), 0.0), 40.0);

                // Ambient occlusion proxy from neighbouring DE samples.
                float ao = ambientOcclusion(hitPos, n, power, uIterations);

                // Fresnel rim boost driven by highs.
                float fresnel = pow(1.0 - max(dot(n, viewDir), 0.0), 3.0);
                vec3 rim = palette_color(0.9) * fresnel
                          * (0.4 + 0.8 * uAudio.z * uAudioReact);

                col = base * (0.2 + 0.7 * ndl) * ao + vec3(0.6) * spec * 0.5 + rim;
                col *= uExposure;
            } else {
                // Background: soft palette sky + halo near silhouette.
                float halo = exp(-20.0 * minDist);
                float skyT = clamp(0.5 + 0.5 * rd.y, 0.0, 1.0);
                vec3 sky = mix(palette_color(0.15) * 0.4,
                               palette_color(0.55) * 0.7, skyT);
                col = sky + palette_color(0.9) * halo * 0.6;
                col *= uExposure;
            }

            // Reinhard tone-map + gamma.
            col = col / (1.0 + col);
            col = pow(col, vec3(1.0 / 2.2));
            fragColor = vec4(col, 1.0);
        }
    """
}
