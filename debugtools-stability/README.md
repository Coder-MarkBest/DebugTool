# debugtools-stability

系统级崩溃日志采集器:从 DropBoxManager + 文件系统(`/data/anr/`, `/data/tombstones/`) 主动采集 Java 崩溃、Native 崩溃(tombstone)、ANR trace,按配置的进程名列表过滤,定时检查 + 手动触发,时间倒序展示。

**前置条件:** 系统应用权限（能读 `/data/` 目录 + DropBoxManager）。

## 接入(2 步)

**1) Application.onCreate 尽早 init:**
```kotlin
StabilityMonitor.init(context, listOf("com.xxx.voice", "com.xxx.asr"))
```

**2) 注册模块:**
```kotlin
DebugTools.builder(context).register(StabilityModule()).build()
```

## 看什么

- **进程存活状态条**:绿色=正常,红色=进程挂了
- **「立即搜索」按钮**:手动触发一次全量扫描
- **崩溃列表**:每条格式 `时间  类型图标  进程名  来源路径`,点击展开堆栈
- **定时检查**:每 60 秒自动搜一次(进入 Tab 时启动,离开时停止)

## 如何定位进程的崩溃

Android 崩溃日志在固定位置标注进程名,模块通过正则提取后精确匹配:

| 崩溃类型 | 来源 | 进程名提取 |
|----------|------|-----------|
| Java 崩溃 | DropBox `system_app_crash` | `Process: com.xxx.voice` |
| ANR | `/data/anr/traces_*` | `Cmd line: com.xxx.voice` |
| Native 崩溃 | `/data/tombstones/tombstone_*` | `>>> com.xxx.voice <<<` |

提取后与配置的进程名列表做精确匹配,不相关的进程崩溃全部过滤。

## 数据来源

| 来源 | 方式 | 覆盖 |
|------|------|------|
| DropBoxManager | `getNextEntry()` 遍历标签 | `system_app_crash`, `system_app_anr`, `SYSTEM_TOMBSTONE`, `SYSTEM_NATIVE_CRASH` |
| `/data/anr/` | 目录扫描 | ANR traces |
| `/data/tombstones/` | 目录扫描 | Native 崩溃 |
| `/data/system/dropbox/` | 目录扫描(兜底) | Java 崩溃文件 |

两路数据取并集,按 `(时间, 进程名, 堆栈前200字符)` 去重,时间倒序。

## 约束

- 需系统应用权限。非系统应用会编译通过但运行时空数据。
- 不做持久化 —— 每次打开/定时/手动都是从系统源实时读取。
- 进程存活仅检查 `/proc` 存在,不做心跳/端口/响应检查。
