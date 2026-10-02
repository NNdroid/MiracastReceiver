# MiracastReceiver WebUI 使用说明

MiracastReceiver 1.4.0 起内置局域网 WebUI；1.5.0 起增加 Android TV 二维码直达、WebUI 首选/实际端口状态和端口冲突自动回退。WebUI 与 AirPlay / DLNA / Miracast 一样由 `CastReceiverService` 托管，因此退出 Android TV 主界面后仍可使用。

## 访问方式

默认配置：

- WebUI：开启
- 首选端口：`18090`
- API Token 验证：开启

电视主界面会显示当前**实际** WebUI 地址和二维码，例如：

```text
http://192.168.1.100:18090
```

最方便的方式是直接用手机扫描电视首页二维码。开启 Token 验证时，二维码会包含一次性的启动参数：

```text
http://192.168.1.100:18090/?token=<WebUI Token>
```

浏览器打开页面后会立即：

1. 将 Token 保存到当前浏览器会话的 `sessionStorage`；
2. 使用 `history.replaceState()` 从地址栏移除 `?token=...`；
3. 后续 API 只通过 `X-API-Token` 请求头鉴权。

因此日常使用不需要在电视遥控器或手机上手工输入长 Token。电视仍会显示缩略 Token 指纹，方便核对当前凭据。

> WebUI 当前使用 HTTP，Token 在局域网链路上不是 TLS 加密的。请只在可信 LAN、受控 VLAN 或 VPN 内使用，不要把 WebUI 端口直接转发到公网。

## 端口自动回退

`18090` 是**首选端口**，不是强制端口。

启动时 WebUI 会按以下顺序选择监听端口：

1. 首先尝试配置的首选端口（默认 `18090`）；
2. 如果端口被占用，优先尝试上一次成功使用的回退端口；
3. 仍不可用时，在 `18091-65535` 中随机选择可用 TCP 端口；
4. 极端情况下由内核分配临时可用端口。

自动回退不会修改你的首选端口配置。例如首选仍为 `18090`，本次因为冲突实际监听 `43721`，电视主界面会显示：

```text
首选端口 18090 被占用 · 已自动切换到 43721
```

二维码永远使用**实际监听端口**。下一次服务启动仍会优先尝试 `18090`。

WebUI 状态页同时显示：

- 首选端口
- 当前实际监听端口
- 是否正在使用端口回退

## 功能

### 概览

- Android / 设备型号 / IP
- 接收服务运行时长
- WebUI 首选端口 / 实际端口 / 回退状态
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
- 查看 WebUI 实际监听端口和回退状态
- 开关 WebUI Token 验证
- 开机自动启动
- 执行 Magisk Root / Shizuku 后台优化
- 轮换 WebUI Token
- 手动重载接收服务

WebUI 与 DLNA / UPnP 端口不能设置成相同端口；Miracast RTSP `7236` 也不会被随机端口回退占用。

### 诊断

可查看：

- 当前 LAN IP / 网络状态
- Wi-Fi 状态
- H.264 / H.265 解码能力
- 推荐解码参数
- 当前实际解码器及硬件解码状态
- WebUI 首选/实际端口
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

二维码 URL 中的 `?token=` 仅用于浏览器首次启动认证；页面加载后会立即从地址栏移除。Token 不会嵌入 WebUI 静态 HTML / JS / CSS 文件中。

Token 使用安全随机数生成并持久化保存，可以在 WebUI 中轮换。轮换后电视首页二维码会自动刷新。

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

## 端口修改注意事项

如果在 WebUI 中修改 WebUI 自己的首选端口，保存后页面会自动跳转到新端口。若新端口已被其他程序占用，服务重载后会继续使用可用回退端口，页面也会尽量自动连接到实际端口。

如果在 WebUI 中关闭 WebUI，当前请求完成后管理服务器会停止；需要在电视主界面重新打开 WebUI 开关才能再次访问。

## 安全限制

WebUI HTTP 服务做了以下限制：

- 默认 Token 鉴权
- 二维码 Token 仅用于首次浏览器启动，加载后立即清理地址栏
- 请求行长度限制
- 单行/总 Header 长度限制
- 请求体最大 256 KiB
- Socket 超时
- `Cache-Control: no-store`
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Referrer-Policy: no-referrer`
- Content Security Policy
- 不在静态 HTML/JS 中嵌入 Token

不建议关闭 Token 验证；关闭后同一网络中的其他设备也可能调用管理 API。
