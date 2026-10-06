# Windows 无线投屏（Miracast）Root 配置说明

> 实测机型：小米 MIX 2S（polaris，SDM845）+ MIUI V9 / Android 8.0，MIUI 自带 root
> 发送端：Windows 11（`MSMiracastSource/10.00.26100`）

本文档记录如何让一台 **已 root 的 Android 设备**被 Windows 的「连接到无线显示器」发现并接收投屏。

> **先确认你的电视是否需要 root。** 系统自带「屏幕镜像」功能的电视（实测 Sony BRAVIA 4K VH22 / Android 12）
> 系统本身已在广播 WFD IE，本应用无需 root 即可接管 Miracast 会话，Windows 和安卓手机都能直接发现。
> 判断方法：`adb shell settings get global wifi_display_on` 返回 `1` 即属于此类，不必继续阅读本文。
> 连接时电视上弹出的「是否允许连接」确认框是系统的 Wi-Fi Direct 授权流程，确认即可。

## 为什么（系统不带屏幕镜像的设备）必须 root

Windows 通过扫描 Wi-Fi beacon / probe response 里的 **WFD IE**（Wi-Fi Display 信息元素）来发现无线显示器。这个 IE 只能由系统的 `wpa_supplicant` 广播，而应用层要设置它必须调用：

```kotlin
WifiP2pManager.setWFDInfo(channel, wfdInfo, listener)
```

该方法需要 `android.permission.CONFIGURE_WIFI_DISPLAY` 权限，保护级别是 **signature**，普通应用无论如何都拿不到。实测日志：

```
SecurityException: Wifi Display Permission denied for uid = 10392
    at android.net.wifi.p2p.WifiP2pManager.setWFDInfo(WifiP2pManager.java:1331)
```

绕过办法是用 root 直接和 `wpa_supplicant` 的控制 socket 对话，跳过 framework 的权限检查。本应用内置了 `libwfdctl.so`（源码 `app/src/main/cpp/wfdctl.c`）来做这件事，由 `WfdRootHelper` 通过 `su` 拉起。

对于系统不带屏幕镜像功能的设备，**没有 root 就无法使用此功能**，其余投屏方式（AirPlay / DLNA）不受影响。

## 设备前置条件

逐项确认，任何一条不满足都无法工作：

**1. 有可用的 root**

```bash
adb shell "su -c 'id'"
```

期望输出包含 `uid=0(root)`。

**2. wpa_supplicant 编译了 Wi-Fi Display 支持**

```bash
adb shell "su -c 'grep -ac WFD_SUBELEM /vendor/bin/hw/wpa_supplicant'"
```

返回非 0 即表示支持。旧机型的 `wpa_supplicant` 可能在 `/system/bin/` 下。

**3. 存在 wpa_supplicant 控制 socket**

```bash
adb shell "su -c 'ls /data/misc/wifi/sockets/ /data/vendor/wifi/wpa/sockets/ 2>/dev/null'"
```

**应用启动前这里是空的**：`p2p-dev-wlan0` 只有调用过 `WifiP2pManager.initialize()` 之后才出现，`p2p0` 则要等到投屏组建成。应用会自动探测两个目录（新版本 Android 在 `/data/vendor/wifi/wpa/sockets/`，旧版本在 `/data/misc/wifi/sockets/`）。

真正需要的是 **`p2p-dev-*`**——WFD 广播只能从 P2P device 接口发出去，写进 `wlan0`（STA）不会有广播，写进 `p2p0`（组接口）则只有在组建成之后才存在。

## 使用方法

1. 安装并启动本应用，在系统弹出授权时**授予 root 权限**；Android 13 及以上还需要授予「附近设备」(`NEARBY_WIFI_DEVICES`) 权限，否则扫描和建组会被系统直接拒绝
2. 应用按这个顺序工作：初始化 P2P（让 `p2p-dev-*` 出现）→ 把 WFD IE 注入 `p2p-dev-*` → 把 Group Owner 意向提到最大 → 以 Group Owner 身份创建 Wi-Fi Direct 组 → 组建成后再次注入 IE，让它落到 G/O 广播里 → 等待 Windows 连接
3. Windows 上按 `Win + K`，在列表中选择本设备（显示名与 AirPlay / DLNA 一致）
4. 首次连接需要在手机上确认配对

