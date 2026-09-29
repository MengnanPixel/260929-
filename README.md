# Hero Flow Live Wallpaper

Android 原生动态壁纸工程。五张角色图已打包在 drawable-nodpi 中。

## 构建
1. 用 Android Studio 打开本目录。
2. 等待 Gradle Sync。
3. Build > Build APK(s)。
4. 安装 APK，打开应用，点“预览并设置动态壁纸”。

## 交互（V6）
- 点击：轻微放大 + 快速炫光。
- 手指滑动：放大与炫彩光晕跟随手指。
- 晃动手机：陀螺仪（Gyroscope）来回切换成员，可在 App 内调灵敏度/方向。
- 硬件加速 Canvas + 独立渲染线程；仅在壁纸可见且有动画时绘制。
