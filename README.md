# Codex Monitor

Codex Monitor 是一个局域网内监控多台电脑 Codex 状态的 Android App + 本地 HTTP Agent。手机端可以查看每台电脑的当前状态、5h / weekly 余额、重置时间和 token 消耗，并在任务完成、失败或需要权限确认时弹出手机通知。

## 功能

- Android App 扫描局域网内 `8787` 端口的电脑端 Agent。
- 可手动添加多台电脑并持续后台轮询。
- 默认每 `0.5 秒`检查状态，App 设置里可调整为 `0.5 / 1 / 2 / 5 / 10 / 30 秒`。
- 使用类似 Agent Signal Bar 的红黄绿信号灯显示状态。
- 显示 Codex 当前状态：思考中、执行中、完成、失败、需要权限、空闲等。
- 显示 Codex Plus 的 5h / weekly 剩余额度和重置时间。
- 显示当前会话 token 消耗。
- 任务完成、失败或需要权限时发送手机通知。
- Codex Hook 可在 Codex 启动时自动拉起 `server.py`。

## 目录结构

```text
android-app/   Android App 源码
host/          Codex Hook 安装、事件上报、自动启动脚本
hooks/         hooks.json 示例
server.py      电脑端局域网 HTTP Agent
.env.example   本地访问令牌等配置示例
```

构建产物、日志、Chrome 调试 profile、本地 IDE 配置、`local.properties`、`.env` 等都已通过 `.gitignore` 排除。

## 推荐安装方式

如果你要在多台电脑上部署，推荐直接让 Codex / Claude Code / 其他 AI 助手在目标电脑上帮你安装。把这个仓库复制到目标电脑后，对 AI 说：

```text
请帮我在这台电脑安装 Codex Monitor 的电脑端 Hook。
要求：
1. 进入当前项目目录。
2. 如需要，复制 .env.example 为 .env。
3. 执行 .\host\install_codex_mobile_hooks.ps1 -Scope User。
4. 确认 %USERPROFILE%\.codex\hooks.json 已写入。
5. 启动或重启 Codex。
6. 启动 python server.py，并确认 http://127.0.0.1:8787/api/health 可访问。
7. 如果局域网访问失败，帮我放行 Windows 防火墙 8787 端口。
```

这样可以避免手动找路径、改 hooks、开防火墙时出错。目标电脑安装完成后，手机 App 扫描局域网即可添加这台电脑。

## 电脑端手动安装

1. 复制配置文件：

   ```powershell
   Copy-Item .env.example .env
   ```

2. 可选：编辑 `.env` 设置本地访问令牌。

   ```text
   LOCAL_AGENT_TOKEN=your-local-token
   ```

   如果留空，局域网内可直接访问。建议只在可信局域网使用留空模式。

3. 启动电脑端 Agent：

   ```powershell
   python server.py
   ```

4. 手机和电脑连接同一个 Wi-Fi，在 App 中扫描或手动添加：

   ```text
   http://电脑局域网IP:8787
   ```

5. 检查接口：

   ```text
   http://127.0.0.1:8787/api/health
   http://127.0.0.1:8787/api/mobile-status?fresh=1
   ```

如果配置了 `LOCAL_AGENT_TOKEN`，App 中也要填写同一个访问令牌。

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

这会写入当前项目的 `.codex/hooks.json`。该目录属于本地生成文件，不建议提交到 GitHub。

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

## Windows 防火墙

如果手机能访问电脑 IP，但打不开 `8787`，通常是防火墙问题。用管理员 PowerShell 运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\host\allow_firewall_8787.ps1
```

也可以手动允许 Python 或端口 `8787` 的局域网入站连接。

## Android App 打包

源码目录：

```text
android-app/
```

使用 Android Studio：

1. 打开 `android-app/`。
2. 等待 Gradle Sync。
3. 菜单选择：

   ```text
   Build > Build Bundle(s) / APK(s) > Build APK(s)
   ```

4. Debug APK 输出：

   ```text
   android-app/app/build/outputs/apk/debug/app-debug.apk
   ```

命令行打包：

```powershell
cd android-app
.\build-apk.ps1
```

或：

```powershell
cd android-app
.\gradlew.bat assembleDebug
```

## 小米 / 红米手机设置

为了让后台通知稳定，建议给 `Codex Monitor` 设置：

- 通知权限：允许通知、顶部横幅、锁屏、震动。
- 省电策略：无限制。
- 自启动：允许。
- 后台运行：不要在最近任务里清理该 App。

如果系统回收后台服务，可能出现任务完成后只有重新打开 App 才弹通知。

## 部署到其他电脑

只需要复制这些文件和目录：

```text
android-app/
host/
hooks/
server.py
.env.example
README.md
```

在目标电脑安装 Python 后，运行：

```powershell
.\host\install_codex_mobile_hooks.ps1 -Scope User
python server.py
```

然后重启 Codex 并信任 Hook。手机 App 扫描局域网即可添加这台电脑。

## GitHub 提交说明

本仓库不提交：

- `.env`
- `android-app/local.properties`
- Android 构建产物
- 日志文件
- Python `__pycache__`
- 本地 IDE 配置

如果要上传到 GitHub，建议先创建私有仓库。
