package com.heroflow.wallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Xfermode;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.service.wallpaper.WallpaperService;
import android.view.Choreographer;
import android.view.Display;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

/**
 * HeroFlow V6
 * - 硬件加速 Canvas + 独立渲染线程 + Choreographer(跟随屏幕刷新率)
 * - 所有动画按真实时间(dt)计算，不再依赖帧数
 * - 点击：轻微放大 + 快速炫光扫过(约 0.3 秒)
 * - 手指滑动：焦点、放大、光晕、光带全部连续跟随手指
 * - 陀螺仪：晃动手机来回切换人物（对角速度积分，不受握持姿势影响）
 * - 画面静止时停止绘制，不耗电
 */
public class HeroWallpaperService extends WallpaperService {

 static final int N = 5;
 static final int BG = 0xff030408;
 static final int[] COLORS = {0xff2dff9a, 0xffb65cff, 0xff35a7ff, 0xffff5ebc, 0xffff3d3d};
 static final int[] RES = {R.drawable.hero_1, R.drawable.hero_2, R.drawable.hero_3, R.drawable.hero_4, R.drawable.hero_5};

 // ---- 可调参数 ----
 static final float SWEEP_MS = 300f;        // 一次炫光扫过时长(越小越快)
 static final float BURST_MS = 260f;        // 点击瞬间的光爆时长
 static final float GYRO_BASE_GAIN = 0.25f; // 每转 1° 移动多少个人物位(再乘“灵敏度”)
 static final float GYRO_DEADZONE = 0.03f;  // rad/s，小于此值视为静止
 static final float K_TOUCH_FOCUS = 45f;    // 手指焦点跟随时间常数(ms)，越小越跟手
 static final float K_FREE_FOCUS = 100f;    // 陀螺仪焦点跟随时间常数(ms)

 @Override public Engine onCreateEngine() { return new HeroEngine(); }

 class HeroEngine extends Engine implements SensorEventListener, Choreographer.FrameCallback {

  // ---------- 线程 / 生命周期 ----------
  final Object lock = new Object();
  HandlerThread ht;
  Handler rh;                       // 渲染线程 Handler
  Choreographer chor;
  volatile boolean visible = false, surfaceOk = false;
  volatile int surfW = 0, surfH = 0;
  boolean framePending = false;
  long lastNanos = 0;

  // ---------- 传感器 ----------
  SensorManager sm;
  Sensor gyro;
  boolean gyroEnabled = true, gyroInvert = false;
  float gyroGain = GYRO_BASE_GAIN;
  float dirSign = -1f;              // 由屏幕旋转决定
  long lastSensorNs = 0;

  // ---------- 图像 / 画笔(只在渲染线程使用) ----------
  final Bitmap[] bmp = new Bitmap[N];
  final BitmapShader[] bShader = new BitmapShader[N];
  final Paint[] imgPaint = new Paint[N];
  final Paint[] bandPaint = new Paint[N];
  final Paint[] glowPaint = new Paint[N];
  final Paint[] fillPaint = new Paint[N];
  Paint whiteGlow;
  final Matrix m = new Matrix();
  int lW = 0, lH = 0;

  // ---------- 动画状态(只在渲染线程使用) ----------
  float focus = 2f, focusT = 2f;
  final float[] wid = new float[N];
  boolean touching = false;
  float touchAmt = 0f, pulse = 0f;
  float fx, fy, fxT, fyT;           // 手指位置(逻辑横屏坐标)，fx/fy 为平滑后
  final float[] sweepT = {-1, -1, -1, -1, -1};
  float burstT = -1f, burstX, burstY;
  int burstIdx = 0, lastActive = -1;

  // =====================================================================
  @Override public void onCreate(SurfaceHolder holder) {
   super.onCreate(holder);
   setTouchEventsEnabled(true);
   for (int i = 0; i < N; i++) wid[i] = .2f;
   sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
   gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
   ht = new HandlerThread("HeroRender", Process.THREAD_PRIORITY_DISPLAY);
   ht.start();
   rh = new Handler(ht.getLooper());
   rh.post(new Runnable() { public void run() { chor = Choreographer.getInstance(); initPaints(); } });
  }

