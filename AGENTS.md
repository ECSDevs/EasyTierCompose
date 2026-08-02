# AGENTS.md

本文件为在此仓库中工作的 AI 代理提供指引。项目所有代码注释与提交信息以英文为主，本说明保留关键技术术语原文。

## 项目概览

EasyTierCompose 是一个单 Activity 的 Jetpack Compose Android 应用，封装 [EasyTier](https://github.com/ECSDevs/EasyTier)（Rust + Tokio 实现的去中心化 VPN）核心。应用本身不编译 Rust 核心，而是通过 JNI 调用预构建的 `libeasytier_android_jni.so`。

- 应用包名 / namespace：`cc.ptoe.easytier.compose`
- 应用显示名：EasyTier
- 根工程名（settings.gradle.kts）：`EasyTier`
- 单模块：`:app`
- EasyTier-Core 为 git submodule（`EasyTier-Core/`，指向 https://github.com/ECSDevs/EasyTier.git），仅作为预构建 `.so` 的上游来源，不参与 Gradle 构建

## 构建工具链

| 项 | 版本 / 配置 |
|---|---|
| AGP | 9.3.1 |
| Kotlin | 2.2.10 |
| Compose BOM | 2026.02.01 |
| Gradle | 9.5.0 |
| Gradle Daemon JVM | 21（由 `gradle/gradle-daemon-jvm.properties` 固定，通过 foojay-resolver 自动下载） |
| Java source/target compatibility | 11 |
| compileSdk | 37 |
| minSdk | 24 |
| targetSdk | 36 |
| NDK（CMake） | 3.22.1 |
| ABI | arm64-v8a, armeabi-v7a, x86_64 |

版本统一在 [gradle/libs.versions.toml](gradle/libs.versions.toml) 中管理。配置缓存已启用（`org.gradle.configuration-cache=true`）。release 构建关闭了 optimization（`enable = false`）。

## 常用命令

```bash
# 构建
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease

# 单元测试（TomlConfigBuilder + ProfileValidator）
./gradlew test
./gradlew :app:testDebugUnitTest

# 安装到设备
./gradlew :app:installDebug

# 查看 dependency tree
./gradlew :app:dependencies
```

`libroot_tun_jni.so` 由 `externalNativeBuild`（CMake）在构建时自动从 `app/src/main/cpp/root_tun_jni.cpp` 编译，无需手动操作。

`libeasytier_android_jni.so` 是预构建产物，已存在于 `app/src/main/jniLibs/<abi>/`。如需重新构建，需进入 `EasyTier-Core/easytier-contrib/easytier-android-jni/` 运行 `build.sh`（需要 Rust + Android NDK），再将产物拷回 `jniLibs`。**不要将其纳入 Gradle 构建。**

## 代码架构

包根：`cc.ptoe.easytier.compose`（`app/src/main/java/cc/ptoe/easytier/compose/`）

### 入口
- [MainActivity.kt](app/src/main/java/cc/ptoe/easytier/compose/MainActivity.kt) — 单 Activity，`enableEdgeToEdge()`，通过 `ViewModelProvider.Factory` 手动注入 `ProfileRepository` 与 `EasyTierRuntimeCoordinator`。**明确不自动连接**（root/consent 流程必须显式触发）。

### core 层
- [EasyTierJni.kt](app/src/main/java/cc/ptoe/easytier/compose/core/EasyTierJni.kt) — 应用拥有的 JNI 门面（facade）。**应用代码统一使用此类，禁止直接调用 `com.easytier.jni.EasyTierJNI`。**
- [EasyTierRuntimeCoordinator.kt](app/src/main/java/cc/ptoe/easytier/compose/core/EasyTierRuntimeCoordinator.kt) — 运行时编排器。`SupervisorJob + Dispatchers.IO` 协程域，`Mutex` 串行化 start/stop，持有 `VpnTunTransport` 与 `RootTunTransport`，轮询 `collectNetworkInfos` 解析虚拟 IPv4 与路由。
- [ProfileConfig.kt](app/src/main/java/cc/ptoe/easytier/compose/core/ProfileConfig.kt) — `ProfileValidator`（字段校验 + 高级 TOML 的原生解析）、`TomlConfigBuilder`（结构化 profile → TOML）、`NativeConfigParser` / `EasyTierNativeConfigParser`。

### data 层
- [EasyTierModels.kt](app/src/main/java/cc/ptoe/easytier/compose/data/EasyTierModels.kt) — `EasyTierProfile`（`@Serializable`）、`TunMode` 枚举（`VPN_SERVICE` / `ROOT_TUN`）、`RuntimeState`、`RuntimeStatus`。
- [ProfileRepository.kt](app/src/main/java/cc/ptoe/easytier/compose/data/ProfileRepository.kt) — DataStore Preferences 持久化（store 名 `easytier_profiles`，key `profiles_v1`），JSON 序列化，含损坏数据自动备份恢复逻辑（`profiles_v1_corrupt`）。

### transport 层
- [RuntimeTransport.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/RuntimeTransport.kt) — `RuntimeTransport` 接口与 `RuntimeEffect` sealed interface（当前仅 `RequestVpnPermission`）。
- `transport/vpn/`：
  - [VpnTunTransport.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/vpn/VpnTunTransport.kt) — VPN 权限申请流，处理 pending start。
  - [EasyTierVpnService.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/vpn/EasyTierVpnService.kt) — `VpnService` 前台服务（`foregroundServiceType="specialUse"`），通过 `Builder()` 建立 tun，调用 `EasyTierJni.setTunFd`。
- `transport/root/`：
  - [RootTunTransport.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/root/RootTunTransport.kt) — 通过 libsu `RootService.bind` IPC 连接 root 进程，轮询 `getStatus()`。
  - [EasyTierRootService.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/root/EasyTierRootService.kt) — `RootService`，在 root 进程内运行 EasyTier + 创建真实 `easytier0` 接口，轮询 DHCP。
  - [RootTunNative.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/root/RootTunNative.kt) — 加载 `libroot_tun_jni`，提供 `create` / `syncRoutes` / `destroy`。
  - [RootModels.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/root/RootModels.kt) — `RootTunSpec`、`RootRuntimeStatus`（`Parcelable`，AIDL 载荷）。

### AIDL
位于 `app/src/main/aidl/cc/ptoe/easytier/compose/transport/root/`：
- `IEasyTierRootService.aidl` — `start(profileId, toml, spec)`、`stop()`、`getStatus()`
- `RootTunSpec.aidl`、`RootRuntimeStatus.aidl` — parcelable 声明

### ui 层
- [EasyTierApp.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/EasyTierApp.kt) — Compose 根。四个 `Destination`（Dashboard / Profiles / Settings / Editor），自适应布局：`screenWidthDp >= 840` 用 `NavigationRail`，否则用 `NavigationBar`。包含 `StatusCard`、`ProfileEditorScreen`、`ListEditorDialog`、`SettingsScreen` 等私有 Composable。
- [EasyTierViewModel.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/EasyTierViewModel.kt) — `combine` 6 个 flow 聚合为 `EasyTierUiState`，`WhileSubscribed(5_000)`。
- `ui/theme/` — `EasyTierTheme`（亮/暗双色方案 + 自定义 `ExpressiveShapes`）、`Color.kt`、`Type.kt`。

### 原生符号所有者
- [com/easytier/jni/EasyTierJNI.kt](app/src/main/java/com/easytier/jni/EasyTierJNI.kt) — `System.loadLibrary("easytier_android_jni")` 的所有者，仅被 `core.EasyTierJni` 引用。

## 两种 TUN 模式

| 维度 | VPN_SERVICE | ROOT_TUN |
|---|---|---|
| 实现 | `VpnService.Builder` | `/dev/net/tun` + netlink（root 进程） |
| 权限 | 系统 VPN 授权弹窗 | root（libsu RootService IPC） |
| 前台服务 | `specialUse` | 否（root 进程内运行） |
| Magic DNS | 支持（`100.100.100.101`） | 不支持 |
| 高级 TOML | 支持 | 不支持（校验阶段即拒绝） |
| 设备名 | Android VPN（虚拟） | `easytier0`（真实接口） |

切换 TUN 模式在 Settings 页通过 Switch 完成；运行中禁止切换。

## EasyTier TOML 配置约定

`TomlConfigBuilder` 生成的结构化 TOML 关键字段：`instance_name`（= profile.id）、`dhcp`、`ipv4`、`listeners`、`routes`、`[[peer]] uri`、`[network_identity]` network_name/network_secret、`[[proxy_network]] cidr`、`[flags]` dev_name（固定 `easytier0`）/ `no_tun=false` / `mtu` / `accept_dns`。

- MTU 默认 1380，有效区间 576..9000。
- `accept_dns` 仅在 VPN_SERVICE 模式且开启 Magic DNS 时为 true。
- 高级 TOML 模式直接透传用户输入，跳过结构化生成，但仍经原生 `parseConfig` 校验。
- 轮询 `collectNetworkInfos` 返回的 JSON 中，`my_node_info.virtual_ipv4` 为 `{address:{addr:<u32 big-endian>}, network_length:<u32>}`，需按大端拆解为点分十进制（见 `EasyTierRootService` 的 `ipv4InetToCidr`）。

## 关键约定与约束

- **JNI 门面**：应用层只调用 `cc.ptoe.easytier.compose.core.EasyTierJni`，不直接接触 `com.easytier.jni.EasyTierJNI`。
- **不自动连接**：`MainActivity.onResume()` 有意留空，连接必须由用户显式触发。
- **运行中不可编辑/删除**：`EasyTierViewModel.saveDraft` / `delete` / `updateTunMode` 均检查 `RuntimeState.STARTING/RUNNING` 并拒绝。
- **协程**：协调器用 `SupervisorJob + Dispatchers.IO` + `Mutex`；ViewModel 用 `viewModelScope`。
- **DataStore**：profile 损坏时自动迁移到 `profiles_v1_corrupt` 并清空主键，避免崩溃。
- **release 构建**：`optimization.enable = false`，R8 实际未启用优化；keep 规则文件 `app/src/main/keepRules/rules.keep` 当前为空模板。
- **自适应布局阈值**：`screenWidthDp >= 840` 切换为双栏 NavigationRail。
- **edge-to-edge**：`enableEdgeToEdge()` + `WindowInsets.safeDrawing`。
- **国际化**：目前 UI 文案为硬编码英文，仅 `app_name` 走 `strings.xml`。

## 测试

- 单元测试 [ProfileConfigTest.kt](app/src/test/java/cc/ptoe/easytier/compose/ProfileConfigTest.kt) 覆盖：结构化 TOML 生成、DHCP、Root TUN 抑制 Magic DNS、高级 TOML 透传、字段校验。测试通过注入 `NativeConfigParser` 桩绕过真实 JNI。
- 修改 `ProfileConfig.kt` 或 `EasyTierModels.kt` 后应运行 `./gradlew :app:testDebugUnitTest`。
- instrumented 测试（`ExampleInstrumentedTest`）为占位。

## 权限

`AndroidManifest.xml` 声明：`INTERNET`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`，以及服务级 `BIND_VPN_SERVICE`。VPN 前台服务 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 标注为 `EasyTier virtual network transport`。

## 易踩坑点

1. **修改 AIDL / Parcelable 后**需重新构建以刷新生成的 Stub。
2. **`libeasytier_android_jni.so` 缺失或架构不匹配**会触发 `UnsatisfiedLinkError`；确认 `jniLibs/<abi>/` 存在且 `abiFilters` 匹配。
3. **Root 模式**依赖设备已 root 且 libsu 能获取 root；`RootService.unbind` 必须在 `bound` 为 true 时才调用。
4. **netlink 路由**：TUN 远端网络路由必须用 `RT_SCOPE_UNIVERSE`（非 `LINK`），否则对任意 CIDR 返回 EINVAL。
5. **IPv4 addr 解析**：EasyTier 序列化的 `addr` 是 `u32::from_be_bytes` 的 big-endian 整数，需拆字节还原。
6. **配置缓存**：修改 build script 后首次构建会重建缓存；某些不兼容的插件可能需关闭。
