package com.heroflow.wallpaper;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;

public class MainActivity extends Activity {
 @Override public void onCreate(Bundle b){
  super.onCreate(b);
  LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(48,80,48,48);
  TextView t=new TextView(this); t.setText("五色流光英雄动态壁纸\n\n手机倾斜时会切换突出成员，并让高光随姿态流动。建议横屏预览。");
  t.setTextSize(18); l.addView(t);
  Button btn=new Button(this); btn.setText("预览并设置动态壁纸"); l.addView(btn);
  btn.setOnClickListener(v->{
   Intent i=new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
   i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,new ComponentName(this,HeroWallpaperService.class));
   startActivity(i);
  });
  setContentView(l);
 }
}