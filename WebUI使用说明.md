# MiracastReceiver WebUI 使用说明

MiracastReceiver 1.4.0 起内置局域网 WebUI。WebUI 与 AirPlay / DLNA / Miracast 一样由 `CastReceiverService` 托管，因此退出 Android TV 主界面后仍可使用。

## 访问方式

默认配置：

- WebUI：开启
- 端口：`8090`
- API Token 验证：开启

电视主界面会直接显示当前 WebUI 地址和 Token，例如：

```text
http://192.168.1.100:8090
Token: 0123456789abcdef...
```

在同一可信局域网的电脑或手机浏览器打开该地址，然后输入电视上显示的 Token。

> WebUI 当前使用 HTTP，Token 在局域网链路上不是 TLS 加密的。请只在可信 LAN、受控 VLAN 或 VPN 内使用，不要把 8090 直接端口转发到公网。

## 功能

### 概览

- Android / 设备型号 / IP
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
- 修改 WebUI 端口
- 开关 WebUI Token 验证
- 开机自动启动
- 执行 Magisk Root / Shizuku 后台优化
- 轮换 WebUI Token
- 手动重载接收服务

WebUI 与 DLNA / UPnP 端口不能设置成相同端口。

### 诊断

可查看：

- 当前 LAN IP / 网络状态
- Wi-Fi 状态
- H.264 / H.265 解码能力
- 推荐解码参数
- 当前实际解码器及硬件解码状态
- WebUI / UPnP / Miracast 端口

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

Token 使用随机数生成并持久化保存，可从电视 UI 查看，也可以在 WebUI 中轮换。

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

如果在 WebUI 中修改 WebUI 自己的端口，保存后页面会自动跳转到新端口。

如果在 WebUI 中关闭 WebUI，当前请求完成后管理服务器会停止；需要在电视主界面重新打开 WebUI 开关才能再次访问。

## 安全限制

WebUI HTTP 服务做了以下限制：

- 默认 Token 鉴权
- 请求行长度限制
- 单行/总 Header 长度限制
- 请求体最大 256 KiB
- Socket 超时
- `Cache-Control: no-store`
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- 不在静态 HTML/JS 中嵌入 Token

不建议关闭 Token 验证；关闭后同一网络中的其他设备也可能调用管理 API。
