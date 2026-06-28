# debugtools-startup

App 启动链路监控:接入方按通用协议上报每个组件的初始化(begin/success/fail + 依赖),SDK 记录、持久化(最近 10 次,App 私有目录卸载即删)、判定成功/失败/流程/耗时,提供甘特图 + 依赖图双视图与自动诊断。

## 设计理念

DebugTool 只定义**通用初始化协议**,对组件语义零理解——接入方按协议传数据,SDK 据协议字段做记录、判定与展示。

## 接入(3 步)

**1) Application.onCreate 尽早 init**(t0 自动取进程启动时间):
```kotlin
AppStartupMonitor.init(this, appVersion = "1.0")
```

**2) 每个组件初始化处上报**(`track` 同步糖,或 `begin/success/fail` 手动):
```kotlin
// 同步初始化:一行包住,自动计时 + 捕获异常(报错也算结束)
AppStartupMonitor.track("asr", dependsOn = listOf("config")) { initAsr() }

// 异步/回调式初始化:手动三件套
AppStartupMonitor.begin("net")
onNetReady { AppStartupMonitor.success("net") }      // 或 fail(name, throwable) / fail(name, msg)
```

**3) 启动完成处标记**(定义启动终点 + 触发落盘):
```kotlin
AppStartupMonitor.complete()   // 漏调有"首个 Activity onResume"兜底,数据不丢
```

注册模块即可在悬浮窗「启动链路」Tab 查看:
```kotlin
DebugTools.builder(context).register(StartupMonitorModule()).build()
```

## 看什么

- **会话列表**:最近 10 次启动(时间、总耗时、✓/✗ 数);点进某次 →
- **甘特图**(默认):时间轴,每组件一条(start→end,色=状态),并行重叠,关键路径高亮;
- **依赖图(DAG)**:一键切换,节点=组件(色=状态),边=依赖;
- **诊断**:逐条列出下方异常 + 关键路径。

## 自动诊断

| 类型 | 含义 |
|------|------|
| 报错 | 组件初始化抛异常 |
| 慢组件 | 耗时 > 50ms |
| 依赖倒挂 | 在依赖完成前就开始(初始化顺序 race) |
| 卡死/漏 end | 启动完成时仍未结束 |
| 依赖环 | dependsOn 成环 |
| 可并行却串行(提示) | 无依赖却延迟开始,可提前并行 |

## 约束

- 仅同进程上报;数据存 `filesDir/startup`,保留最近 10 次,**随 App 卸载删除**;不做网络上报。
- 耗时用单调时钟(`SystemClock.uptimeMillis`),免受系统改时间影响;t0 = 进程启动时间(`Process.getStartUptimeMillis`)。
