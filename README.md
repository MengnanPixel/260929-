# Hero Flow Live Wallpaper

Android 原生动态壁纸工程。五张角色图已打包在 drawable-nodpi 中。

## 构建
1. 用 Android Studio 打开本目录。
2. 等待 Gradle Sync。
3. Build > Build APK(s)。
4. 安装 APK，打开应用，点“预览并设置动态壁纸”。

## 交互
- 使用 Rotation Vector；若设备没有则退回 Gyroscope。
- 左右倾斜选择五位成员。
- 当前成员整幅放大、提亮，并叠加随姿态移动的角色色高光与流光扫光。
- 非当前成员压暗。
- 仅在壁纸可见时注册传感器并约 30 FPS 绘制，以降低耗电。

如果桌面启动器锁定竖屏，壁纸会按当前 Surface 尺寸自适应裁切；横屏时效果最接近原设计。
