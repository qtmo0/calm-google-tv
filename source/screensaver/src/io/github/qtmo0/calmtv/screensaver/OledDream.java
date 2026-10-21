package io.github.qtmo0.calmtv.screensaver;

import android.content.Context;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.service.dreams.DreamService;
import android.util.Log;
import android.view.Choreographer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Calendar;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Screensaver: fireflies. Seven soft lights drift slowly through the middle of the screen,
 * glow up and fade out again, each in its own colour. Colourful in the morning and during the
 * day (5-17 h), warm pinks, purples and amber in the evening and at night (17-5 h), matching
 * the home screen's time-of-day phases.
 *
 * Drawn by a fragment shader into a 4K surface: every pixel's glow is computed in floating
 * point as a smooth Gaussian, and a tiny per-pixel noise is added before the 8-bit output so
 * the dark glow shows no rings (the Canvas version banded, and was drawn at the UI's 1080p).
 * Pure black stays pure black for the OLED.
 *
 * Motion follows the approved mockup (drawn on a 960x540 canvas): tempo 0.8, safe margin 22%
 * left/right and 24% top/bottom. About 30 fps, only while dreaming.
 */
public class OledDream extends DreamService {

    private static final String TAG = "OledDream";
    private GLSurfaceView glView;
    private FireflyRenderer renderer;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        // Render at the panel's own resolution; the UI itself is only 1080p.
        android.view.Display d = getWindowManager().getDefaultDisplay();
        android.view.Display.Mode mode = d.getMode();
        int w = Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight());
        int h = Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight());
        glView.getHolder().setFixedSize(w, h);
        renderer = new FireflyRenderer();
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setContentView(glView);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (glView != null) {
            glView.onResume();
            renderer.start(glView);
        }
    }

    @Override
    public void onDreamingStopped() {
        if (renderer != null) renderer.stop();
        if (glView != null) glView.onPause();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (renderer != null) renderer.stop();
        super.onDetachedFromWindow();
    }

    static final class FireflyRenderer implements GLSurfaceView.Renderer, Choreographer.FrameCallback {

        private static final float TEMPO = 0.8f;
        private static final float MARGIN_X = 0.22f, MARGIN_Y = 0.24f;
        private static final int FLIES = 7;
        private static final long FRAME_NS = 33_000_000L; // about 30 fps

        private static final float[] COLOURFUL = {0, 60, 120, 180, 240, 300};
        private static final float[] WARM = {330, 290, 345, 20, 35, 310};

        // Each firefly is one square just big enough for its halo (3.3 sigma, where the glow is
        // below a quarter of an 8-bit step), added onto the black with additive blending. Only
        // those squares are shaded; a full-screen pass over all seven ran at 4 fps in 4K.
        private static final float REACH = 3.3f;
        private static final String VERTEX =
                "attribute vec2 aCorner;\n" +
                "uniform vec4 uFly;\n" +      // x, y, halo sigma, core sigma (pixels, GL y-up)
                "uniform vec2 uRes;\n" +
                "varying highp vec2 vD;\n" +
                "void main() {\n" +
                "  vD = aCorner * (" + REACH + " * uFly.z);\n" +
                "  gl_Position = vec4((uFly.xy + vD) / uRes * 2.0 - 1.0, 0.0, 1.0);\n" +
                "}\n";

        // A wide halo and a small bright core, both Gaussians. Then dither against banding in the
        // near-black outer glow: triangular noise of up to +-1 8-bit step (two interleaved
        // gradient noises), moving every frame so the eye averages it into a smooth ramp. It
        // fades out below one step of light, so black stays black and no lone pixels sparkle.
        private static final String FRAGMENT =
                "precision highp float;\n" +
                "uniform vec4 uFly;\n" +
                "uniform vec4 uHalo;\n" +     // rgb, strength
                "uniform vec4 uCore;\n" +
                "uniform float uFrame;\n" +
                "varying highp vec2 vD;\n" +
                "void main() {\n" +
                "  float d2 = dot(vD, vD);\n" +
                "  vec3 c = uHalo.rgb * (uHalo.a * exp(-d2 / (2.0 * uFly.z * uFly.z)))\n" +
                "         + uCore.rgb * (uCore.a * exp(-d2 / (2.0 * uFly.w * uFly.w)));\n" +
                "  vec2 q = gl_FragCoord.xy + 5.588238 * uFrame;\n" +
                "  float n1 = fract(52.9829189 * fract(dot(q, vec2(0.06711056, 0.00583715))));\n" +
                "  float n2 = fract(52.9829189 * fract(dot(q + vec2(17.0, 31.0), vec2(0.06711056, 0.00583715))));\n" +
                "  float amount = clamp(max(c.r, max(c.g, c.b)) * 255.0, 0.0, 1.0);\n" +
                "  c += (n1 + n2 - 1.0) * amount / 255.0;\n" +
                "  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);\n" +
                "}\n";

        private final Random random = new Random();
        private final float[] x = new float[FLIES], y = new float[FLIES];
        private final float[] tx = new float[FLIES], ty = new float[FLIES];
        private final float[] age = new float[FLIES], life = new float[FLIES], radius = new float[FLIES];
        private final float[] uFly = new float[FLIES * 4], uHalo = new float[FLIES * 4], uCore = new float[FLIES * 4];
        private final float[] rgb = new float[3];

        private GLSurfaceView view;
        private volatile boolean running;
        private boolean started, warm;
        private long lastFrameNs, lastDrawNs, lastPhaseCheckNs;
        private float t, scale = 1f;
        private int width, height;

        private int program, aPos, locRes, locFly, locHalo, locCore, locFrame, frameNo;
        private FloatBuffer quad;
        private int framesDrawn;
        private long statsSinceNs;

        void start(GLSurfaceView v) {
            view = v;
            if (running) return;
            running = true;
            lastFrameNs = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }

        void stop() {
            running = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }

        // ---- timing (main thread) -------------------------------------------------------------

        @Override
        public void doFrame(long frameNs) {
            if (!running) return;
            if (lastFrameNs == 0) lastFrameNs = frameNs;
            if (frameNs - lastDrawNs >= FRAME_NS) {
                float dt = Math.min(0.1f, (frameNs - lastFrameNs) / 1e9f) * TEMPO;
                lastFrameNs = frameNs;
                lastDrawNs = frameNs;
                if (lastPhaseCheckNs == 0 || frameNs - lastPhaseCheckNs > 60_000_000_000L) {
                    lastPhaseCheckNs = frameNs;
                    warm = warmNow();
                }
                synchronized (this) { step(dt); }
                if (view != null) view.requestRender();
            }
            Choreographer.getInstance().postFrameCallback(this);
        }

        private static boolean warmNow() {
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            return h >= 17 || h < 5;
        }

        private float rnd(float a, float b) { return a + random.nextFloat() * (b - a); }

        private void place(int i, boolean firstTime) {
            x[i] = rnd(width * MARGIN_X, width * (1 - MARGIN_X));
            y[i] = rnd(height * MARGIN_Y, height * (1 - MARGIN_Y));
            tx[i] = rnd(width * MARGIN_X, width * (1 - MARGIN_X));
            ty[i] = rnd(height * MARGIN_Y, height * (1 - MARGIN_Y));
            life[i] = rnd(9f, 14f);
            age[i] = firstTime ? rnd(0f, 9f) : 0f; // staggered at the start, then each glows up from dark
            radius[i] = rnd(26f, 46f) * scale;
        }

        private void step(float dt) {
            if (width == 0) return;
            if (!started) {
                started = true;
                for (int i = 0; i < FLIES; i++) place(i, true);
            }
            t += dt;
            for (int i = 0; i < FLIES; i++) {
                age[i] += dt;
                if (age[i] > life[i]) { place(i, false); continue; }
                x[i] += (tx[i] - x[i]) * dt * 0.06f;
                y[i] += (ty[i] - y[i]) * dt * 0.06f + (float) Math.sin(t * 0.4f + i) * dt * 3f * scale;
            }
            float sat = warm ? 0.80f : 0.85f;
            for (int i = 0; i < FLIES; i++) {
                float a = (float) Math.sin(age[i] / life[i] * Math.PI);
                if (a < 0) a = 0;
                float h = hueAt(i + t * 0.03f);
                float r = radius[i];
                uFly[i * 4] = x[i];
                uFly[i * 4 + 1] = height - y[i]; // GL counts y from the bottom
                // The mockup's glows reach 3r (halo) and 0.5r (core) and are at 45% at 40% of that:
                // a Gaussian with sigma = 0.316 x reach matches those points, without any edge.
                uFly[i * 4 + 2] = 0.316f * 3f * r;
                uFly[i * 4 + 3] = 0.316f * 0.5f * r;
                hslToRgb(h, sat, 0.62f, rgb);
                uHalo[i * 4] = rgb[0]; uHalo[i * 4 + 1] = rgb[1]; uHalo[i * 4 + 2] = rgb[2];
                uHalo[i * 4 + 3] = 0.22f * a;
                hslToRgb(h, sat, 0.70f, rgb);
                uCore[i * 4] = rgb[0]; uCore[i * 4 + 1] = rgb[1]; uCore[i * 4 + 2] = rgb[2];
                uCore[i * 4 + 3] = 0.7f * a;
            }
        }

        /** Blends along the palette, the short way round the colour wheel. */
        private float hueAt(float index) {
            float[] p = warm ? WARM : COLOURFUL;
            int n = p.length;
            float f = ((index % n) + n) % n;
            int lo = (int) f;
            float a = p[lo], b = p[(lo + 1) % n], k = f - lo;
            float d = ((b - a + 540f) % 360f) - 180f;
            return ((a + d * k) % 360f + 360f) % 360f;
        }

        // ---- drawing (GL thread) --------------------------------------------------------------

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            program = link(VERTEX, FRAGMENT);
            aPos = GLES20.glGetAttribLocation(program, "aCorner");
            locRes = GLES20.glGetUniformLocation(program, "uRes");
            locFly = GLES20.glGetUniformLocation(program, "uFly");
            locHalo = GLES20.glGetUniformLocation(program, "uHalo");
            locCore = GLES20.glGetUniformLocation(program, "uCore");
            locFrame = GLES20.glGetUniformLocation(program, "uFrame");
            float[] q = {-1, -1, 1, -1, -1, 1, 1, 1};
            quad = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(q).position(0);
            GLES20.glClearColor(0, 0, 0, 1);
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int w, int h) {
            GLES20.glViewport(0, 0, w, h);
            synchronized (this) {
                width = w;
                height = h;
                scale = w / 960f;
                started = false;
            }
            Log.i(TAG, "surface " + w + "x" + h);
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (program == 0) return;
            GLES20.glUseProgram(program);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE); // overlapping light adds up
            GLES20.glUniform2f(locRes, width, height);
            GLES20.glUniform1f(locFrame, frameNo = (frameNo + 1) % 64); // new noise pattern every frame
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
            synchronized (this) {
                for (int i = 0; i < FLIES; i++) {
                    if (uHalo[i * 4 + 3] <= 0f) continue;
                    GLES20.glUniform4fv(locFly, 1, uFly, i * 4);
                    GLES20.glUniform4fv(locHalo, 1, uHalo, i * 4);
                    GLES20.glUniform4fv(locCore, 1, uCore, i * 4);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                }
            }

            // Frames per second in the log, to check the chip keeps up at this resolution.
            long now = System.nanoTime();
            if (statsSinceNs == 0) statsSinceNs = now;
            framesDrawn++;
            if (now - statsSinceNs >= 5_000_000_000L) {
                Log.i(TAG, String.format("fps %.1f at %dx%d", framesDrawn * 1e9 / (now - statsSinceNs), width, height));
                framesDrawn = 0;
                statsSinceNs = now;
            }
        }

        private static int link(String vs, String fs) {
            int v = compile(GLES20.GL_VERTEX_SHADER, vs), f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                Log.e(TAG, "link: " + GLES20.glGetProgramInfoLog(p));
                return 0;
            }
            return p;
        }

        private static int compile(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) Log.e(TAG, "compile: " + GLES20.glGetShaderInfoLog(s));
            return s;
        }

        private static void hslToRgb(float h, float s, float l, float[] out) {
            h = ((h % 360f) + 360f) % 360f / 360f;
            float q = l < 0.5f ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
            out[0] = hue2rgb(p, q, h + 1f / 3);
            out[1] = hue2rgb(p, q, h);
            out[2] = hue2rgb(p, q, h - 1f / 3);
        }

        private static float hue2rgb(float p, float q, float t) {
            if (t < 0) t += 1;
            if (t > 1) t -= 1;
            if (t < 1f / 6) return p + (q - p) * 6 * t;
            if (t < 1f / 2) return q;
            if (t < 2f / 3) return p + (q - p) * (2f / 3 - t) * 6;
            return p;
        }
    }
}
