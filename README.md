# Pixel OTA Shield (v2.3.3)

> **优雅、高效、纯通信层的 Google Pixel 原生系统更新拦截 LSPosed 模块**  
> 专为 **Google Pixel 10 Pro XL (`mustang`, Android 16+)** 量身打造，通用兼容现代 Pixel 设备。

[![GitHub Release](https://img.shields.io/badge/release-v2.3.3-blue.svg)](https://github.com/hanhan-ggit/PixelOtaShield/releases)
[![Android](https://img.shields.io/badge/Android-16%2B-green.svg)](https://developer.android.com)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed%20%2F%20Zygisk-orange.svg)](https://github.com/LSPosed/LSPosed)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

## 一、 为什么选择本方案（设计理念）

在保留 Root 权限、内核模块（KernelSU-Next / APatch / Magisk）及 AVB Green 状态下使用 Pixel 设备时，官方的强制 OTA 更新往往带来破坏 Boot 链条或造成砖机的隐患。

传统的拦截方案通常存在以下痛点：
1. **修改系统 APK / 静态改包**：破坏系统分区只读完整性，触发 AVB 校验失败或导致启动死循环。
2. **Hosts / 防火墙粗暴域名阻断**：导致 GMS 更新服务反复抛出网络超时异常（`SocketTimeoutException`），触发系统退避重试风暴（Exponential Backoff），长期持有 CPU 唤醒锁（WakeLock），引起严重发热与耗电。
3. **传统 UI Hook**：随系统更新界面改版极易失效，且界面往往停留在漫长且无限的“正在检查更新...”转圈卡死状态，或频繁弹出冗余 Toast。

**Pixel OTA Shield（方案 2：纯通信层双向闭环）彻底革新了这一过程**：
- **不修改任何系统只读文件**，不影响 AVB 启动链。
- **纯通信与调度层拦截**：向上层与 UI 恒定伪装为“系统已是最新版本”。
- **平滑自然动效**：点击“Check for update”仅旋转约 1.2 秒自然模拟过渡，随后平滑收起。
- **零弹窗干扰**：全局消除更新类 Toast 弹窗，带来纯净原生体验。
- **零额外耗电**：底层任务调度直接返回成功终止，绝不持有 WakeLock，完美支持 Doze 深度休眠。

---

## 二、 系统架构与拦截机理

```
┌─────────────────────────────────┐       ┌─────────────────────────────────┐
│     客户端与系统界面群          │       │        操作系统核心服务         │
│  (Settings / SafetyCenter / UI) │       │        (system_server)          │
└────────────────┬────────────────┘       └────────────────┬────────────────┘
                 │                                         │
                 │────── 1. Binder: retrieveInfo() ───────>│
                 │<───── 返回 STATUS_IDLE (1) ─────────────│ 【接入点 A: ISystemUpdateManager】
                 │   (UI 自然渲染最新版本状态与补丁日期)   │ • 锁定读取返回恒定空闲/最新
                 │                                         │ • 拦截写入中和为 STATUS_IDLE
                 │                                         │
                 │                                         │<── 2. Binder: updateSystemUpdateInfo() (中和)
                 │                                         │
                 │                                         │
┌────────────────┴─────────────────────────────────────────┴────────────────┐
│                           谷歌服务框架 (GMS 核心)                          │
├───────────────────────────────────────────────────────────────────────────┤
│ 【接入点 B: 核心调度与网络彻底阻断】                                      │
│                                                                           │
│ • Point B1: SystemUpdateService (onStartCommand)                          │
│   拦截启动 Intent，直接返回 START_NOT_STICKY，不发起检查任务               │
│                                                                           │
│ • Point B2: ChimeraUpdateJobService (onStartJob)                          │
│   直接返回 false，通知 JobScheduler 任务已完成且禁止重试（释放 WakeLock） │
│                                                                           │
│ • Point B3: SafetyCenterManager (setSafetySourceData)                     │
│   拦截 GoogleSecurityUpdates 数据源，强制伪装安全状态为 OK (100)          │
│                                                                           │
│ • Point B5: SharedPreferences (update.storage.xml)                        │
│   持久化存储强力清洗，彻底杜绝本地缓存写入新固件描述或下载链接             │
│                                                                           │
│ • Point B6: CheckForConfigUpdateOperation (goxp.f)                        │
│   根源切断：向 Google OTA 服务器发起查询的方法直接置空（0 网络请求）       │
│                                                                           │
│ • Point B7: GlifExpressiveOtaStatusFragment (gphv.W)                      │
│   UI 动效智能接管：点击更新后执行 1.2s 平滑过渡，随后自然收回最新状态      │
│                                                                           │
│ • Point B8: SystemUpdateStatus (gphp.F)                                   │
│   状态机清洗：强制重置为 STATUS_IDLE，防止界面错乱                       │
│                                                                           │
│ • Point B9: Toast 智能拦截器                                              │
│   拦截 GMS 内部含有 "up to date" / "最新版本" 的弹窗，保持界面极简静默    │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 三、 实测数据与验证

在 **Google Pixel 10 Pro XL (mustang, Android 16)** 上的真机实测数据：

| 测试项目 | 测试手段 | 测试结果 | 说明 |
| :--- | :--- | :--- | :--- |
| **网络 Socket 监测** | 实时监控 GMS 进程网络文件描述符 | **0 Socket 打开** | 点击更新前后，均未发起任何向 Google 的 TCP/TLS 请求 |
| **网络层阻断** | 跟踪 `goxp.f` (CheckForConfigUpdate) | **100% 拦截置空** | 杜绝新版本（如 Android 17）信息流入设备 |
| **本地存储隔离** | 检查 `/data/user/0/.../shared_prefs/` | **文件不存在** | `update.storage.xml` 恒为空，无下载 URL 缓存 |
| **交互动画** | 点击底部 "Check for update" | **1.2s 平滑过渡** | 告别漫长转圈，状态自然收起，无多余 Toast 弹窗 |
| **耗电与休眠** | 息屏观察 WakeLock 与 CPU 状态 | **正常进入 Doze** | JobScheduler 任务直接返回终止，无死循环退避重试 |
| **常规网络功能** | Ping 测试与 Google Play 商店使用 | **0% 丢包 / 完全正常** | 仅阻断系统 OTA，完全不影响应用商店、AI 模型与网页访问 |

---

## 四、 快速上手与安装配置

### 1. 下载安装包
您可以直接从仓库的 [releases/](releases/PixelOtaShield_v2.3.3.apk) 目录或 [GitHub Releases 页面](https://github.com/hanhan-ggit/PixelOtaShield/releases) 下载编译并签名的最新 APK：
- **`PixelOtaShield_v2.3.3.apk`**

### 2. 勾选 LSPosed 作用域（非常重要）
安装后，打开 **LSPosed 管理器**，在模块列表中启用 **Pixel OTA Shield**，勾选以下推荐作用域：
- ☑ **系统框架** (`android`) —— 激活接入点 A
- ☑ **Google Play 服务** (`com.google.android.gms`) —— 激活接入点 B

> **提示**：旧版方案中的 `com.android.settings` 与 `com.google.android.permissioncontroller` **无需**勾选。

### 3. 重启设备生效
由于接入点 A 涉及系统框架（`system_server`），建议软重启或完整重启一次手机使 Hook 全面生效：
```bash
adb shell svc power reboot
```

---

## 五、 项目源码结构

```text
PixelOtaShield/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml                 # 模块清单配置与 LSPosed 标记
│   │   ├── assets/xposed_init                  # Xposed 入口声明
│   │   └── java/com/pixel/otashield/
│   │       └── MainHook.java                   # 核心 Hook 实现（接入点 A 与 B1~B9）
│   └── build.gradle.kts                        # 子模块构建脚本
├── releases/
│   └── PixelOtaShield_v2.3.3.apk               # 最新正式发布安装包（已签名）
├── build.gradle.kts                            # 根构建脚本
├── settings.gradle.kts                         # 模块配置
└── README.md                                   # 项目全景技术文档
```

---

## 六、 开源协议

本项目基于 [Apache 2.0 License](LICENSE) 开源。仅供 Android 系统安全研究与个人合法设备管理使用。
