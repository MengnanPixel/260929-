package com.heroflow.wallpaper;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.*;

public class MainActivity extends Activity {
 static final String PREFS = "hero_prefs";

 @Override public void onCreate(Bundle b){
  super.onCreate(b);
  final SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);

  LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(48,80,48,48);
  TextView t=new TextView(this);
  t.setText("五色流光英雄动态壁纸 V6\n\n· 点击：轻微放大 + 快速炫光\n· 手指滑动：放大与炫彩跟随手指\n· 晃动手机：陀螺仪来回切换人物");
  t.setTextSize(17); l.addView(t);

  CheckBox gyro=new CheckBox(this); gyro.setText("启用陀螺仪（晃动手机切换人物）");
  gyro.setChecked(sp.getBoolean("gyro", true));
  gyro.setOnCheckedChangeListener((v,c)->sp.edit().putBoolean("gyro",c).apply());
  l.addView(gyro);

  CheckBox inv=new CheckBox(this); inv.setText("反转陀螺仪方向（晃动方向反了就勾上）");
  inv.setChecked(sp.getBoolean("invert", false));
  inv.setOnCheckedChangeListener((v,c)->sp.edit().putBoolean("invert",c).apply());
  l.addView(inv);

  TextView st=new TextView(this); st.setText("陀螺仪灵敏度（往右更灵敏）"); st.setPadding(0,32,0,0); l.addView(st);
  SeekBar sb=new SeekBar(this); sb.setMax(100); sb.setProgress(sp.getInt("sens",50));
  sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
   public void onProgressChanged(SeekBar s,int p,boolean u){ sp.edit().putInt("sens",p).apply(); }
   public void onStartTrackingTouch(SeekBar s){}
   public void onStopTrackingTouch(SeekBar s){}
  });
  l.addView(sb);

  Button btn=new Button(this); btn.setText("预览并设置动态壁纸"); l.addView(btn);
  btn.setOnClickListener(v->{
   Intent i=new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
   i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,new ComponentName(this,HeroWallpaperService.class));
   startActivity(i);
  });
  setContentView(l);
 }
}
