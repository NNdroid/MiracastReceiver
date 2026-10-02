# MiracastReceiver WebUI 使用说明

MiracastReceiver 1.5.x 内置新版局域网 WebUI。WebUI 与 AirPlay / DLNA / Miracast 一样由 `CastReceiverService` 托管，因此退出 Android TV 主界面后仍可使用。

## 访问方式

默认配置：

- WebUI：开启
- **首选端口**：`18090`
- API Token 验证：开启
- 端口自动回退：开启

电视主界面会直接显示当前**实际 WebUI 地址**、首选/实际端口状态和扫码二维码，例如：

```text
http://192.168.1.100:18090
实际 18090 · 使用首选端口
```

最推荐的方式是直接使用手机扫描电视首页二维码。二维码已经包含当前实际监听端口和 Token，浏览器会自动完成鉴权，无需手动输入长 Token。电视界面只显示 Token 的缩略指纹，完整 Token 不再占用大屏空间。

二维码中的 Token 使用 URL Fragment：

```text
http://192.168.1.100:18090/#token=<token>
```

`#token=...` 不会作为 HTTP query 发送给 WebUI 服务器。网页读取 Token 后会保存到当前浏览器会话，并立即从地址栏清除 Fragment。后续 API 仍通过 `X-API-Token` 请求头鉴权。

如果手动输入 WebUI 地址，则仍可在登录页面输入 Token。

> WebUI 当前使用 HTTP，Token 在局域网链路上不是 TLS 加密的。请只在可信 LAN、受控 VLAN 或 VPN 内使用，不要把 WebUI 端口直接转发到公网。

## 首选端口与自动回退

`18090` 是**首选端口**，不是一次冲突后就会被永久改写的端口。

启动时 WebUI 会按顺序：

1. 尝试配置的首选端口（默认 `18090`）；
2. 如果被占用，尝试上一次成功使用的备用端口；
3. 如果仍不可用，在高位端口范围内随机选择一个可用端口；
4. 将实际监听端口同步到电视首页、二维码、WebUI 状态和诊断页面。

例如首选端口仍为 `18090`，当前被其他程序占用而临时使用 `43721`，电视会显示：

```text
实际 43721 · 首选 18090 被占用，已临时回退
```

自动回退只更新运行时/last-bound 端口，不会把 `web_ui_port` 首选配置永久改成随机端口。下一次接收服务启动仍会首先尝试首选端口。

在 WebUI 中手动修改首选端口时，如果指定端口已被占用，本次运行会继续使用可用回退端口；浏览器会根据服务返回的实际重连端口跳转，并通过 `#token=` Fragment 带入当前认证，所以跨端口后无需再次手输 Token。

WebUI 与 DLNA / UPnP 端口不能设置为相同端口，Miracast RTSP `7236` 也不会被选作自动备用端口。

## 功能

### 概览

新版概览页提供：

- Android / 设备型号 / IP
- 当前 WebUI 实际端口、首选端口和自动回退状态
- 接收服务运行时长
- AirPlay 状态和发送端
- Miracast 会话、客户端和 RTP 端口
- 当前播放状态、标题、来源
- 当前 MediaCodec 解码器及是否硬件加速
- Root / Magisk / Shizuku / service.d 状态
- 最近的接收服务错误

### 协议与发现

可独立配置：

- AirPlay
- DLNA / UPnP
- Miracast / WFD
- 自定义 mDNS
- AirPlay 音频
- 收到投屏后是否自动切换到播放器
- 设备广播名称
- 镜像最大高度：自动 / 720p / 1080p / 1440p / 2160p
- DLNA / UPnP HTTP 端口
- 重新生成连接码

配置保存后接收服务会自动安全重载。修改设备名、协议开关、端口或连接码无需重启电视。

### 播放器控制

DLNA / Media3 播放时可远程执行：

- 播放
- 暂停
- 停止
- Seek
- 音量
- 播放倍速

AirPlay / Miracast 实时镜像继续使用低延迟 `MediaCodec -> Surface` 路径，不对实时镜像提供 Seek / 倍速。

### 系统与后台

- 开关 WebUI
- 修改 WebUI 首选端口
- 查看当前实际监听端口和自动回退状态
- 开关 WebUI Token 验证
- 开机自动启动
- 执行 Magisk Root / Shizuku 后台优化
- 轮换 WebUI Token
- 手动重载接收服务

轮换 Token 后电视首页二维码会自动更新。

### 诊断

可查看：

- 当前 LAN IP / 网络状态
- Wi-Fi 状态
- H.264 / H.265 解码能力
- 推荐解码参数
- 当前实际解码器及硬件解码状态
- WebUI 实际端口 / 首选端口 / 回退状态
- UPnP / Miracast 端口

### 日志

WebUI 提供一个内存环形日志缓冲区：

- 最多保存约 600 条最近日志
- 不写入永久文件
- 支持实时刷新
- 支持一键清空
- App 重启后自动清空

## API 鉴权

开启鉴权时，所有管理 API（除 `/api/ping`）都要求：

```http
X-API-Token: <token>
```

或者：

```http
Authorization: Bearer <token>
```

Token 使用安全随机数生成并持久化保存，可通过电视二维码自动带入，也可以在 WebUI 中轮换。

## 主要 API

```text
GET  /api/ping
GET  /api/status
GET  /api/config
POST /api/config
GET  /api/diagnostics
GET  /api/logs?limit=400
POST /api/logs/clear
POST /api/actions/player
POST /api/actions/restart
POST /api/actions/reconfigure
POST /api/actions/regenerate-code
POST /api/actions/rotate-token
POST /api/actions/background-optimize
```

`POST /api/actions/player` 示例：

```json
{"action":"pause"}
```

```json
{"action":"seek","positionMs":120000}
```

```json
{"action":"volume","value":70}
```

```json
{"action":"speed","value":1.25}
```

## 安全限制

WebUI HTTP 服务包含以下限制：

- 默认 Token 鉴权
- 扫码 Token 使用 URL Fragment，不作为 query 发送
- 请求行长度限制
- 单行/总 Header 长度限制
- 请求体最大 256 KiB
- Socket 超时
- `Cache-Control: no-store`
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- 不在静态 HTML / JS 中嵌入 Token

不建议关闭 Token 验证；关闭后同一网络中的其他设备也可能调用管理 API。
