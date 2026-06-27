# DebugTools SDK 设计文档

> Android 平台语音助手调试工具 SDK

---

## 概述

DebugTools 是一个面向 Android 语音助手项目的**通用开源调试工具 SDK**，以悬浮窗形式展示在所有应用最上层，支持业务方自由扩展模块。最低支持 Android API 26（`TYPE_APPLICATION_OVERLAY`）。

---

## 1. 库拆分（多模块结构）

```
debugtools-core          ← 必选
  ├── window/            悬浮窗管理、三种展示模式
  ├── ipc/               AIDL Service、进程模式切换
  ├── module/            DebugModule 接口、Tab 管理
  ├── settings/          原子设置项定义、渲染、卡片分组
  └── persistence/       SettingsStorage 接口 + SP/DataStore 实现

debugtools-network        ← 可选，网络信息模块
debugtools-timeline       ← 可选，事件时间线模块
debugtools-general        ← 可选，磁盘监控 + 进程存活检测
```

接入示例：

```kotlin
// app/build.gradle
debugImplementation("io.github.xxx:debugtools-core:1.0")
debugImplementation("io.github.xxx:debugtools-network:1.0")
debugImplementation("io.github.xxx:debugtools-timeline:1.0")
```

```kotlin
// Application.onCreate()
DebugTools.builder(this)
    .processMode(ProcessMode.INDEPENDENT)     // 或 ATTACHED
    .storage(DataStoreStorage(this))          // 可省略，默认 SharedPreferences
    .register(NetworkModule.create("8.8.8.8"))
    .register(TimelineModule.create(maxEvents = 500))
    .register(GeneralModule.builder()
        .addDiskMonitor("/data/data/com.example", intervalMinutes = 10)
        .addProcessMonitor(listOf("com.example", "com.example:asr"))
        .build())
    .register(MyVoiceModule())                // 业务自定义模块
    .build()
```

---

## 2. 进程模式

### 独立进程模式（`ProcessMode.INDEPENDENT`）

```
主进程（App）                              :debug 进程
┌────────────────────────┐  AIDL Binder  ┌─────────────────────────┐
│  DebugToolsClient      │ ────────────► │  DebugToolsService      │
│  .sendEvent(event)     │              │  悬浮窗 + 全部 UI         │
│  .reportCrash(crash)   │ ◄──────────── │  模块 Presenter          │
│  UncaughtException拦截 │  Callback     │  SettingsStorage         │
└────────────────────────┘              └─────────────────────────┘
```

- `DebugToolsService` 声明 `android:process=":debug"`，与主进程完全隔离
- 主进程崩溃不影响 debug 进程，可展示崩溃信息
- 主进程通过 `UncaughtExceptionHandler` 在崩溃前同步调用 `reportCrash()`

### 附着主进程模式（`ProcessMode.ATTACHED`）

- 不启动 Service，`DebugTools` 单例直接管理悬浮窗和所有模块
- `DebugToolsClient` 接口相同，底层为直接方法调用（无 Binder 开销）

---

## 3. 进程间通信（AIDL）

### 接口定义

```java
// IDebugToolsService.aidl — 主进程调用服务端
interface IDebugToolsService {
    void sendEvent(in DebugEvent event);
    void reportCrash(in CrashInfo crash);
    void updateModuleData(String moduleId, in Bundle data);
    void registerCallback(IDebugToolsCallback cb);
    void unregisterCallback(IDebugToolsCallback cb);
}

// IDebugToolsCallback.aidl — 服务端回调主进程
interface IDebugToolsCallback {
    void onSettingChanged(String moduleId, String key, in Bundle value);
    void onDisplayModeChanged(int mode);
}
```

`DebugEvent`、`CrashInfo` 均实现 `Parcelable`。

### 连接生命周期与断连恢复

- `onServiceConnected`：拿到 stub，注册 callback，发送缓存事件
- `onServiceDisconnected`：进入 `RECONNECTING` 状态，指数退避重连（2s → 4s → 8s，上限 30s）
- 断连期间最多缓存 100 条事件（可配置），重连后批量发送

---

## 4. 悬浮窗与展示模式

### Window 参数

