package com.heroflow.wallpaper;

import android.app.*;
import android.os.Handler;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;
import android.graphics.*;
import android.graphics.drawable.*;
import android.hardware.*;
import android.content.*;
import java.util.*;

public class HeroWallpaperService extends WallpaperService {
 @Override public Engine onCreateEngine(){ return new HeroEngine(); }

 class HeroEngine extends Engine implements SensorEventListener {
  final Handler h=new Handler();
  final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
  final Paint glow=new Paint(Paint.ANTI_ALIAS_FLAG);
  Bitmap[] imgs=new Bitmap[5];
  int[] colors={0xff2dff9a,0xffb65cff,0xff35a7ff,0xffff5ebc,0xffff3d3d};
  SensorManager sm; Sensor sensor;
  boolean visible=false; float tilt=0f, targetTilt=0f, phase=0f; int active=2; long touchLockUntil=0L; boolean touching=false; float touchX=-1f; float selectFlash=0f;
  final Runnable drawTask=new Runnable(){ public void run(){ drawFrame(); if(visible) h.postDelayed(this,33); }};

  HeroEngine(){
   imgs[0]=load(com.heroflow.wallpaper.R.drawable.hero_1); imgs[1]=load(R.drawable.hero_2);
   imgs[2]=load(R.drawable.hero_3); imgs[3]=load(R.drawable.hero_4); imgs[4]=load(R.drawable.hero_5);
   sm=(SensorManager)getSystemService(SENSOR_SERVICE);
   sensor=sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
   if(sensor==null) sensor=sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
  }
  Bitmap load(int id){ return BitmapFactory.decodeResource(getResources(),id); }

  @Override public void onCreate(SurfaceHolder holder){
   super.onCreate(holder);
   setTouchEventsEnabled(true);
  }

  @Override public void onTouchEvent(android.view.MotionEvent event){
   int action=event.getActionMasked();
   int sw=Math.max(1,getSurfaceHolder().getSurfaceFrame().width());
   int sh=Math.max(1,getSurfaceHolder().getSurfaceFrame().height());
   float landscapeX=(sh>sw)?event.getY():event.getX();
   float landscapeW=(sh>sw)?sh:sw;

   if(action==android.view.MotionEvent.ACTION_DOWN || action==android.view.MotionEvent.ACTION_MOVE){
     touching=true;
     touchX=Math.max(0f,Math.min(landscapeW-1f,landscapeX));
     int idx=Math.max(0,Math.min(4,(int)(touchX/(landscapeW/5f))));
     if(idx!=active){ active=idx; selectFlash=1f; }
     else if(action==android.view.MotionEvent.ACTION_DOWN) selectFlash=1f;

     // 手指在五位成员上连续滑动时，焦点和流光实时追随。
     targetTilt=-42f + (touchX/landscapeW)*84f;
     tilt=targetTilt;
     touchLockUntil=android.os.SystemClock.uptimeMillis()+650L;
     drawFrame();
   } else if(action==android.view.MotionEvent.ACTION_UP || action==android.view.MotionEvent.ACTION_CANCEL){
     touching=false;
     touchLockUntil=android.os.SystemClock.uptimeMillis()+450L;
   }
  }

  @Override public void onVisibilityChanged(boolean v){
   visible=v; h.removeCallbacks(drawTask);
   if(v){ if(sensor!=null) sm.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME); h.post(drawTask); }
   else sm.unregisterListener(this);
  }
  @Override public void onSurfaceDestroyed(SurfaceHolder holder){ super.onSurfaceDestroyed(holder); visible=false; h.removeCallbacks(drawTask); sm.unregisterListener(this); }
  @Override public void onSensorChanged(SensorEvent e){
   if(e.sensor.getType()==Sensor.TYPE_ROTATION_VECTOR){
    float[] r=new float[9], o=new float[3]; SensorManager.getRotationMatrixFromVector(r,e.values); SensorManager.getOrientation(r,o);
    targetTilt=(float)Math.toDegrees(o[2])*1.42f;
   } else targetTilt += e.values[1]*2.65f;
   targetTilt=Math.max(-42,Math.min(42,targetTilt));
  }
  @Override public void onAccuracyChanged(Sensor s,int a){}

