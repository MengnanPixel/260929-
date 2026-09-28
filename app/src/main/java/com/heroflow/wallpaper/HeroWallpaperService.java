package com.heroflow.wallpaper;
import android.os.SystemClock;
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
  final Paint bitmapPaint=new Paint(Paint.FILTER_BITMAP_FLAG);
  final Paint fxPaint=new Paint();
  final Paint dimPaint=new Paint();
  final Rect src=new Rect();
  final RectF dst=new RectF();
  final float[] widths=new float[5], targetWidths=new float[5];
  Bitmap[] imgs=new Bitmap[5];
  int[] colors={0xff2dff9a,0xffb65cff,0xff35a7ff,0xffff5ebc,0xffff3d3d};

  SensorManager sm; Sensor sensor;
  boolean visible=false,touching=false;
  float tilt=0,targetTilt=0,focus=2,targetFocus=2,touchX=-1,phase=0,flash=0;
  int active=2,lastActive=2;
  long lastFrame=0;

  // 约 30fps；不在触摸时不额外重复 drawFrame，避免 V4 的重复绘制造成卡顿。
  final Runnable drawTask=new Runnable(){ public void run(){
    if(!visible)return;
    long now=SystemClock.uptimeMillis();
    if(now-lastFrame>=31){ drawFrame(); lastFrame=now; }
    h.postDelayed(this,16);
  }};

  HeroEngine(){
   imgs[0]=load(R.drawable.hero_1); imgs[1]=load(R.drawable.hero_2); imgs[2]=load(R.drawable.hero_3);
   imgs[3]=load(R.drawable.hero_4); imgs[4]=load(R.drawable.hero_5);
   sm=(SensorManager)getSystemService(SENSOR_SERVICE);
   sensor=sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
   if(sensor==null)sensor=sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
   for(int i=0;i<5;i++)widths[i]=.20f;
  }
  Bitmap load(int id){return BitmapFactory.decodeResource(getResources(),id);}

  @Override public void onCreate(SurfaceHolder holder){super.onCreate(holder);setTouchEventsEnabled(true);}

  @Override public void onTouchEvent(android.view.MotionEvent e){
   int a=e.getActionMasked();
   int sw=Math.max(1,getSurfaceHolder().getSurfaceFrame().width()), sh=Math.max(1,getSurfaceHolder().getSurfaceFrame().height());
   float W=(sh>sw)?sh:sw;
   float lx=(sh>sw)?e.getY():e.getX();
   if(a==android.view.MotionEvent.ACTION_DOWN||a==android.view.MotionEvent.ACTION_MOVE){
    touching=true; touchX=Math.max(0,Math.min(W-1,lx));
    targetFocus=Math.max(0,Math.min(4,(touchX/W)*5f-.5f));
   }else if(a==android.view.MotionEvent.ACTION_UP||a==android.view.MotionEvent.ACTION_CANCEL){
    touching=false;
   }
  }

  @Override public void onVisibilityChanged(boolean v){
   visible=v;h.removeCallbacks(drawTask);
   if(v){if(sensor!=null)sm.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME);h.post(drawTask);}
   else sm.unregisterListener(this);
  }
  @Override public void onSurfaceDestroyed(SurfaceHolder holder){super.onSurfaceDestroyed(holder);visible=false;h.removeCallbacks(drawTask);sm.unregisterListener(this);}
  @Override public void onAccuracyChanged(Sensor s,int a){}

  @Override public void onSensorChanged(SensorEvent e){
   if(touching)return;
   if(e.sensor.getType()==Sensor.TYPE_ROTATION_VECTOR){
    float[] r=new float[9],o=new float[3];
    SensorManager.getRotationMatrixFromVector(r,e.values);SensorManager.getOrientation(r,o);
    targetTilt=Math.max(-36,Math.min(36,(float)Math.toDegrees(o[2])*1.22f));
   }else{
    targetTilt=Math.max(-36,Math.min(36,targetTilt+e.values[1]*1.45f));
   }
   targetFocus=Math.max(0,Math.min(4,((targetTilt+36f)/72f)*5f-.5f));
  }

  void drawFrame(){
   SurfaceHolder sh=getSurfaceHolder();Canvas c=null;
   try{
    c=sh.lockCanvas();if(c==null)return;
    int sw=c.getWidth(),shh=c.getHeight();
    boolean portrait=shh>sw;
    int W=portrait?shh:sw,H=portrait?sw:shh;
    c.drawColor(Color.rgb(3,4,8));
    c.save();
    if(portrait){c.rotate(90);c.translate(0,-sw);}

    // 类似电脑 HTML：焦点不是“跳格子”，而是连续追随指针/倾斜位置。
    focus+=(targetFocus-focus)*.20f;
    tilt+=(targetTilt-tilt)*.16f;
    active=Math.max(0,Math.min(4,Math.round(focus)));
    if(active!=lastActive){flash=1f;lastActive=active;}
    flash*=.76f; phase+=.10f;

    // 默认 20%；焦点人物最多约 24%，邻近栏平滑让位。
    float total=0;
    for(int i=0;i<5;i++){
     float d=Math.abs(i-focus);
     float e=Math.max(0,1-d);
     targetWidths[i]=.19f+.05f*e;
     widths[i]+=(targetWidths[i]-widths[i])*.22f;
     total+=widths[i];
    }

    float x=0;
    for(int i=0;i<5;i++){
     float w=W*widths[i]/total;
     float proximity=Math.max(0,1-Math.abs(i-focus));
     drawHero(c,imgs[i],x,w,H,i,proximity);
     x+=w;
    }

    c.restore();
   }finally{if(c!=null)sh.unlockCanvasAndPost(c);}
  }

  void drawHero(Canvas c,Bitmap b,float x,float w,float h,int idx,float proximity){
   // 电脑版本观感：轻微放大，不再把整个人突然弹大。
   float scale=1.0f+.075f*proximity;
   float dw=w*scale,dh=h*scale;
   src.set(0,0,b.getWidth(),b.getHeight());
   float sa=(float)b.getWidth()/b.getHeight(), da=dw/dh;
   if(sa>da){int nw=(int)(b.getHeight()*da),l=(b.getWidth()-nw)/2;src.set(l,0,l+nw,b.getHeight());}
   else{int nh=(int)(b.getWidth()/da),t=(b.getHeight()-nh)/2;src.set(0,t,b.getWidth(),t+nh);}
   float parallax=(focus-idx)*-3.0f;
   dst.set(x-(dw-w)/2+parallax,-(dh-h)/2,x-(dw-w)/2+parallax+dw,-(dh-h)/2+dh);
   bitmapPaint.setAlpha((int)(165+90*proximity));
   c.drawBitmap(b,src,dst,bitmapPaint);

   // 未聚焦成员只轻压暗。
   if(proximity<.98f){
    dimPaint.setColor((int)((1-proximity)*70)<<24);
    c.drawRect(x,0,x+w,h,dimPaint);
   }

   if(proximity>.02f){
    c.save();c.clipRect(x,0,x+w,h);
    int col=colors[idx];

    // 不再每帧创建 RadialGradient/LinearGradient：用两层半透明几何光带模拟 HTML 流光，GPU/CPU 压力小很多。
    fxPaint.setBlendMode(BlendMode.SCREEN);
    float a=proximity;
    float bandY=h*(.72f-(float)Math.sin(phase)*.08f);
    fxPaint.setColor(withAlpha(col,(int)(42+68*a)));
    c.rotate(-12,x+w/2,h/2);
    c.drawRect(x-w*.18f,bandY-h*.075f,x+w*1.18f,bandY+h*.075f,fxPaint);
    fxPaint.setColor(withAlpha(0xffffffff,(int)(22+42*a)));
    c.drawRect(x-w*.12f,bandY-h*.025f,x+w*1.12f,bandY+h*.025f,fxPaint);

    // 切换成员时快速由下往上闪一下；只有短暂几帧。
    if(idx==active && flash>.035f){
     float fy=h*(1.08f-flash*1.05f);
     fxPaint.setColor(withAlpha(col,(int)(120*flash)));
     c.drawRect(x-w*.12f,fy-h*.08f,x+w*1.12f,fy+h*.08f,fxPaint);
     fxPaint.setColor(withAlpha(0xffffffff,(int)(145*flash)));
     c.drawRect(x,fy-h*.018f,x+w,fy+h*.018f,fxPaint);
    }
    fxPaint.setBlendMode(null);
    c.restore();
   }
  }
  int withAlpha(int color,int a){return(color&0x00ffffff)|(Math.max(0,Math.min(255,a))<<24);}
 }
}
