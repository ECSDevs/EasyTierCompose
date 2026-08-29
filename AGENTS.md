# AGENTS.md

本文件为在此仓库中工作的 AI 代理提供指引。项目所有代码注释与提交信息以英文为主，本说明保留关键技术术语原文。

## 项目概览

EasyTierCompose 是一个单 Activity 的 Jetpack Compose Android 应用，封装 [EasyTier](https://github.com/ECSDevs/EasyTier)（Rust + Tokio 实现的去中心化 VPN）核心。应用本身不编译 Rust 核心，而是通过 JNI 调用预构建的 `libeasytier_android_jni.so`。

* 应用包名 / namespace：`cc.ptoe.easytier.compose`

* 应用显示名：EasyTier

* 根工程名（settings.gradle.kts）：`EasyTier`

* 单模块：`:app`

* EasyTier-Core 为 git submodule（`EasyTier-Core/`
  ，指向 <https://github.com/ECSDevs/EasyTier.git），仅作为预构建> `.so` 的上游来源，不参与 Gradle 构建

## 构建工具链

| 项                                | 版本 / 配置                                                                |
|----------------------------------|------------------------------------------------------------------------|
| AGP                              | 9.3.1                                                                  |
| Kotlin                           | 2.2.10                                                                 |
| Compose BOM                      | 2026.02.01                                                             |
| Gradle                           | 9.5.0                                                                  |
| Gradle Daemon JVM                | 21（由 `gradle/gradle-daemon-jvm.properties` 固定，通过 foojay-resolver 自动下载） |
| Java source/target compatibility | 11                                                                     |
| compileSdk                       | 37                                                                     |
| minSdk                           | 24                                                                     |
| targetSdk                        | 36                                                                     |
| NDK（CMake）                       | 30.0.16138531 / 4.1.2                                                  |
| ABI                              | arm64-v8a, armeabi-v7a, x86\_64                                        |

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

* [MainActivity.kt](app/src/main/java/cc/ptoe/easytier/compose/MainActivity.kt) — 单 Activity，
  `enableEdgeToEdge()`，通过 `ViewModelProvider.Factory` 手动注入 `ProfileRepository`、
  `EasyTierRuntimeCoordinator` 与 `GlobalSettingsRepository`。**明确不自动连接**（root/consent
  流程必须显式触发）。

### core 层

* [EasyTierJni.kt](app/src/main/java/cc/ptoe/easytier/compose/core/EasyTierJni.kt) — 应用拥有的 JNI
  门面（facade）。**应用代码统一使用此类，禁止直接调用** **`com.easytier.jni.EasyTierJNI`。**

* [EasyTierRuntimeCoordinator.kt](app/src/main/java/cc/ptoe/easytier/compose/core/EasyTierRuntimeCoordinator.kt) —
  运行时编排器。`SupervisorJob + Dispatchers.IO` 协程域，`Mutex` 串行化 start/stop，持有
  `VpnTunTransport` 与 `RootTunTransport`。`start(profile, globalSettings)` 先用
  `withDeviceHostnameIfBlank` 从 Android 设备名补全 hostname，再经 `ProfileValidator` +
  `TomlConfigBuilder` 生成 TOML，按 `tunMode` 分派到 VPN / Root 实现；轮询 `collectNetworkInfos` 解析虚拟
  IPv4、`proxy_cidrs` 远端路由、hostname、NAT 类型与 `peer_route_pairs` Peer 列表（VPN\_SERVICE 直接在
  app 进程轮询；ROOT\_TUN 通过 AIDL 收集 `RootTunTransport.status` flow）。

* [ProfileValidator.kt](app/src/main/java/cc/ptoe/easytier/compose/core/ProfileValidator.kt) —
  `ProfileValidator`：字段校验（IPv4/IPv6 CIDR、MTU、peers、proxy networks、port forwards、VPN
  portal、multiThreadCount、bps 限制等）+ 生成 TOML 的原生 `parseConfig` 校验，接受 `GlobalSettings`
  作为第二参数。私有扩展函数（`isValidIpv4Cidr` / `isValidIpv6Cidr` / `isValidSocketAddr` /
  `isValidListenerUrl` 等）位于文件末尾。

* [TomlConfigBuilder.kt](app/src/main/java/cc/ptoe/easytier/compose/core/TomlConfigBuilder.kt) —
  `TomlConfigBuilder`（object）：结构化 profile + GlobalSettings → TOML，并提供 `rootTunSpec()` 生成
  `RootTunSpec`（含 `noTun` 标志）。ROOT\_TUN 模式下（含 no\_tun）输出 `bind_device = false` +
  `socket_mark = 0x20000`（`ROOT_TUN_SOCKET_MARK`）以绕过 VpnService/mihomo TUN。**已移除高级 TOML 透传路径
  **，所有配置均通过结构化表单生成。

* [NativeConfigParser.kt](app/src/main/java/cc/ptoe/easytier/compose/core/NativeConfigParser.kt) —
  `NativeConfigParser`（fun interface）+ `EasyTierNativeConfigParser`（object 默认实现，调用
  `EasyTierJni.parseConfig` / `getLastError`）。测试通过注入桩绕过真实 JNI。

* [NetworkInfo.kt](app/src/main/java/cc/ptoe/easytier/compose/core/NetworkInfo.kt) —
  `collectNetworkInfos` 返回 JSON 的解析逻辑（从原 `EasyTierRuntimeCoordinator.kt` 抽出）：
  `NetworkInfo` data class、`String.networkInfo(profileId)` 顶层解析、`peerRoutePair()` 映射
  `RuntimePeer`、`ipv4InetToCidr`（u32 big-endian → 点分十进制）、`natTypeName`（字符串/数字 0–9 统一）、
  `normalizeTunnelType`（取 scheme）、`normalizeCidr`（裸 IP 补 /32）、
  `EasyTierProfile.withDeviceHostnameIfBlank`（从 `Settings.Global.DEVICE_NAME` 补全 hostname）。

### data 层

* [EasyTierModels.kt](app/src/main/java/cc/ptoe/easytier/compose/data/EasyTierModels.kt) —
  `EasyTierProfile`（`@Serializable`，含 \~50 个字段：基础、peers、listeners、proxy networks、port
  forwards、VPN portal、secure mode、STUN/whitelists、KCP/QUIC proxy flags、bps 限制等）、`GlobalSettings`（
  `tunDeviceName` / `noTun` / `startOnBoot` / `mtu` / `multiThread` / `multiThreadCount` / bps 限制）、
  `TunMode` / `CompressionAlgo` / `EncryptionAlgorithm` 枚举、`Peer` / `ProxyNetwork` /
  `PortForward` / `VpnPortal` / `SecureMode` 子结构、`RuntimePeer`（含 `connectionType` /
  `tunnelProtos` / `lossRate` / `natType` / `cost`）、`RuntimeState`、`RuntimeStatus`（含 `hostname` /
  `natType` / `peers`）。

* [ProfileRepository.kt](app/src/main/java/cc/ptoe/easytier/compose/data/ProfileRepository.kt) —
  `ProfileRepository`：DataStore Preferences 持久化（store 名 `easytier_profiles`，key `profiles_v1` +
  `selected_profile_id`），JSON 序列化，含损坏数据自动备份恢复逻辑（`profiles_v1_corrupt`）；提供
  `newProfile` / `save` / `delete` / `select` / `reset`。`GlobalSettingsRepository`：独立 DataStore（
  `easytier_global_settings`），持久化 TUN 设备名 / `no_tun` 等全局设置。

### transport 层

* [RuntimeTransport.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/RuntimeTransport.kt) —
  `RuntimeTransport` 接口（`start(profile, toml, globalSettings)`）与 `RuntimeEffect` sealed
  interface（当前仅 `RequestVpnPermission`）。

* `transport/vpn/`：

    * [VpnTunTransport.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/vpn/VpnTunTransport.kt) —
      VPN 权限申请流，处理 pending start；`establishWhenResolved` 在虚拟 IPv4 解析后启动
      `EasyTierVpnService` 前台服务。

    * [EasyTierVpnService.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/vpn/EasyTierVpnService.kt) —
      `VpnService` 前台服务（`foregroundServiceType="specialUse"`），通过 `Builder()` 建立 tun，调用
      `EasyTierJni.setTunFd`。

* `transport/root/`：

    * [RootTunTransport.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/root/RootTunTransport.kt) —
      通过 libsu `RootService.bind` IPC 连接 root 进程，启动后持续轮询 `getStatus()` 并解析
      `peersJson` 为 `List<RuntimePeer>`。

    * [EasyTierRootService.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/root/EasyTierRootService.kt) —
      `RootService`，在 root 进程内运行 EasyTier + 创建真实 `easytier0` 接口，轮询 DHCP；RUNNING 后持续轮询
      `collectNetworkInfos` 更新 Peer 列表。聚焦 Service 生命周期 + Binder + EasyTier 启停，路由与 DNS
      委托给下方两个 manager。通过 `RemoteCallbackList<IRootStatusCallback>` push 状态更新（替代轮询
      `getStatus()`）。

    * [RootTunRouteManager.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/root/RootTunRouteManager.kt) —
      `internal` 类，管理 TUN 路由与 `ip rule`：`syncTunRoutes(cidr, runtimeRoutes, spec)` 计算路由集（虚拟
      IP 子网 + 远端 `proxy_cidrs` + manual routes + Magic DNS 路由，移除本地 `spec.proxyCidrs`
      ）并增量同步；`cleanupRoutesAndRules()` 幂等清理。ip rule 优先级 5000（先于 Android 的 10000+）。

    * [SystemDnsManager.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/root/SystemDnsManager.kt) —
      `internal` 类，Magic DNS 切换：`enableMagicDns(fakeIp)` 通过 `settings put global dns1/dns2`
      切换系统 DNS 并保存原值；`restore()` 幂等恢复。常量 `MAGIC_DNS_FAKE_IP = "100.100.100.101"`。

    * [RootTunNative.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/root/RootTunNative.kt) —
      加载 `libroot_tun_jni`，提供 `create` / `syncRoutes` / `destroy`。

    * [RootModels.kt](app/src/main/java/cc/ptoe/easytier/compose/transport/root/RootModels.kt) —
      `RootTunSpec`、`RootRuntimeStatus`（`Parcelable`，AIDL 载荷）。

### AIDL

位于 `app/src/main/aidl/cc/ptoe/easytier/compose/transport/root/`：

* `IEasyTierRootService.aidl` — `start(profileId, toml, spec)`、`stop()`、`getStatus()`、
  `registerStatusCallback(cb)` / `unregisterStatusCallback(cb)`（push-based 状态更新）

* `IRootStatusCallback.aidl` — `onStatusUpdated(status)` 回调接口

* `RootTunSpec.aidl`、`RootRuntimeStatus.aidl` — parcelable 声明

### ui 层

* [EasyTierApp.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/EasyTierApp.kt) — Compose 根。五个
  `Destination`（Dashboard / Profiles / Peers / Settings / Editor），自适应布局：`screenWidthDp >= 840`
  用 `NavigationRail`，否则用 `NavigationBar`（Editor 屏幕隐藏底部栏）。Editor 屏幕支持 predictive back
  手势（`PredictiveBackHandler` + `Animatable` 平移/缩放/淡出）。仅保留顶层导航逻辑，屏幕与组件已拆分到
  `ui/screens/` 与 `ui/components/` 子包（所有内部 Composable 为 `internal` 可见性）。

* [EasyTierViewModel.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/EasyTierViewModel.kt) —
  `combine` 7 个 flow（profiles、selectedProfileId、本地
  selectedId、draft、coordinator.status、errors、globalSettings）聚合为 `EasyTierUiState`，
  `WhileSubscribed(5_000)`。提供 `selectProfile` / `beginCreate` / `beginEdit` / `discardDraft` /
  `updateDraft` / `saveDraft` / `delete` / `updateTunMode` / `updateGlobalSettings` / `connect` /
  `disconnect` / `onVpnPermissionResult` / `resetProfiles`。

* `ui/components/` — 跨屏幕复用的 `internal` Composable：

    * [FormComponents.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/components/FormComponents.kt) —
      `SectionCard`、`FormField`、`SwitchRow`、`ChoiceRow`（ExposedDropdownMenu）、`ListField` +
      `ListEditorDialog`（通用字符串列表编辑器）。

    * [EditorListFields.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/components/EditorListFields.kt) —
      `PeerListField` + `PeerListEditorDialog`、`ProxyNetworkListField` + `ProxyNetworkEditorDialog`、
      `PortForwardListField` + `PortForwardEditorDialog`（强类型列表字段 + 编辑对话框）。

    * [SettingsComponents.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/components/SettingsComponents.kt) —
      `SettingsGroup`、`SettingsItem`、`notificationsPermissionGranted(context)` 工具函数。

* `ui/screens/` — 各 `Destination` 屏幕（`internal` Composable）：

    * [DashboardScreen.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/screens/DashboardScreen.kt) —
      `DashboardScreen` + `StatusCard` + `StatusDetailsGroup`（网络名 / 虚拟 IP / hostname / NAT
      类型 / 状态）+ `ErrorBanner`。

    * [ProfilesScreen.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/screens/ProfilesScreen.kt) —
      profile 列表 + 删除确认对话框。

    * [PeersScreen.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/screens/PeersScreen.kt) —
      `PeersScreen` 列出 `RuntimePeer`，`PeerDetailsDialog` 展示延迟 / 隧道 / 丢包率等详情。

    * [ProfileEditorScreen.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/screens/ProfileEditorScreen.kt) —
      按分区组织全部配置（General / Network & Peers / Routing / IPv6 Public Address / Port Forwards /
      VPN Portal (WireGuard) / Secure Mode / STUN & Whitelists / Flags — General / Flags — P2P /
      Flags — KCP Proxy / Flags — QUIC Proxy）。

    * [SettingsScreen.kt](app/src/main/java/cc/ptoe/easytier/compose/ui/screens/SettingsScreen.kt) —
      TUN 模式切换、**Global overrides**（TUN 设备名 / `no_tun` / Start on boot）、通知授权与 Reset。

* `ui/theme/` — `EasyTierTheme`（亮/暗双色方案 + 自定义 `ExpressiveShapes`）、`Color.kt`、`Type.kt`。

### 原生符号所有者

* [com/easytier/jni/EasyTierJNI.kt](app/src/main/java/com/easytier/jni/EasyTierJNI.kt) —
  `System.loadLibrary("easytier_android_jni")` 的所有者，仅被 `core.EasyTierJni` 引用。

## 两种 TUN 模式

| 维度        | VPN\_SERVICE          | ROOT\_TUN                                            |
|-----------|-----------------------|------------------------------------------------------|
| 实现        | `VpnService.Builder`  | `/dev/net/tun` + netlink（root 进程）                    |
| 权限        | 系统 VPN 授权弹窗           | root（libsu RootService IPC）                          |
| 前台服务      | `specialUse`          | 否（root 进程内运行）                                        |
| Magic DNS | 支持（`100.100.100.101`） | 支持（root 进程内运行 + `settings put global dns1` 切换系统 DNS） |
| 设备名       | Android VPN（虚拟）       | `easytier0`（真实接口）                                    |

切换 TUN 模式在 Settings 页通过 Switch 完成（UI 显示为 "Root" / "VPN Service"）；运行中禁止切换。**No
TUN 模式下该 Switch 常驻显示且有意义**：No TUN 不能和 VpnService / 其他代理同时工作，此时 Root
模式将核心运行在 root daemon 进程（不创建 TUN），配合 `socket_mark` 确保核心流量走物理网卡，不被
VpnService/其他代理的 TUN 劫持；VPN\_SERVICE 侧的 No TUN 仍在 app 进程内运行核心（无 TUN）。

## EasyTier TOML 配置约定

`TomlConfigBuilder.build(profile, globalSettings)` 生成的结构化 TOML 关键字段：

* 顶层：`instance_name`（= profile.id）、`hostname`、`dhcp`、`ipv4`、`ipv6`、`listeners`、`mapped_listeners`、
  `exit_nodes`、`routes`（manual routes）、`stun_servers`、`stun_servers_v6`、`tcp_whitelist`、
  `udp_whitelist`、`ipv6_public_addr_provider` / `ipv6_public_addr_auto` / `ipv6_public_addr_prefix`。

* `[network_identity]` — `network_name` / `network_secret`。

* `[[peer]]` — `uri`（peer URI 来自 `Peer.uri`，当前 `peerPublicKey` 仅存于模型，未写入 TOML）。

* `[[proxy_network]]` — `cidr` / `mapped_cidr`（可选）/ `allow`（tcp/udp/icmp 列表）。

* `[[port_forward]]` — `bind_addr` / `dst_addr` / `proto`（tcp/udp）。

* `[vpn_portal_config]` — `client_cidr` / `wireguard_listen`（仅 `vpnPortal != null` 时输出）。

* `[secure_mode]` — `enabled` / `local_private_key` / `local_public_key`（仅 `secureMode.enabled`
  时输出）。

* `[flags]` — `default_protocol`、`dev_name`（来自 `globalSettings.tunDeviceName`）、
  `enable_encryption`、`enable_ipv6`、`mtu`、`latency_first`、`enable_exit_node`、`no_tun`（来自
  `globalSettings.noTun`）、`use_smoltcp=false`、`relay_network_whitelist`、`disable_p2p` / `p2p_only` /
  `lazy_p2p` / `relay_all_peer_rpc`、各 hole punching / UPnP 开关、`multi_thread` /
  `multi_thread_count`、`data_compress_algo`（枚举名：None/Zstd）、`bind_device`（VPN\_SERVICE =
  profile.bindDevice；ROOT\_TUN 强制 false）、`socket_mark`（仅 ROOT\_TUN（含 no\_tun），= `0x20000` /
  `ROOT_TUN_SOCKET_MARK`，使 Android 策略路由绕过 VpnService TUN 走物理接口）、KCP proxy 系列开关、
  `proxy_forward_by_system`、`accept_dns`（= `enableMagicDns`）、`private_mode`、QUIC proxy 系列开关、
  `foreign_relay_bps_limit` / `instance_recv_bps_limit`、`encryption_algorithm`（xor / aes-gcm /
  aes-256-gcm / chacha20）、`tld_dns_zone`、`disable_relay_data`、`enable_udp_broadcast_relay`。

约束与说明：

* MTU 默认 1380，有效区间 576..9000。

* `accept_dns` 在开启 Magic DNS 时为 true（VPN\_SERVICE 与 ROOT\_TUN 均支持）。ROOT\_TUN 模式下，
  `SystemDnsManager`（由 `EasyTierRootService` 持有）额外通过 root shell 执行
  `settings put global dns1/dns2 100.100.100.101` 切换系统 DNS，并在停止时恢复原值。

* `TomlConfigBuilder.rootTunSpec(profile, globalSettings)` 生成 `RootTunSpec`（含 `ipv4Cidr` /
  `mtu` / `manualRoutes` / `proxyCidrs` / `devName` / `magicDns`），用于 AIDL 传递给 root 进程；
  `devName` 空白时回退到 `easytier0`。

* 轮询 `collectNetworkInfos` 返回的 JSON 中：

    * `my_node_info.virtual_ipv4` 为 `{address:{addr:<u32 big-endian>}, network_length:<u32>}`
      ，需按大端拆解为点分十进制（见 `ipv4InetToCidr`）。

    * `routes[].proxy_cidrs` 是对端可达的远端网络 CIDR（去重排序后作为 TUN 内核路由）。

    * `peer_route_pairs[]` 每项含 `route`（hostname / `ipv4_addr` / `cost` / `path_latency` /
      `path_latency_latency_first` / `stun_info.udp_nat_type`）与可选 `peer`（`conns[]` 含
      `stats.latency_us` / `loss_rate` / `tunnel.tunnel_type`）；`peerRoutePair` 按 cost==1 直连取
      `latency_us`、cost>1 中继取 `path_latency_latency_first`/`path_latency`，映射为 `RuntimePeer`（参照
      easytier-cli 的 `PeerTableItem::from(PeerRoutePair)`）。

    * `stun_info.udp_nat_type` 可为字符串或数字（0–9），由 `natTypeName` 统一为可读名称。

    * `tunnel.tunnel_type` 可能是裸 scheme 或完整 URL，由 `normalizeTunnelType` 取 scheme 部分。

## 关键约定与约束

* **JNI 门面**：应用层只调用 `cc.ptoe.easytier.compose.core.EasyTierJni`，不直接接触
  `com.easytier.jni.EasyTierJNI`。

* **不自动连接**：`MainActivity.onResume()` 有意留空，连接必须由用户显式触发。

* **运行中不可编辑/删除/切换模式**：`EasyTierViewModel.saveDraft` / `delete` / `updateTunMode` 均检查
  `RuntimeState.STARTING/RUNNING` 并拒绝；`resetProfiles` 会先 `coordinator.stop()` 再清空。

* **Hostname 自动补全**：`EasyTierRuntimeCoordinator.start` 通过 `withDeviceHostnameIfBlank` 在
  profile 留空时取 `Settings.Global.DEVICE_NAME`（回退 `Build.MODEL`），剥离 ISO 控制字符并截断到 32
  字符，与 EasyTier Core 的 `get_hostname` 行为一致。

* **GlobalSettings**：TUN 设备名 / `no_tun` 等全局选项独立持久化，所有 profile 共享；
  `ProfileValidator.validate` 与 `TomlConfigBuilder.build` 均需显式传入 `GlobalSettings`。

* **协程**：协调器用 `SupervisorJob + Dispatchers.IO` + `Mutex`；ViewModel 用 `viewModelScope`。

* **DataStore**：profile 损坏时自动迁移到 `profiles_v1_corrupt` 并清空主键，避免崩溃；
  `GlobalSettingsRepository` 使用独立 store（`easytier_global_settings`）。

* **release 构建**：`optimization.enable = false`，R8 实际未启用优化；keep 规则文件
  `app/src/main/keepRules/rules.keep` 当前为空模板。

* **自适应布局阈值**：`screenWidthDp >= 840` 切换为双栏 NavigationRail。

* **edge-to-edge**：`enableEdgeToEdge()` + `WindowInsets.safeDrawing`。

* **国际化**：目前 UI 文案为硬编码英文，仅 `app_name` 走 `strings.xml`。

## 测试

* 单元测试 [ProfileConfigTest.kt](app/src/test/java/cc/ptoe/easytier/compose/ProfileConfigTest.kt)
  覆盖：结构化 TOML 生成（含 `[[port_forward]]` / `[vpn_portal_config]` / `[secure_mode]` / 全 flags
  字段）、DHCP、Root TUN 支持 Magic DNS、`rootTunSpec`（含 dev name 覆盖与空白回退、no\_tun 下禁用 Magic
  DNS 且携带 `noTun` 标志）、no\_tun + ROOT\_TUN 仍输出 `socket_mark`、GlobalSettings 覆盖 TUN 设备名 /
  `no_tun`、字段校验（IPv4/IPv6 CIDR、MTU、peers、proxy networks、port forwards、VPN
  portal、multiThreadCount、bps 限制）。测试通过注入 `NativeConfigParser` 桩绕过真实 JNI。

* 修改 `ProfileValidator.kt` / `TomlConfigBuilder.kt` / `NativeConfigParser.kt` / `NetworkInfo.kt` 或
  `EasyTierModels.kt` 后应运行 `./gradlew :app:testDebugUnitTest`。

* instrumented 测试（`ExampleInstrumentedTest`）为占位。

## 权限

`AndroidManifest.xml` 声明：`INTERNET`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`，以及服务级 `BIND_VPN_SERVICE`。VPN 前台服务 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 标注为 `EasyTier virtual network transport`。

## 易踩坑点

1. **修改 AIDL / Parcelable 后**需重新构建以刷新生成的 Stub。
2. **`libeasytier_android_jni.so`** **缺失或架构不匹配**会触发 `UnsatisfiedLinkError`；确认
   `jniLibs/<abi>/` 存在且 `abiFilters` 匹配。
3. **Root 模式**依赖设备已 root 且 libsu 能获取 root；`RootService.unbind` 必须在 `bound` 为 true 时才调用。
4. **netlink 路由**：TUN 远端网络路由必须用 `RT_SCOPE_UNIVERSE`（非 `LINK`），否则对任意 CIDR 返回 EINVAL。
5. **IPv4 addr 解析**：EasyTier 序列化的 `addr` 是 `u32::from_be_bytes` 的 big-endian 整数，需拆字节还原。
6. **配置缓存**：修改 build script 后首次构建会重建缓存；某些不兼容的插件可能需关闭。