  @Override public void onDestroy() {
   visible = false;
   sm.unregisterListener(this);
   if (ht != null) ht.quitSafely();
   super.onDestroy();
  }

  @Override public void onSurfaceCreated(SurfaceHolder holder) {
   super.onSurfaceCreated(holder);
   surfaceOk = true;
  }

  @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
   super.onSurfaceChanged(holder, format, w, h);
   surfW = w; surfH = h;
   rh.post(new Runnable() { public void run() { updateRotation(); requestFrame(); } });
  }

  @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
   synchronized (lock) { surfaceOk = false; }
   super.onSurfaceDestroyed(holder);
  }

  @Override public void onVisibilityChanged(final boolean v) {
   visible = v;
   rh.post(new Runnable() { public void run() {
    if (v) {
     loadPrefs(); updateRotation();
     lastNanos = 0; lastSensorNs = 0;
     sm.unregisterListener(HeroEngine.this);
     if (gyro != null && gyroEnabled) sm.registerListener(HeroEngine.this, gyro, SensorManager.SENSOR_DELAY_GAME, rh);
     requestFrame();
    } else {
     sm.unregisterListener(HeroEngine.this);
     touching = false;
    }
   }});
  }

  void loadPrefs() {
   SharedPreferences p = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
   gyroEnabled = p.getBoolean("gyro", true);
   gyroInvert = p.getBoolean("invert", false);
   int sens = p.getInt("sens", 50);                       // 0..100
   gyroGain = GYRO_BASE_GAIN * 0.2f * (float) Math.pow(25.0, sens / 100.0);   // 0.2x ~ 5x，指数变化，两端差别明显
  }

  // 画布 +x 方向对应设备 Y 轴的哪一侧：随屏幕旋转变化
  void updateRotation() {
   int rot = 0;
   try {
    DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
    Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
    if (d != null) rot = d.getRotation();
   } catch (Exception ignored) { }
   dirSign = (rot == 0 || rot == 1) ? -1f : 1f;
  }

  // =====================================================================
  //  画笔：全部预先创建，逐帧不再分配对象
  // =====================================================================
  void initPaints() {
   Xfermode scr = new PorterDuffXfermode(PorterDuff.Mode.SCREEN);
   for (int i = 0; i < N; i++) {
    int c = COLORS[i], b = COLORS[(i + 2) % N], a = COLORS[(i + 4) % N];
    // 光带：白色高亮核心，两侧是主色和邻近色，形成“炫彩”边缘
    int[] cols = {al(b, 0), al(b, 80), al(c, 205), 0xffffffff, al(c, 205), al(a, 80), al(a, 0)};
    float[] pos = {0f, .20f, .40f, .50f, .60f, .80f, 1f};
    bandPaint[i] = new Paint();
    bandPaint[i].setShader(new LinearGradient(0, -1, 0, 1, cols, pos, Shader.TileMode.CLAMP));
    bandPaint[i].setXfermode(scr);

    glowPaint[i] = new Paint();
    glowPaint[i].setShader(new RadialGradient(0, 0, 1,
      new int[]{al(c, 255), al(c, 110), al(c, 0)}, new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP));
    glowPaint[i].setXfermode(scr);

    fillPaint[i] = new Paint();
    fillPaint[i].setColor(c);
    fillPaint[i].setXfermode(scr);
   }
   whiteGlow = new Paint();
   whiteGlow.setShader(new RadialGradient(0, 0, 1,
     new int[]{0xffffffff, 0x66ffffff, 0x00ffffff}, new float[]{0f, .40f, 1f}, Shader.TileMode.CLAMP));
   whiteGlow.setXfermode(scr);
  }

  // =====================================================================
  //  图片：按屏幕尺寸预缩放，避免每帧缩放 1229x1536 大图
  // =====================================================================
  void rebuild(int W, int H) {
   lW = W; lH = H;
   int th = (int) Math.ceil(H * 1.2f);
   for (int i = 0; i < N; i++) {
    Bitmap old = bmp[i];
    bmp[i] = prep(RES[i], th);
    if (old != null && old != bmp[i]) old.recycle();
    bShader[i] = new BitmapShader(bmp[i], Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    imgPaint[i] = new Paint(Paint.FILTER_BITMAP_FLAG);
    imgPaint[i].setShader(bShader[i]);
   }
  }

  Bitmap prep(int id, int targetH) {
   BitmapFactory.Options o = new BitmapFactory.Options();
   o.inJustDecodeBounds = true;
   BitmapFactory.decodeResource(getResources(), id, o);
   int ss = 1;
   while (o.outHeight / (ss * 2) >= targetH) ss *= 2;
   BitmapFactory.Options o2 = new BitmapFactory.Options();
   o2.inSampleSize = ss;
   Bitmap b = BitmapFactory.decodeResource(getResources(), id, o2);
   if (b.getHeight() > targetH) {
    int tw = Math.round(b.getWidth() * (float) targetH / b.getHeight());
    Bitmap s = Bitmap.createScaledBitmap(b, tw, targetH, true);
    if (s != b) b.recycle();
    b = s;
   }
   return b;
  }

  // =====================================================================
  //  触摸(主线程收到 → 转到渲染线程处理)
  // =====================================================================
  @Override public void onTouchEvent(MotionEvent e) {
   final int a = e.getActionMasked();
   final float x = e.getX(), y = e.getY();
   rh.post(new Runnable() { public void run() { handleTouch(a, x, y); } });
  }

  void handleTouch(int a, float x, float y) {
   int sw = surfW, sh = surfH;
   if (sw <= 0 || sh <= 0) return;
   boolean portrait = sh > sw;
   float W = portrait ? sh : sw, H = portrait ? sw : sh;
   float lx = portrait ? y : x;
   float ly = portrait ? sw - x : y;
   lx = clamp(lx, 0, W - 1); ly = clamp(ly, 0, H);
   float f = clamp(lx / W * 5f - .5f, 0, 4);

   if (a == MotionEvent.ACTION_DOWN) {
    touching = true;
    fx = fxT = lx; fy = fyT = ly;
    focusT = f;
    int idx = clampI(Math.round(f), 0, 4);
    lastActive = idx;
    sweepT[idx] = 0f;                 // 点击：立刻触发炫光
    burstT = 0f; burstX = lx; burstY = ly; burstIdx = idx;
    pulse = 1f;                       // 点击：轻微放大
   } else if (a == MotionEvent.ACTION_MOVE) {
    if (!touching) { touching = true; fx = lx; fy = ly; }
    fxT = lx; fyT = ly;
    focusT = f;
   } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
    touching = false;
   } else return;
   requestFrame();
  }

  // =====================================================================
  //  陀螺仪：对绕设备 X 轴的角速度积分，得到“晃动量”来推动焦点
  //  (人物排列方向始终沿设备 Y 轴：竖屏时是上下方向，横屏时是左右方向)
  // =====================================================================
  @Override public void onSensorChanged(SensorEvent e) {
   long ts = e.timestamp;
   float dt = (ts - lastSensorNs) / 1e9f;
   lastSensorNs = ts;
   if (touching || dt <= 0f || dt > 0.2f) return;
   float wx = e.values[0];
   float aw = Math.abs(wx);
   if (aw < GYRO_DEADZONE) return;
   wx = Math.signum(wx) * (aw - GYRO_DEADZONE);
   float deg = (float) Math.toDegrees(wx * dt);
   float dir = gyroInvert ? -dirSign : dirSign;
   float nf = clamp(focusT - dir * gyroGain * deg, 0f, 4f);
   if (nf != focusT) { focusT = nf; requestFrame(); }
  }
  @Override public void onAccuracyChanged(Sensor s, int acc) { }

  // =====================================================================
  //  帧循环
  // =====================================================================
  void requestFrame() {
   if (!framePending && visible && chor != null) {
    framePending = true;
    chor.postFrameCallback(this);
   }
  }

  @Override public void doFrame(long nanos) {
   framePending = false;
   if (!visible) return;
   float dt = lastNanos == 0 ? 16.7f : (nanos - lastNanos) / 1e6f;
   lastNanos = nanos;
   dt = clamp(dt, 1f, 50f);
   boolean more = step(dt);
   draw();
   if (more) requestFrame(); else lastNanos = 0;
  }

  boolean step(float dt) {
   boolean more = false;

   // 焦点：手指按下时非常跟手，陀螺仪时稍柔和
   float kf = 1f - (float) Math.exp(-dt / (touching ? K_TOUCH_FOCUS : K_FREE_FOCUS));
   focus += (focusT - focus) * kf;
   if (Math.abs(focusT - focus) > .002f) more = true; else focus = focusT;

   // 触摸强度 / 点击脉冲 / 手指位置平滑
   float ta = touching ? 1f : 0f;
   touchAmt += (ta - touchAmt) * (1f - (float) Math.exp(-dt / (touching ? 70f : 180f)));
   if (Math.abs(ta - touchAmt) > .004f) more = true; else touchAmt = ta;
   pulse *= (float) Math.exp(-dt / 140f);
   if (pulse > .01f) more = true; else pulse = 0f;
   float kp = 1f - (float) Math.exp(-dt / 30f);
   fx += (fxT - fx) * kp; fy += (fyT - fy) * kp;
   if (touchAmt > 0f && (Math.abs(fxT - fx) > .5f || Math.abs(fyT - fy) > .5f)) more = true;

   // 人物栏宽度：焦点栏变宽，邻栏平滑让位
   float kw = 1f - (float) Math.exp(-dt / 90f);
   for (int i = 0; i < N; i++) {
    float e = Math.max(0f, 1f - Math.abs(i - focus));
    float wt = .19f + (.05f + .02f * touchAmt) * e;
    float d = wt - wid[i];
    if (Math.abs(d) > .0004f) { wid[i] += d * kw; more = true; } else wid[i] = wt;
   }

   // 切换到新人物：立刻触发一次炫光
   int act = clampI(Math.round(focus), 0, 4);
   if (act != lastActive) {
    if (lastActive != -1) sweepT[act] = 0f;
    lastActive = act;
   }

   // 炫光 / 光爆计时
   for (int i = 0; i < N; i++) {
    if (sweepT[i] >= 0f) {
     sweepT[i] += dt;
     if (sweepT[i] >= SWEEP_MS) sweepT[i] = -1f; else more = true;
    }
   }
   if (burstT >= 0f) {
    burstT += dt;
    if (burstT >= BURST_MS) burstT = -1f; else more = true;
   }
   return more;
  }

  // =====================================================================
  //  绘制
  // =====================================================================
  void draw() {
   synchronized (lock) {
    if (!surfaceOk) return;
    SurfaceHolder h = getSurfaceHolder();
    Canvas c = null;
    try {
     c = h.lockHardwareCanvas();
    } catch (Exception ex) {
     try { c = h.lockCanvas(); } catch (Exception ex2) { return; }
    }
    if (c == null) return;
    try {
     render(c);
    } finally {
     try { h.unlockCanvasAndPost(c); } catch (Exception ignored) { }
    }
   }
  }

  void render(Canvas c) {
   int cw = c.getWidth(), chh = c.getHeight();
   boolean portrait = chh > cw;
   int W = portrait ? chh : cw, H = portrait ? cw : chh;
   if (W != lW || H != lH) rebuild(W, H);

   c.drawColor(BG);
   c.save();
   if (portrait) { c.rotate(90); c.translate(0, -cw); }

   float total = 0f;
   for (int i = 0; i < N; i++) total += wid[i];
   float acc = 0f; int prev = 0;
   for (int i = 0; i < N; i++) {
    acc += wid[i];
    int next = (i == N - 1) ? W : Math.round(W * acc / total);   // 边界取整，避免缝隙
    drawStrip(c, i, prev, next - prev, H);
    prev = next;
   }
   c.restore();
  }

  void drawStrip(Canvas c, int i, int x, int w, int H) {
   if (w <= 0) return;
   float prox = Math.max(0f, 1f - Math.abs(i - focus));

   // ---- 放大：焦点轻放大，按住更大一点，点击瞬间再弹一下 ----
   float zoom = 1f + prox * (.05f + .05f * touchAmt + .045f * pulse);
   Bitmap b = bmp[i];
   float PW = b.getWidth(), PH = b.getHeight();
   float da = (float) w / H;
   float sH = PH * .94f, sW = sH * da;
   if (sW > PW) { sW = PW; sH = sW / da; }
   sW /= zoom; sH /= zoom;

   // ---- 画面朝手指方向平移：放大的“中心”跟着手指走 ----
   float fxn = clamp((fx - x) / w, 0f, 1f) * 2f - 1f;
   float fyn = clamp(fy / H, 0f, 1f) * 2f - 1f;
   float pan = touchAmt * prox;
   float nx = clamp(fxn * pan * .18f + clamp(focus - i, -1f, 1f) * .06f, -1f, 1f);
   float ny = clamp(fyn * pan * .30f, -1f, 1f);
   float cx = PW / 2f + (PW - sW) / 2f * nx;
   float cy = PH / 2f + (PH - sH) / 2f * ny;
   float s = w / sW;
   m.setScale(s, s);
   m.postTranslate(x - (cx - sW / 2f) * s, -(cy - sH / 2f) * s);
   bShader[i].setLocalMatrix(m);
   imgPaint[i].setAlpha((int) (160 + 95 * prox));
   c.drawRect(x, 0, x + w, H, imgPaint[i]);

   // ---- 特效 ----
   boolean sweeping = sweepT[i] >= 0f;
   boolean fingerFx = touchAmt > .01f && prox > .02f;
   boolean burst = burstT >= 0f && burstIdx == i;
   if (!sweeping && !fingerFx && !burst) return;

   c.save();
   c.clipRect(x, 0, x + w, H);

   if (fingerFx) {
    float a = touchAmt * prox;
    float r = w * 1.15f;
    glowPaint[i].setAlpha(a255(200 * a));
    c.save(); c.translate(fx, fy); c.scale(r, r); c.drawCircle(0, 0, 1, glowPaint[i]); c.restore();
    whiteGlow.setAlpha(a255(140 * a));
    c.save(); c.translate(fx, fy); c.scale(r * .38f, r * .38f); c.drawCircle(0, 0, 1, whiteGlow); c.restore();
    drawBand(c, i, x, w, fy, .07f, 105f * a);       // 跟随手指的光带
   }

   if (sweeping) {
    float t = sweepT[i] / SWEEP_MS;
    float e = 1f - (1f - t) * (1f - t) * (1f - t);   // ease-out：先快后缓
    float cyb = H * (1.25f - e * 1.5f);              // 由下往上快速扫过
    float fade = (1f - t * t * t) * Math.min(1f, t * 12f);
    fillPaint[i].setAlpha(a255(85f * (1f - t) * (1f - t)));   // 整栏瞬间提亮
    c.drawRect(x, 0, x + w, H, fillPaint[i]);
    drawBand(c, i, x, w, cyb, .12f, 255f * fade);
   }

   if (burst) {
    float u = burstT / BURST_MS;
    float r = w * (.5f + 1.3f * (1f - (1f - u) * (1f - u)));
    whiteGlow.setAlpha(a255(230f * (1f - u) * (float) Math.sqrt(1f - u)));
    c.save(); c.translate(burstX, burstY); c.scale(r, r); c.drawCircle(0, 0, 1, whiteGlow); c.restore();
   }
   c.restore();
  }

  void drawBand(Canvas c, int i, int x, int w, float cy, float thick, float alpha) {
   if (alpha < 1f) return;
   Paint p = bandPaint[i];
   p.setAlpha(a255(alpha));
   c.save();
   c.translate(x + w * .5f, cy);
   c.rotate(-16f);
   c.scale(w * .85f, lH * thick);
   c.drawRect(-1f, -1f, 1f, 1f, p);
   c.restore();
  }
 }

 // ---------------------------------------------------------------------
 static int al(int color, int a) { return (color & 0x00ffffff) | (a << 24); }
 static int a255(float v) { return v < 0f ? 0 : (v > 255f ? 255 : (int) v); }
 static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
 static int clampI(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