**接收端必须是 Group Owner**，这是设备能被手机搜到的前提：手机只扫描携带 WFD 信息元素的 G/O 广播。把 WFD IE 写进 socket 却不建组，设备在列表里永远不会出现——这条链路上任何一步缺失都表现为「搜不到设备」。

成功时的日志：

```
WfdRootHelper: WFD: primary sink advertised via /data/vendor/wifi/wpa/sockets/p2p-dev-wlan0; advertisedRtsp=7236 discoverability=true extended-listen=false verified=true
WfdRootHelper: WFD: group owner intent set to 15 via /data/vendor/wifi/wpa/sockets/p2p-dev-wlan0
WifiDirectManager: Sink Group Owner formed; re-injecting the WFD IE into the G/O beacon
WfdServer: WFD: connected to source at 192.168.49.163:7236
WfdSessionHandler: WFD: negotiated mode = 1920x1080p60
RtpReceiver: First RTP packet: 1328B payloadType=33 payload=1316B
```

`verified=true` 表示读回的值确实等于写进去的 IE；`verified=false` 只是「命令没被拒绝」，不代表广播已经生效。

## 手动注入（调试用）

如果应用内注入失败，可以用仓库里的诊断工具手动完成。工具源码在 [tools/](tools/)，需要用 NDK 交叉编译：

```bash
/path/to/ndk/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android21-clang tools/wfdprobe.c -o tools/wfdprobe
```

推送并执行：

```bash
adb push tools/wfdprobe /data/local/tmp/ && adb shell "chmod 755 /data/local/tmp/wfdprobe"
```

```bash
adb shell "su -c '/data/local/tmp/wfdprobe /data/vendor/wifi/wpa/sockets/p2p-dev-wlan0 \"WFD_SUBELEM_SET 0 000600111c440032\" \"P2P_SET go_int 15\"'"
```

`WFD_SUBELEM_SET` / `WFD_SUBELEM_GET` 是 wpa_supplicant 里唯一的 WFD 标准命令。`SET wifi_display`、`P2P_SET discoverability`、`P2P_EXT_LISTEN` 是非标准扩展，很多固件会回 `UNKNOWN COMMAND`，失败属于正常现象。

其中 `000600111c440032` 是 WFD 设备信息子元素：

| 字段 | 值 | 含义 |
|------|-----|------|
| 长度 | `0006` | 后续 6 字节 |
| 设备信息 | `0011` | bit0-1=01 主 Sink，bit4-5=01 会话可用 |
| 控制端口 | `1c44` | 7236（WFD 标准 RTSP 端口） |
| 最大吞吐 | `0032` | 50 Mbps |

验证是否写入成功（**必须读回比对，不能只看命令有没有被拒绝**）：

```bash
adb shell "su -c '/data/local/tmp/wfdprobe /data/vendor/wifi/wpa/sockets/p2p-dev-wlan0 \"WFD_SUBELEM_GET 0\"'"
```

期望读回 `000600111c440032`。应用里的 `wfdctl` 按这个约定返回退出码：`0` 命令被明确接受，`1` 被明确拒绝，`2` 已发送但从未收到回复（通常是 SELinux 拦掉了回包，**无法确认是否生效**）。

> **注意**：SELinux enforcing 下 `wpa_supplicant` 的**回包**会被拦截（`avc: denied { sendto } ... tcontext=u:r:su:s0`）。此时命令可能已经送达并生效，也可能根本没有——只靠「没报错误」判断不出来。想可靠确认必须用 `WFD_SUBELEM_GET` 读回，或者临时 `setenforce 0`（看完务必 `setenforce 1` 恢复）。

