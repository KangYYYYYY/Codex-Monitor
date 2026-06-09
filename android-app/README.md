# Codex Monitor Android App

原生 Java Android App，用来连接电脑端 `server.py`：

```text
http://电脑局域网IP:8787/api/mobile-status
```

## 功能

- 扫描局域网内运行 `server.py` 的电脑。
- 手动添加电脑端地址。
- 多设备状态卡片纵向排列。
- 显示状态、5h / weekly 余额、重置时间和 token 消耗。
- 默认每 0.5 秒后台检查状态，设置中可调整。
- 任务完成、失败或需要权限确认时发送手机通知。

## 用 Android Studio 打包

1. 安装 Android Studio。
2. 打开目录：

   ```text
   android-app
   ```

3. 等待 Gradle Sync 完成。
4. 菜单选择：

   ```text
   Build > Build Bundle(s) / APK(s) > Build APK(s)
   ```

5. APK 输出目录：

   ```text
   android-app/app/build/outputs/apk/debug/app-debug.apk
   ```

## 命令行打包

```powershell
cd android-app
.\gradlew.bat assembleDebug
```

## 安装到手机

Android Studio 连接手机后可直接点击 Run。

命令行安装：

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

## App 内设置

电脑端地址示例：

```text
http://192.168.1.23:8787
```

如果电脑端 `.env` 设置了：

```text
LOCAL_AGENT_TOKEN=xxx
```

App 里的访问令牌也要填同一个值。没设置就留空。

## 后台通知

小米/红米手机建议开启：

- 通知横幅、锁屏、震动。
- 省电策略设为无限制。
- 允许自启动。

否则系统可能回收后台服务，导致通知延迟到下次打开 App 才出现。
