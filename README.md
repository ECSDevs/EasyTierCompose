# EasyTier Compose

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202026.02-blue.svg)](https://developer.android.com/jetpack/compose)

一个基于 Jetpack Compose 的 Android 客户端，封装 [EasyTier](https://github.com/ECSDevs/EasyTier)（Rust + Tokio 实现的去中心化 VPN）核心，提供原生的配置管理、网络编排与两种 TUN 接入模式。

> ✨ Decentralized mesh VPN, reborn as a first-class Android citizen.

## 功能特性

- 📱 **纯 Compose UI** — Material 3 + 自适应布局（手机 NavigationBar / 平板 NavigationRail）
- 🔌 **双 TUN 模式** — `VPN_SERVICE`（免 root，系统 VPN 授权）与 `ROOT_TUN`（root 进程 + 真实 `easytier0` 接口）
- 🛡️ **JNI 门面架构** — 应用层不直接接触原生符号，统一通过 `core.EasyTierJni` 访问
- 💾 **DataStore 持久化** — 多 profile 管理，含损坏数据自动恢复
- 🎛️ **结构化 & 高级 TOML** — 表单输入或直接编辑 TOML（VPN Service 模式）
- 🌐 **Magic DNS** — VPN Service 模式下支持 EasyTier 内置 DNS（`100.100.100.101`）
- ⚡ **协程编排** — `SupervisorJob + Mutex` 串行化运行时状态机，避免竞态

## 系统要求

| 项 | 值 |
|---|---|
| Android minSdk | 24（Android 7.0） |
| Android targetSdk | 36 |
| Android compileSdk | 37 |
| ABI | `arm64-v8a` / `armeabi-v7a` / `x86_64` |
| Gradle | 9.5.0 |
| AGP | 9.3.1 |
| JDK | 21（Daemon）/ 11（source/target compat） |
| NDK | 3.22.1（仅用于编译 `root_tun_jni`） |

Root TUN 模式需要设备已 root 并授予应用 root 权限。

## 架构概览

```
app/src/main/java/cc/ptoe/easytier/compose/
├── MainActivity.kt              # 单 Activity，手动注入
├── core/                        # JNI 门面 + 运行时编排器 + 配置生成
│   ├── EasyTierJni.kt
│   ├── EasyTierRuntimeCoordinator.kt
│   └── ProfileConfig.kt
├── data/                        # 模型 + DataStore 持久化
│   ├── EasyTierModels.kt
│   └── ProfileRepository.kt
├── transport/                   # TUN 抽象 + 两种实现
│   ├── RuntimeTransport.kt
│   ├── vpn/                     # VpnService 实现（免 root）
│   └── root/                    # libsu RootService 实现（root）
└── ui/                          # Compose UI + ViewModel + Theme
    ├── EasyTierApp.kt
    ├── EasyTierViewModel.kt
    └── theme/
```

### 双 TUN 模式对比

| 维度 | VPN_SERVICE | ROOT_TUN |
|---|---|---|
| 实现 | `VpnService.Builder` | `/dev/net/tun` + netlink |
| 权限 | 系统 VPN 授权弹窗 | root（libsu RootService IPC） |
| 前台服务 | `specialUse` | 否（root 进程内运行） |
| Magic DNS | 支持 | 不支持 |
| 高级 TOML | 支持 | 不支持 |
| 设备名 | Android VPN（虚拟） | `easytier0`（真实接口） |

详见 [AGENTS.md](AGENTS.md) 中的"两种 TUN 模式"章节。

## 快速开始

### 1. 克隆仓库（含 submodule）

```bash
git clone --recurse-submodules <repo-url>
cd EasyTierCompose

# 若已克隆但未拉取 submodule
git submodule update --init --recursive
```

### 2. 构建与安装

```bash
# Debug 构建 + 安装到已连接设备
./gradlew :app:installDebug

# Release APK
./gradlew :app:assembleRelease
```

首次构建时，Gradle 会通过 [foojay-resolver](https://github.com/gradle/foojay-resolver) 自动下载 JDK 21 用于 Daemon。

### 3. 运行单元测试

```bash
./gradlew :app:testDebugUnitTest
```

测试覆盖 `TomlConfigBuilder` 与 `ProfileValidator`，通过 `NativeConfigParser` 桩绕过真实 JNI。

## 原生库说明

本项目包含两个原生库，来源与构建方式不同：

| 库 | 来源 | 构建方式 |
|---|---|---|
| `libroot_tun_jni.so` | `app/src/main/cpp/root_tun_jni.cpp` | Gradle `externalNativeBuild`（CMake）自动编译 |
| `libeasytier_android_jni.so` | `EasyTier-Core/easytier-contrib/easytier-android-jni/` | 预构建，已置于 `app/src/main/jniLibs/<abi>/` |

如需重新构建 `libeasytier_android_jni.so`（需要 Rust + Android NDK）：

```bash
cd EasyTier-Core/easytier-contrib/easytier-android-jni
./build.sh
# 将 target/android/<abi>/libeasytier_android_jni.so 拷贝到
# app/src/main/jniLibs/<abi>/
```

**不要将 `libeasytier_android_jni.so` 纳入 Gradle 构建。**

## 权限说明

`AndroidManifest.xml` 声明以下权限：

- `INTERNET` — 网络通信
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` — VPN 前台服务
- `POST_NOTIFICATIONS` — VPN 状态通知
- `BIND_VPN_SERVICE`（服务级）— 仅系统可绑定 `EasyTierVpnService`

VPN 前台服务的 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 标注为 `EasyTier virtual network transport`。

## 开发指引

- **JNI 调用**：只使用 `cc.ptoe.easytier.compose.core.EasyTierJni`，禁止直接调用 `com.easytier.jni.EasyTierJNI`
- **不自动连接**：`MainActivity.onResume()` 有意留空，连接必须由用户显式触发
- **运行中不可编辑/删除**：profile 处于 `STARTING`/`RUNNING` 状态时，编辑、删除、切换 TUN 模式均被拒绝
- **netlink 路由**：TUN 远端网络路由必须使用 `RT_SCOPE_UNIVERSE`，否则对任意 CIDR 返回 `EINVAL`
- **IPv4 解析**：EasyTier 序列化的 `addr` 是 `u32::from_be_bytes` 的 big-endian 整数，需按大端拆解

完整开发约定见 [AGENTS.md](AGENTS.md)。

## 技术栈

- **Kotlin** 2.2.10 + Coroutines 1.10.2 + Serialization 1.9.0
- **Jetpack Compose** BOM 2026.02.01 + Material 3 + Material Icons Extended
- **AndroidX** Activity Compose 1.13.0 / Lifecycle 2.11.0 / DataStore 1.2.1 / Core KTX 1.19.0
- **libsu** 6.0.0（root IPC + RootService）
- **CMake** 3.22.1 + C++20（root TUN JNI）

## 许可证

本项目基于 [GNU General Public License v3](LICENSE) 发布。

```
EasyTier Compose
Copyright (C) 2026 EasyTier Compose Contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

### 上游许可

封装的 EasyTier 核心（`EasyTier-Core/` submodule，来自 https://github.com/ECSDevs/EasyTier）采用 [LGPLv3](EasyTier-Core/LICENSE)。重新分发或修改该 submodule 内的代码须遵循 LGPLv3 条款。

## 相关链接

- [EasyTier 上游项目](https://github.com/ECSDevs/EasyTier)
- [EasyTier 官方文档](https://easytier.cn/en/)
- [EasyTier Web 控制台](https://easytier.cn/web)
- [Jetpack Compose 文档](https://developer.android.com/jetpack/compose)
- [libsu 项目](https://github.com/topjohnwu/libsu)