## 撤销广播

WFD IE 和 P2P 组都保存在 `wpa_supplicant`（系统进程）里，**不随应用卸载而消失**。应用正常退出时会自动撤销；如果残留，手动清理：

```bash
adb shell "su -c '/data/local/tmp/wfdprobe /data/vendor/wifi/wpa/sockets/p2p-dev-wlan0 \"WFD_SUBELEM_SET 0\" \"SET wifi_display 0\" \"P2P_GROUP_REMOVE p2p0\"'"
```

最简单的办法是**关闭再打开 Wi-Fi**，或重启设备。

## 实现要点

排查过程中确认的几个关键事实，改动相关代码前请留意：

**1. RTSP 连接方向与直觉相反**

WFD 里 **Source（Windows）才是 RTSP 监听方**，Sink 必须主动连到 Source 的 7236 端口。如果 Sink 也在监听等待，双方会互等约 60 秒后超时断开（Windows 显示「连接失败」）。

**2. Sink 需要主动发起 M2 / M6 / M7**

```
M1  Source → Sink   OPTIONS
M2  Sink   → Source OPTIONS          ← Sink 主动发
M3  Source → Sink   GET_PARAMETER    ← 必须回 wfd_client_rtp_ports
M4  Source → Sink   SET_PARAMETER
M5  Source → Sink   SET_PARAMETER (wfd_trigger_method: SETUP)
M6  Sink   → Source SETUP            ← Sink 主动发
M7  Sink   → Source PLAY             ← Sink 主动发
```

Windows 在 M3 里会一口气询问 27 项能力（含 `wfd2_*`、`intel_*`、`microsoft_*` 私有扩展），但**只回标准的 7 项它照样接受**，无需实现私有扩展。

**3. `wfd_presentation_URL` 有两个值**

M4 里的格式是 `rtsp://<ip>/wfd1.0/streamid=0 none`，**只能取第一个**。整串拿去拼 SETUP 请求行会多出一段，Windows 判定为畸形请求直接断链。

**4. 视频是 MPEG-2 TS 封装，不是裸 H.264**

RTP 负载类型为 **33（MP2T）**，每包 1328 字节 = 12 字节 RTP 头 + 7 × 188 字节 TS 包。需要先做 TS 解复用（PAT → PMT → 视频 PID → PES）才能拿到 H.264。

**5. 不要用播放器解这路流**

实测用 ExoPlayer 的 `ProgressiveMediaSource` 会引入 **10 秒以上延迟** —— 它把实时流当成从位置 0 开始的点播文件，没有直播边缘概念，播放速率和数据到达速率都是 1×，永远追不上。正确做法是解出访问单元直接送 `MediaCodec`，收到即解码、解完即送显。

## 延迟参考

当前实现的端到端延迟约 **60–130 ms**，构成如下：

| 环节 | 典型耗时 |
|------|---------|
| Windows 屏幕捕获 + H.264 编码 | 30–60 ms |
| 一帧的 PES 组装等待（等于帧间隔） | 16 ms @60fps |
| Wi-Fi Direct 传输 | 5–20 ms |
| MediaCodec 解码 + 送显 | 20–50 ms |

用于浏览网页、看文档、当参考屏完全可用；**用于游戏或视频剪辑会明显难受**，这是 Miracast 协议本身的下限，任何实现都突破不了 Windows 编码器那一段。

## 常见问题

**Windows 搜不到设备**

先看 WebUI 的「诊断」面板（或 `GET /api/diagnostics` 的 `miracast` 字段），它会把整条链路的状态摊开：

