# Pixel OTA Shield (v2.3.8)

> **优雅、高效、纯系统底层的 Google Pixel 原生系统更新拦截 LSPosed / Vector 模块**  
> 专为 **Google Pixel 10 Pro XL (`mustang`, Android 16+)** 量身打造，通用兼容现代 Pixel 设备。  
> **【核心突破】零污染 GMS 架构，完美共存 Play Integrity 三绿 (Strong Integrity) 与原生 OTA 拦截！**

[![GitHub Release](https://img.shields.io/badge/release-v2.3.8-blue.svg)](https://github.com/hanhan-ggit/PixelOtaShield/releases)
[![Android](https://img.shields.io/badge/Android-16%2B-green.svg)](https://developer.android.com)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed%20%2F%20Vector-orange.svg)](https://github.com/LSPosed/LSPosed)
[![Play Integrity](https://img.shields.io/badge/Play%20Integrity-MEETS__STRONG__INTEGRITY-brightgreen.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

## 一、为什么选择本方案（设计理念）
在保持 Root 权限、内核模块（KernelSU-Next / APatch / Magisk）及 AVB Green 状态下使用 Pixel 设备时，官方的强制 OTA 更新往往带来破坏 Boot 链条或造成砖机的隐患。

传统的拦截方案通常存在以下痛点：
1. **修改系统 APK / 静态改造**：破坏系统分区只读完整性，触发 AVB 校验失败或导致启动死循环。
2. **全局作用域污染 GMS 导致三绿失效**：
   - 传统模块通常勾选 `com.google.android.gms`，导致 LSPosed / Vector 注入库 (`libvector.so` / `liblspd.so`) 在 Zygote 阶段被强制映射进 `com.google.android.gms.unstable` (DroidGuard) 进程内存中。
   - DroidGuard 内部的 Native C/C++ 扫描器直接读取 `/proc/self/maps`，一旦检出注入痕迹，即使 Java 层返回也会直接导致 `MEETS_DEVICE_INTEGRITY` 和 `MEETS_STRONG_INTEGRITY` 彻底报废。
3. **Hosts / 防火墙粗暴域名阻断**：导致 GMS 更新服务反复抛出网络超时异常（`SocketTimeoutException`），触发系统退避重试风暴（Exponential Backoff），长期持有 CPU 唤醒锁（WakeLock），引起严重发热与耗电。

**Pixel OTA Shield v2.3.8 采用全新“零污染 GMS 架构”**：
- **仅作用于系统框架层 (`android` / `system_server`)**，对 GMS 零修改、零注入！
- **完美保全 AlwaysStrong / TrickyStore 三绿**（`MEETS_BASIC_INTEGRITY`、`MEETS_DEVICE_INTEGRITY`、`MEETS_STRONG_INTEGRITY` 均 100% 满绿点亮）。
- **根源状态锁定**：拦截系统层 `SystemUpdateManagerService.retrieveSystemUpdateInfo()`，向所有系统组件和设置中心恒定报告 `STATUS_IDLE (1)`。
- **杜绝一切 OTA 通知与静默下载**：系统绝不弹出更新升级提示，绝不下载 Android 17 安装包。
- **原生极致体验**：设置中心正常显示“Your system is up to date”，点击“Check for update”平滑刷新时间戳，无横线跳动，无按钮置灰卡死。

---

## 二、系统架构与拦截机制

```
┌─────────────────────────────────┐      ┌─────────────────────────────────┐
│     客户端与系统界面层          │      │       操作系统核心服务          │
│ (Settings / SafetyCenter / UI)  │      │       (system_server)           │
└────────────────┬────────────────┘      └────────────────┬────────────────┘
                 │                                        │
                 │────── 1. Binder: retrieveInfo() ───────>│【接入点 A: SystemUpdateManagerService】
                 │                                        │ 强制锁定系统更新公告板：
                 │<───── 返回 STATUS_IDLE (1) ────────────│ 1. 客户端读取恒定返回空闲/最新
                 │  (UI 自然渲染最新版本状态与补丁日期)   │ 2. 拦截中和任何 updateSystemUpdateInfo 写入
                 │                                        │ 3. 彻底阻断 STATUS_WAITING_FOR_REBOOT 广播
                 │                                        │
┌────────────────┴────────────────────────────────────────┴────────────────┐
│                          Google Play 服务 (GMS)                          │
├──────────────────────────────────────────────────────────────────────────┤
│ 【零注入隔离区】                                                        │
│ • GMS 完全不纳入 LSPosed / Vector 作用域                                 │
│ • com.google.android.gms.unstable (DroidGuard) 零 Native 内存污染        │
│ • AlwaysStrong / TrickyStore / Keystore 硬件级三绿完整认证保障通过       │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 三、真机实测验证 (Google Pixel 10 Pro XL, Android 16)

在 **Google Pixel 10 Pro XL (`mustang`, Android 16)** 上的真机实测数据：

| 测试项目 | 测试手段 | 测试结果 | 说明 |
| :--- | :--- | :--- | :--- |
| **Play Integrity 三绿检验** | 使用 Ma Integrity Check 真实验证 | **BASIC + DEVICE + STRONG 全绿** | 彻底解除对 GMS 的内存注入，三项认证 100% 通过 |
| **系统更新通知拦截** | 监测系统状态广播与通知中心 | **0 提示 / 0 弹窗** | 底层公告板恒定锁定 `STATUS_IDLE (1)`，无任何更新提醒 |
| **设置更新界面体验** | 打开 设置 -> 系统 -> 系统更新 | **显示最新版本** | 显示“Your system is up to date”，版本号与补丁保持原生 |
| **手动检查按钮交互** | 点击 "Check for update" 按钮 | **平滑刷新时间戳** | 按钮保持高亮可点，无无限转圈卡死，无横向进度条跳动 |
| **耗电与休眠** | 息屏观察 WakeLock 与 CPU 状态 | **正常进入 Doze** | 无死循环退避重试，零额外功耗 |

---

## 四、快速上手与安装配置

### 1. 下载安装包
您可以直接从仓库的 [releases/](releases/PixelOtaShield_v2.3.8.apk) 目录或 [GitHub Releases 页面](https://github.com/hanhan-ggit/PixelOtaShield/releases) 下载编译并签名的最新 APK：
- **`PixelOtaShield_v2.3.8.apk`**

### 2. 勾选作用域（至关重要）
安装后，打开 **Vector / LSPosed 管理器**，在模块列表中启用 **Pixel OTA Shield**：
- ✅ **系统框架** (`android`) —— **仅勾选此项！**
- ❌ **请勿勾选 Google Play 服务 (`com.google.android.gms`)** —— 保持 GMS 纯净零注入，是保障 Play Integrity 三绿的核心前提。

### 3. 重启设备生效
激活后，建议软重启或完整重启一次手机使 Hook 全面生效：
```bash
adb shell svc power reboot
```

---

## 五、开源协议
本项目基于 [Apache 2.0 License](LICENSE) 开源。仅供 Android 系统安全研究与个人合法设备管理使用。