```kotlin
WindowManager.LayoutParams(
    WRAP_CONTENT, WRAP_CONTENT,
    TYPE_APPLICATION_OVERLAY,
    FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_WATCH_OUTSIDE_TOUCH,
    PixelFormat.TRANSLUCENT
)
```

`FLAG_NOT_TOUCH_MODAL` 保证工具区域外的触摸事件透传给下层应用。

### 三种展示模式

| 模式 | 描述 | 切换方式 |
|------|------|---------|
| **ExpandedMode** | 贴右侧面板，宽度约屏宽 40%（可配置），含 TabBar + 内容 View | 滑动收起 → Minimized |
| **MinimizedMode** | 可拖拽悬浮圆形按钮，`ACTION_UP` 后自动吸附左/右屏幕边缘 | 单击 → Expanded；长按 → Brief |
| **BriefMode** | 贴右侧竖条 或 顶/底横条（`BriefOrientation` 初始化时配置，默认竖向） | 单击 → Expanded |

**模式状态机：**

```
         滑动收起
Expanded ──────────► Minimized
   ▲                    │  长按
   │  单击              ▼
   └──────────────── Brief
```

`DisplayModeManager` 持有当前状态，切换时更新 `LayoutParams` 并通知 `OnModeChangeListener`，UI 层监听后做视图切换。`MinimizedMode` 下所有模块暂停 View 刷新（省电）。

### 权限处理

`FloatingWindowManager.init()` 先检查 `Settings.canDrawOverlays()`，无权限时抛出 `OverlayPermissionException`，由接入方处理引导授权。

---

## 5. 模块接口

```kotlin
interface DebugModule {
    val moduleId: String
    val tabTitle: String
    fun buildSettings(): List<SettingGroup>         // 声明设置项（按组）
    fun createContentView(context: Context): View   // Tab 内容 View
    fun getBriefItems(): List<BriefItem>            // 简要信息条内容
    fun onAttach(storage: SettingsStorage)          // 生命周期：挂载
    fun onDetach()                                  // 生命周期：卸载
}
```

每个模块 Tab 遵循 MVP：`ModuleView ↔ ModulePresenter ↔ SettingsStorage / 数据源`。`ModulePresenter` 在 `onDetach()` 时取消 CoroutineScope，无内存泄漏。

---

## 6. 原子设置项

```kotlin
sealed class SettingItem(
    val key: String,
    val label: String,
    val description: String? = null   // null 则不展示说明
) {
    class SingleSelect(key: String, label: String,
        val options: List<String>, val default: String,
        description: String? = null) : SettingItem(...)

    class MultiSelect(key: String, label: String,
        val options: List<String>, val defaults: List<String>,
        description: String? = null) : SettingItem(...)

    class Toggle(key: String, label: String,
        val default: Boolean,
        description: String? = null) : SettingItem(...)

    class EditText(key: String, label: String,
        val default: String, val hint: String = "",
        description: String? = null) : SettingItem(...)

    class Custom(key: String, label: String,
        val viewFactory: (Context, SettingsStorage) -> View,
        description: String? = null) : SettingItem(...)
}
```

### SettingGroup

```kotlin
data class SettingGroup(
    val title: String,           // 卡片标题
    val items: List<SettingItem>
)
```

`buildSettings()` 返回 `List<SettingGroup>`，每组渲染为一张卡片。只有一组时可省略标题（`title = ""`）。

### 渲染规则

`SettingsRenderer.render(context, groups, storage): View` 将 `List<SettingGroup>` 渲染为卡片分组视图：

- 每个 `SettingGroup` 渲染为一张卡片，标题展示在卡片顶部
- `description != null` 时，在设置项下方渲染蓝色左边框说明块
- `SettingItem.default` 仅在 storage 中无此 key 时写入，之后以 storage 值为准

**设置项 ↔ ViewBinder 映射：**

| SettingItem | ViewBinder | 渲染结果 |
|-------------|------------|---------|
| SingleSelect | SingleSelectBinder | Pill 形态横向选项按钮 |
| MultiSelect | MultiSelectBinder | CheckBox 列表 |
| Toggle | ToggleBinder | Switch |
| EditText | EditTextBinder | EditText + 确认按钮 |
| Custom | CustomBinder | viewFactory() 返回值 |

---

