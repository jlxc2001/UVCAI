# UVCAI

干净版 Android 项目，用于在低配置车机上做 NanoDet / ncnn 图像识别。

## 当前稳定输入方案

你已经验证第三方 USB Camera App 可以打开 UVC 摄像头，所以这个版本默认采用最稳路线：

USB 摄像头 → 你现有可用的 USB Camera App 显示画面 → UVCAI 通过系统录屏取帧 → ncnn/NanoDet 识别 → HUD 锁定框悬浮显示

这样不直接依赖某个 UVC 库是否兼容你的摄像头，优先保证能跑通识别。

## 使用方式

1. 上传整个项目到 GitHub 空仓库。
2. Settings → Actions → General → Workflow permissions 选 Read and write permissions。
3. Actions → release-apk → Run workflow。
4. 安装 Release 里的 APK。
5. 打开 UVCAI，选择 ELite0_320 + CPU。
6. 点击“打开USB摄像头App”，确认第三方 App 画面正常。
7. 回到 UVCAI，点击“开始识别当前屏幕”。
8. 授权悬浮窗和录屏。
9. 切回 USB Camera App，即可在摄像头画面上叠加 HUD 识别框。

## 说明

没有内置或复制第三方 USB Camera APK 里的闭源 native so。后续如果要做真 UVC 直连，需要继续基于公开 UVC 库接入，或你提供可合法复用的源码库。
