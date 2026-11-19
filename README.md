# AI 识图悬浮助手

[简体中文](README.md) | [English](README.en.md)

一个常驻安卓悬浮球：**单击存图、双击打字提问、三击翻译截图**。悬浮球只有 48dp，不抢输入焦点，窗口以外的触摸全部下发给当前应用——刷视频、聊天、浏览网页都不受影响。

- 包名：`com.aivision.floatassist`
- 版本：1.1
- 最低系统：Android 8.0（API 26），适配 Android 14
- 技术栈：Java + Android Framework + MediaProjection + ML Kit OCR + OkHttp

## 功能

| 手势 | 行为 |
| --- | --- |
| 单击 | 截取当前屏幕并存入「AI 截图库」，同时后台 OCR 出文字；只弹一行轻提示，不打断操作 |
| 双击 | 截图并弹出输入框，打字结合截图向 AI 提问；输入框在面板顶部，键盘不会遮挡 |
| 三击 | 截图并翻译：原文中文 → 英文，其他语言 → 简体中文；只显示译文 |

拖动悬浮球可换位置、松手自动吸边；长按 0.5 秒弹出快捷菜单（打开设置 / 关闭悬浮球）；透明度可在 25%–100% 之间调节。

## 首次使用

1. 安装 APK（需要允许「安装未知来源应用」）。
2. 打开 App，填写 DeepSeek API Key（`sk-` 开头），点「保存配置」。
3. 点「测试连接」，确认 Key 可用。
4. 点「授予悬浮窗权限」，在系统设置里打开「显示在其他应用上层」。
5. 点「开启悬浮助手」，在系统弹出的「屏幕截图」授权中允许。
6. 之后可退出 App，悬浮球会一直保留在屏幕上。

## AI 与 DeepSeek

DeepSeek 官方接口（`deepseek-chat` / `deepseek-reasoner`）**只接受文本，不接受图片**。因此本项目默认采用：

```
屏幕截图 → 本地 ML Kit OCR（离线，中英文）→ 文本 + 用户问题 → DeepSeek → 回答 / 译文
```

这样官方 Key 可以直接使用。如果希望 AI 真正「看图」（纯图片、界面布局、商品外观等），在配置页的「视觉模型（可选）」填一个 **OpenAI 兼容且支持图片输入**的模型名，并让接口地址指向对应服务。只有这一栏非空时，才会把截图以 base64 图片发送给模型。

翻译实现细节：

- 本地统计中英文字符比例，自动判定目标语言；
- 提示词显式指定目标语言，禁止输出原文或原文对照；
- 译文与原文雷同、或目标语言缺失时，自动重试一次；
- 仍失败会提示「没拿到译文」，而不是把原文当作翻译结果展示。

- 接口地址默认 `https://api.deepseek.com`，可填代理/中转地址，自动补 `/chat/completions`。
- API Key、接口地址只保存在本机 SharedPreferences，不会上传。
- 截图保存在应用私有目录 `files/shots`，最多保留 40 张，可在「AI 截图库」查看、复制文字、长按删除。

## 为什么悬浮球不影响手机操作

- 悬浮球窗口仅 48×48dp，使用 `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`，不获取输入焦点，窗口外的触摸事件全部下发给下层应用。
- 拖动后自动吸附屏幕左/右边缘，尽量减少遮挡。
- 半透明显示，透明度可调。
- 只有主动双击/三击才会出现操作面板，点「收起 / 关闭」立即恢复原界面。
- 通知栏常驻一条低优先级通知，这是 Android 前台服务（截图授权）的强制要求，不会弹出横幅。

## 已知限制

- 每次重新开启悬浮助手都需要在系统弹窗中重新授权屏幕截图（Android 14 强制要求，无法绕过）。
- 银行、支付、DRM 等安全窗口系统禁止截屏，会得到黑屏或空图。
- 截图分辨率取开启时的屏幕尺寸；开启后旋转屏幕或进出分屏，可能被裁切，建议重新开启一次。
- 纯图片且没有文字、又未配置视觉模型时，只能存图，AI 没有可分析的信息。
- ML Kit 中文 OCR 对极小字号、艺术字、竖排文字会识别错漏，遇到答非所问可以换更具体的问法再试。

## 构建

环境：JDK 17、Android SDK 34、Gradle 8.7。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

主要依赖：

- `com.google.mlkit:text-recognition-chinese:16.0.0`（离线 OCR）
- `com.squareup.okhttp3:okhttp:4.12.0`（网络请求）

## 项目结构

```
app/src/main/java/com/aivision/floatassist/
├── MainActivity.java      配置、权限申请、启动入口
├── FloatingService.java   悬浮球、手势识别、结果面板、快捷菜单（前台服务）
├── CaptureManager.java    MediaProjection + VirtualDisplay + ImageReader 截图
├── OcrHelper.java         ML Kit 本地 OCR
├── AiClient.java          DeepSeek / OpenAI 兼容接口，翻译与提问
├── ShotStore.java         AI 截图库存储
├── GalleryActivity.java   截图库界面
├── Prefs.java             本机配置
└── Ui.java                尺寸与屏幕工具
```

## 版本记录

- **1.1**：修复英文截图翻译仍输出英文的问题（显式目标语言 + 复述检测与重试）；面板标题显示目标语言；无文字且无视觉模型时给出明确提示。
- **1.0**：首个版本，支持单击存图、双击提问、三击翻译，悬浮球可拖动吸边。