## 7. 持久化层

### 接口

```kotlin
interface SettingsStorage {
    fun putString(key: String, value: String)
    fun getString(key: String, default: String): String
    fun putStringSet(key: String, value: Set<String>)
    fun getStringSet(key: String, default: Set<String>): Set<String>
    fun putBoolean(key: String, value: Boolean)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun remove(key: String)
    fun clear()
}
```

同步接口，由 Presenter 在 `Dispatchers.IO` 上调用，不阻塞主线程。

### 内置实现

- **`SharedPreferencesStorage`**：`context.getSharedPreferences(name, MODE_PRIVATE)`，零额外依赖，默认实现
- **`DataStoreStorage`**：写操作 `launch` 异步，读操作 `runBlocking` 在 IO 线程取值

### Key 命名

`ScopedStorage` 装饰器自动处理 key 前缀，格式为 `{moduleId}/{settingKey}`，模块内只需使用自己的短 key，`core` 在 `onAttach(storage)` 时传入已 scope 好的实例。

---

## 8. 可选模块

### debugtools-network

| 指标 | 实现 |
|------|------|
| 网络类型 | `ConnectivityManager` + `NetworkCapabilities` |
| Ping 延迟 | `InetAddress.getByName(gateway).isReachable()` 循环，默认间隔 5s |
| 网络质量 | 综合类型+延迟 → `EXCELLENT / GOOD / POOR / OFFLINE` 枚举 |

BriefItem：`WiFi · 23ms · 良好`，状态色跟随质量枚举。

### debugtools-timeline

```kotlin
data class DebugEvent(
    val timestamp: Long,
    val tag: String,          // 始终展示
    val detail: String? = null // 默认折叠，点击展开
) : Parcelable
```

- 主进程注入：`DebugTools.sendEvent(event)`
- `RecyclerView` 时间线，`DiffUtil` 增量更新
- 内存最多保留 N 条（默认 500，可配置），超出丢弃最旧
- `expandedKeys: Set<Long>` 在 Presenter 层维护，不污染数据层

BriefItem：最新一条 tag + 相对时间（`3s ago`）。

### debugtools-general

**磁盘监控：**

```kotlin
DiskMonitor(path = "/data/data/com.example", intervalMinutes = 10)
// intervalMinutes 最小值强制 clamp 为 5
// File.walkTopDown() 惰性遍历，捕获 SecurityException 跳过无权限子目录
```

**进程存活监控：**

- 读取 `ActivityManager.getRunningAppProcesses()` 或解析 `/proc`
- 每 10s 检查一次，状态变化时触发回调

BriefItem：各磁盘大小 + 进程存活状态（✓ / ✗），超阈值标红。

---

## 9. 数据流

```
主进程业务代码
    │  DebugTools.sendEvent() / updateModuleData()
    ▼
DebugToolsClient
    │  独立进程：AIDL binder call（Binder 线程池接收）
    │  附着模式：直接方法调用
    ▼
DebugToolsController
    │  分发给对应 ModulePresenter（Dispatchers.IO）
    ▼
Handler.post → 主线程
    ▼
ModuleView.update()
    ├─► Expanded：Tab 内容刷新
    ├─► Brief：BriefItem 文字/颜色更新
    └─► Minimized：跳过（省电）
```

### 线程规则

| 操作 | 线程 |
|------|------|
| AIDL binder call 接收 | Binder 线程池 |
| 数据处理、存储读写 | `Dispatchers.IO` |
| 网络采集、磁盘扫描 | `Dispatchers.IO` |
| View 更新、拖拽动画 | 主线程 |

---

## 10. 错误处理

| 场景 | 处理方式 |
|------|---------|
| AIDL 断连 | 指数退避重连（2→4→8s，上限 30s），断连期间缓存事件（上限 100 条） |
| 主进程崩溃 | `UncaughtExceptionHandler` 同步调用 `reportCrash()`，之后交还原 handler |
| 磁盘扫描无权限 | 捕获 `SecurityException`，跳过该目录继续统计 |
| 无悬浮窗权限 | `OverlayPermissionException`，由接入方引导用户授权 |
| debug 进程崩溃 | 主进程 `onServiceDisconnected` 触发重连，对主进程无影响 |