| 字段 | 含义 | 不是预期的值说明什么 |
|------|------|----------------------|
| `miracast.advertiseState` | `NOT_ATTEMPTED` / `WFD_ADVERTISED` / `WFD_ADVERTISED_GO_INTENT_MISSING` / `WFD_ADVERTISE_FAILED` | 广播环节没跑通，先解决这条 |
| `miracast.advertised` | 至少有一个 socket 没拒绝 WFD 广播命令 | `false` = 所有接口都拒绝了，通常是驱动不支持 WFD |
| `miracast.verified` | 读回值等于写入的 IE | `false` 时「广播」只是猜测，不算数 |
| `miracast.socketKind` | 广播落到的接口类型 | 必须是 `P2P_DEV`；`STA_FALLBACK` 发出的 IE 不会变成 P2P 广播 |
| `miracast.groupOwnerIntentConfigured` | `P2P_SET go_int 15` 是否被接受 | `false` = 可能抢不到 GO，手机扫不到 |
| `miracast.detail` | 本次广播的完整描述，含 `verified=` / `socket=` / 接口类型告警 | 直接读这一段通常就能定位 |
| `miracast.checks.wfdctlBinary` | 帮助程序 `libwfdctl.so` 的实际路径 | `MISSING` = 二进制既没被系统解压也解不出来，整条链路直接空转 |
| `miracast.checks.nativeLibOnDisk` | 系统有没有把 native 库解压到磁盘 | `false` 说明 `extractNativeLibs` 关着，应用会自己从 APK 解到 `filesDir` |
| `miracast.checks.wifiEnabled` | Wi-Fi 射频开关 | `false` = P2P 不会初始化，`p2p-dev-*` 永远不存在 |
| `miracast.checks.wifiDisplaySetting` | `settings get global wifi_display_on` | `0` 时部分固件直接关掉 WFD 发现；应用会在广播前写成 `1` |
| `miracast.checks.p2pDevSockets` | 实际找到的 `p2p-dev-*` | `NONE` 说明 P2P 还没初始化成功，或 Wi-Fi Direct 权限没给 |
| `miracast.checks.wfdSupport` | 固件的 `wpa_supplicant` 是否包含 `WFD_SUBELEM` 符号 | 出现 `no WFD_SUBELEM symbol` 就是驱动编译时没开 Wi-Fi Display |
| `miracast.checks.wfdSubelemReadback` | 现场 `WFD_SUBELEM_GET 0` 的原始输出 | 与写入的 `000600111c440032` 比对 |
| `miracast.checks.p2pGet` | 现场 `P2P_GET` 的原始输出 | 里面能看到当前 `group_owner_intent` |
| `miracast.checks.selinux` | `getenforce` 的结果 | `Enforcing` 时回包可能被拦，只能靠读回判断 |
| `miracast.checks.groupInterface` | `p2p0` 的 IPv4 地址 | 提示 `p2p0 does not exist` = 组没建成，手机一定搜不到 |

按顺序排查：root 授权 → 附近设备权限（Android 13+）→ `p2p-dev-*` 是否存在 → `verified` 是否为真 → `groupOwnerIntentConfigured` 是否为真 → `p2p0` 是否已有地址。

手动命令对照：

```bash
# 广播是否真的在里面
adb shell "su -c '/data/local/tmp/wfdprobe /data/vendor/wifi/wpa/sockets/p2p-dev-wlan0 \"WFD_SUBELEM_GET 0\"'"
# GO 意向
adb shell "su -c '/data/local/tmp/wfdprobe /data/vendor/wifi/wpa/sockets/p2p-dev-wlan0 \"P2P_GET go_int\"'"
# 组是否建成
adb shell "su -c 'ip -4 addr show p2p0'"   # 期望看到 192.168.49.1
```

**连上后几秒断开，手机无反应**

多半是 RTSP 协商失败。抓日志看断在哪条 M 消息：

```bash
adb logcat -s WfdSessionHandler WfdServer RtpReceiver
```

**画面花屏**

看日志里的丢包统计。`lost` 持续增长说明 Wi-Fi Direct 链路质量差，可以在 `WfdSessionHandler.VIDEO_FORMATS` 里把 CEA 位图改成 720p60（`0x00000040`）降低码率。

**卸载应用后 Windows 仍能搜到**

见上文「撤销广播」—— 状态在系统进程里，不随应用消失。
