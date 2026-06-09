# Codex Monitor

局域网内监控多台电脑 Codex 状态的 Android App + 本地 HTTP Agent。

手机端显示每台电脑的当前状态、5h / weekly 余额、重置时间和 token 消耗；当任务完成、失败或需要权限确认时发送手机通知。

## 保留的目录

```text
android-app/   Android App 源码
host/          Codex Hook 安装、事件上报、自动启动脚本
hooks/         hooks.json 示例
server.py      电脑端局域网 HTTP Agent
.env.example   本地访问令牌等配置示例
```

构建产物、日志、预览图、Chrome 调试 profile、第三方参考工程和本地 IDE 配置已经从仓库中移除，并由 `.gitignore` 忽略。

## 功能

- Android App 扫描局域网内 `8787` 端口的电脑端 Agent。
- 可手动添加多台电脑并持续后台轮询。
- 默认每 0.5 秒检查状态，App 设置里可调整周期。
- 显示 Codex 状态：思考中、执行中、完成、失败、需要权限、空闲等。
- 显示 Codex 5h / weekly 剩余额度和重置时间。
- 显示当前会话 token 消耗。
- 任务完成、失败或需要权限时发送手机通知。
- Codex Hook 可在 Codex 启动时自动拉起 `server.py`。

## 电脑端启动

1. 复制配置文件：

   ```powershell
   Copy-Item .env.example .env
   ```

2. 可选：编辑 `.env` 设置本地访问令牌：

   ```text
   LOCAL_AGENT_TOKEN=自己设置一个本地访问令牌
   ```

3. 启动服务：

   ```powershell
   python server.py
   ```

4. 手机和电脑连接同一个 Wi-Fi，App 中添加或扫描电脑地址：

   ```text
   http://电脑局域网IP:8787
   ```

健康检查接口：

```text
GET /api/health
```

手机状态接口：

```text
GET /api/mobile-status
```

如果配置了 `LOCAL_AGENT_TOKEN`，请求需要带 `X-Agent-Token`。

## Codex Hook 自动启动

安装用户级 Hook：

```powershell
.\host\install_codex_mobile_hooks.ps1 -Scope User
```

这会写入：

```text
%USERPROFILE%\.codex\hooks.json
```

安装项目级 Hook：

```powershell
.\host\install_codex_mobile_hooks.ps1 -Scope Project
```

这会写入当前项目的 `.codex/hooks.json`。该目录是本地生成文件，不提交到 GitHub。

安装后重启 Codex。第一次运行时 Codex 可能要求 review / trust hook 定义，确认信任后才会执行。

Hook 映射：

```text
SessionStart      -> 启动 server.py
UserPromptSubmit  -> THINKING
PreToolUse        -> WRITING(apply_patch) / RUNNING(other)
PostToolUse       -> THINKING / ERROR
PermissionRequest -> NEED_CONFIRM
Stop              -> DONE
```

## Android App

源码在：

```text
android-app/
```

打包方式：

1. 用 Android Studio 打开 `android-app/`。
2. 等待 Gradle Sync。
3. 菜单选择：

   ```text
   Build > Build Bundle(s) / APK(s) > Build APK(s)
   ```

4. Debug APK 输出：

   ```text
   android-app/app/build/outputs/apk/debug/app-debug.apk
   ```

也可以在 Android Studio 终端中运行：

```powershell
cd android-app
.\gradlew.bat assembleDebug
```

## 其它电脑部署

复制这些文件/目录到另一台电脑：

```text
android-app/
host/
hooks/
server.py
.env.example
README.md
```

那台电脑安装 Python 后，在项目目录运行：

```powershell
.\host\install_codex_mobile_hooks.ps1 -Scope User
```

然后重启 Codex 并信任 Hook。手机 App 扫描局域网即可添加这台电脑。

## 小米手机设置

为了后台通知稳定，建议在小米/红米手机上给 `Codex Monitor` 设置：

- 通知权限：允许通知、横幅、锁屏、震动。
- 省电策略：无限制。
- 自启动：允许。
- 后台运行：不要在最近任务里锁死/清理该 App。

电脑防火墙需要允许 Python 或端口 `8787` 的局域网入站连接。可以用管理员 PowerShell 运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\host\allow_firewall_8787.ps1
```
