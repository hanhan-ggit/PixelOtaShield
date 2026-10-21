# Pixel OTA Shield (双版本发布: v2.3.7 / v2.3.8)

> **优雅、高效、专为 Google Pixel 打造的原生系统更新拦截 LSPosed / Vector 模块**  
> 适配 **Google Pixel 10 Pro XL (`mustang`, Android 16+)** 及现代各代 Pixel 机型。

[![GitHub Release](https://img.shields.io/badge/release-v2.3.7%20%7C%20v2.3.8-blue.svg)](https://github.com/hanhan-ggit/PixelOtaShield/releases)
[![Android](https://img.shields.io/badge/Android-16%2B-green.svg)](https://developer.android.com)
[![Framework](https://img.shields.io/badge/Framework-LSPosed%20%2F%20Vector-orange.svg)](https://github.com/LSPosed/LSPosed)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

## 🚀 双版本定位与选择指南

为了满足不同用户的核心诉求，我们同时发布并维护两个经过严格实测的版本：

| 对比维度 | **v2.3.7 版本 (完整双层闭环 / 视觉完美版)** | **v2.3.8 版本 (零污染 GMS / 三绿保障版)** |
| :--- | :--- | :--- |
| **核心优势** | 🎨 **视觉极致原生**：点进最深层页面也显示最新版本 | 🟢 **安全认证全满**：**Play Integrity 三绿满绿点亮** |
| **Play Integrity 表现** | ⚠️ 仅通过 `BASIC`（三绿失效，因 Vector 注入 GMS） | 🟢 **`BASIC` + `DEVICE` + `STRONG` 全部通过** |
| **系统更新通知/红点** | 🛡️ **彻底屏蔽**（0 通知、0 弹窗、0 红点） | 🛡️ **彻底屏蔽**（0 通知、0 弹窗、0 红点） |
| **后台自动静默下载** | 🛡️ **绝对不会下载**（双层物理阻断） | 🛡️ **绝对不会下载**（底层状态机锁死） |
| **内层详情页呈现** | 强制改写为“已是最新版本”，1.2秒平滑过渡，无横线 | 显示 GMS 原生状态（显示“有更新”及大小，但不下载） |
| **作用域勾选要求** | ✅ **系统框架 (`android`)** + **Google Play 服务 (`com.google.android.gms`)** | ✅ **仅勾选系统框架 (`android`)**<br>❌ **切勿勾选 Google Play 服务** |
| **推荐人群** | 不使用 Google 钱包闪付/重度金融，追求极致视觉闭环者 | **重度使用 Google Wallet、外服银行支付、要求三绿者** |
| **下载地址** | [📦 下载 v2.3.7 APK](https://github.com/hanhan-ggit/PixelOtaShield/releases/tag/v2.3.7) | [📦 下载 v2.3.8 APK](https://github.com/hanhan-ggit/PixelOtaShield/releases/tag/v2.3.8) |

---

## 一、版本深度解析

### 1. v2.3.7 (完整双层闭环 / 视觉完美版)
* **设计目标**：向用户呈现 100% 毫无破绽的“系统已是最新版本”UI。
* **特性**：
  - 拦截 GMS `CheckForConfigUpdate` 通信，断绝 Android 17 元数据拉取；
  - 挂载 1.2 秒平滑回归动效，解决原生更新点击转圈卡死问题；
  - 隐藏顶部横线跳动进度条，按钮保持高亮并可重复点击；
  - 彻底过滤所有更新类 Toast 冗余弹窗；
* **注意**：由于 Vector 需要注入 GMS 进程以改写 UI，DroidGuard 扫描自身内存时会发现外来 `.so`，导致 Play Integrity 强认证（AlwaysStrong）降级为单绿。

### 2. v2.3.8 (零污染 GMS 架构 / Play Integrity 三绿保障版)
* **设计目标**：在不触碰 GMS 的前提下，彻底杜绝系统更新推送与后台偷跑下载，保全 AlwaysStrong / TrickyStore 硬件级三绿。
* **特性**：
  - 仅作用于 `system_server` 的 `SystemUpdateManagerService`，状态恒定报告 `STATUS_IDLE (1)`；
  - 对 GMS 实行 **0 侵入、0 内存映射**，`com.google.android.gms.unstable` 保持 100% 纯净；
  - 外层设置列表、安全中心以及通知栏完全静默，绝不弹窗、绝不在后台偷跑下载 3.55GB 固件包。

---

## 二、快速安装与配置

1. 从 [Releases 页面](https://github.com/hanhan-ggit/PixelOtaShield/releases) 下载所需版本安装包；
2. 打开 **Vector / LSPosed 管理器**，启用模块；
3. **按照您选择的版本勾选作用域**：
   * **v2.3.7**：勾选 `系统框架` 和 `Google Play 服务`；
   * **v2.3.8**：**仅勾选** `系统框架`；
4. 软重启手机或执行 `adb shell svc power reboot` 生效。

---

## 三、开源协议
本项目基于 [Apache 2.0 License](LICENSE) 开源。仅供 Android 系统安全研究与个人合法设备管理使用。