  void drawFrame(){
   SurfaceHolder sh=getSurfaceHolder(); Canvas c=null;
   try{
    c=sh.lockCanvas(); if(c==null)return;
    int surfaceW=c.getWidth(), surfaceH=c.getHeight();
    c.drawColor(Color.rgb(2,3,7));

    // 强制“横屏作品”逻辑：
    // 如果系统壁纸 Surface 是竖屏，就把整张画布旋转 90°，
    // 这样用户把手机横过来时，五人会像 HTML 示例一样横向铺满。
    boolean portraitSurface = surfaceH > surfaceW;
    int W = portraitSurface ? surfaceH : surfaceW;
    int H = portraitSurface ? surfaceW : surfaceH;

    c.save();
    if(portraitSurface){
      c.rotate(90f);
      c.translate(0f, -surfaceW);
    }

    tilt += (targetTilt-tilt)*.28f;
    if(android.os.SystemClock.uptimeMillis()>=touchLockUntil) active=Math.max(0,Math.min(4,(int)(((tilt+42f)/84f)*5f)));
    phase+=0.070f; selectFlash*=0.78f;

    // 更接近 HTML 英雄选择：当前成员占约 46%，其他四人均分剩余空间。
    float focusPos;
    if(touching && touchX>=0f) focusPos=(touchX/W)*5f-.5f;
    else focusPos=((tilt+42f)/84f)*5f-.5f;
    focusPos=Math.max(0f,Math.min(4f,focusPos));

    // 宽度连续插值：最大仍约 24%，其余接近 19%，跨成员时不会突然跳变。
    float[] weights=new float[5]; float total=0f;
    for(int i=0;i<5;i++){
      float d=Math.abs(i-focusPos);
      float emphasis=Math.max(0f,1f-d);
      weights[i]=.19f+.05f*emphasis;
      total+=weights[i];
    }
    float x=0f;
    for(int i=0;i<5;i++){
      float w=W*(weights[i]/total);
      drawHero(c,imgs[i],x,0,w,H,i,i==active);
      x+=w;
    }

    // HUD 扫描线
    p.setColor(0x14ffffff); p.setStrokeWidth(1);
    for(int y=0;y<H;y+=6)c.drawLine(0,y,W,y,p);

    // 上下电影感渐暗，突出横屏构图
    LinearGradient vignetteTop=new LinearGradient(0,0,0,H*.22f,0x8a000000,0x00000000,Shader.TileMode.CLAMP);
    glow.setShader(vignetteTop); c.drawRect(0,0,W,H*.25f,glow);
    LinearGradient vignetteBottom=new LinearGradient(0,H*.72f,0,H,0x00000000,0x9b000000,Shader.TileMode.CLAMP);
    glow.setShader(vignetteBottom); c.drawRect(0,H*.68f,W,H,glow);
    glow.setShader(null);

    c.restore();
   } finally { if(c!=null) sh.unlockCanvasAndPost(c); }
  }

  void drawHero(Canvas c, Bitmap b, float x,float y,float w,float h,int idx,boolean on){
   float scale=on?1.12f:1.015f;
   float dw=w*scale, dh=h*scale;
   Rect src=new Rect(0,0,b.getWidth(),b.getHeight());
   float srcAspect=(float)b.getWidth()/b.getHeight(), dstAspect=dw/dh;
   if(srcAspect>dstAspect){ int nw=(int)(b.getHeight()*dstAspect); int left=(b.getWidth()-nw)/2; src.set(left,0,left+nw,b.getHeight()); }
   else { int nh=(int)(b.getWidth()/dstAspect); int top=(b.getHeight()-nh)/2; src.set(0,top,b.getWidth(),top+nh); }
   float shift=on?tilt*0.32f:0;
   RectF dst=new RectF(x-(dw-w)/2+shift,y-(dh-h)/2,dstX(x,w,dw,shift),y-(dh-h)/2+dh);
   p.setAlpha(on?255:155); c.drawBitmap(b,src,dst,p);

   if(on){
    c.save(); c.clipRect(x,0,x+w,h);

    // 切换瞬间：一束高亮炫彩从下往上快速扫过，约数百毫秒衰减。
    if(selectFlash>.035f){
      float fy=h*(1.10f-selectFlash*1.15f);
      LinearGradient flashGrad=new LinearGradient(x,fy+h*.18f,x+w,fy-h*.18f,
        new int[]{0x00ffffff,withAlpha(colors[idx],0x88),0xeeffffff,withAlpha(colors[idx],0x99),0x00ffffff},
        null,Shader.TileMode.CLAMP);
      glow.setShader(flashGrad); glow.setBlendMode(BlendMode.SCREEN);
      c.drawRect(x-w*.15f,fy-h*.22f,x+w*1.15f,fy+h*.22f,glow);
      glow.setShader(null); glow.setBlendMode(null);
    }
    float sx=x+w*(.5f+tilt/115f), sy=h*(.40f+(float)Math.sin(phase*.7f)*.06f);
    RadialGradient rg=new RadialGradient(sx,sy,Math.max(w,h)*.42f,
      new int[]{0xb8ffffff,withAlpha(colors[idx],0x92),0x00222222},new float[]{0,.22f,1},Shader.TileMode.CLAMP);
    glow.setShader(rg); glow.setBlendMode(BlendMode.SCREEN); c.drawRect(x,0,x+w,h,glow);
    float band=(float)((Math.sin(phase)+1)*.5)*(h*1.25f)-h*.12f;
    LinearGradient lg=new LinearGradient(x,band-h*.20f,x+w,band+h*.20f,
      new int[]{0x00ffffff,withAlpha(colors[idx],0x55),0xd8ffffff,withAlpha(colors[idx],0x55),0x00ffffff},
      null,Shader.TileMode.CLAMP);
    glow.setShader(lg); c.rotate(-10,x+w/2,h/2); c.drawRect(x-w*.25f,band-h*.16f,x+w*1.25f,band+h*.16f,glow);
    glow.setShader(null); glow.setBlendMode(null); c.restore();
   } else {
    p.setColor(0x48000000); c.drawRect(x,0,x+w,h,p);
   }
  }
  float dstX(float x,float w,float dw,float shift){return x-(dw-w)/2+shift+dw;}
  int withAlpha(int color,int a){return (color&0x00ffffff)|(a<<24);}
 }
}