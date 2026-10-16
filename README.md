# 白名单静音 / EmergencyContactSilentXP

Android 14 / LSPosed 的系统音频与震动控制模块。首版适配目标为一加 PHB110，系统 PHB110_14.0.0.813(CN01)，LSPosed Zygisk 1.9.2 (7024)。

项目仓库：[pppppbbbbb/EmergencyContactSilentXP](https://github.com/pppppbbbbb/EmergencyContactSilentXP)。

## 适配环境与版本

| 项目 | 版本 / 说明 |
| --- | --- |
| 目标设备 | 一加 PHB110 |
| 基于的 Android 版本 | Android 14（API 34） |
| 目标系统版本 | `PHB110_14.0.0.813(CN01)` |
| LSPosed | Zygisk 版 `1.9.2 (7024)` |
| 模块版本 | `0.1.0`（versionCode `1`） |
| 最低 / 目标 Android SDK | minSdk `34` / targetSdk `34` |
| 编译 SDK | Android SDK `36` |
| Xposed API 编译依赖 | `de.robv.android.xposed:api:82`（compileOnly） |
| 模块声明的最低 Xposed API | `93`（AndroidManifest.xml 中的 xposedminversion） |
| Java | JDK `17+`，源码与字节码目标为 Java `17` |
| Android Gradle Plugin | `8.13.0` |
| Gradle Wrapper | `8.13` |

以上设备、系统和 LSPosed 版本是首版适配基线，尚未完成系统 Hook 的实机验收；不代表已经验证其他系统或框架版本。minSdk 允许安装也不代表系统内部 Hook 兼容，升级系统或更换 ROM 后需重新验证。

应用包名与源码命名空间：`com.percy.silentxp`。变更包名后 Android 会视为新应用，需要重新配置白名单并在 LSPosed 中启用新模块。

## 安装与使用

1. 安装 `dist/EmergencyContactSilentXP-0.1.0-debug.apk`。
2. 在 LSPosed 中启用「白名单静音」，勾选「系统框架」作用域。
3. 重启手机加载系统 Hook。模块检测到所有必要 Hook 和配置读取可用后，控制开关才可操作。
4. 打开应用设置电话/短信号码及应用白名单。
5. 点击「添加顶部快捷开关」，在系统弹窗中确认；也可从快捷设置编辑页手动添加。

首次安装默认关闭屏蔽。开关状态与白名单保存在设备加密存储，之后会保留；关闭屏蔽会恢复被模块静音的播放器。无需给所有应用逐一添加 LSPosed 作用域。

## 行为

- 开启时，默认屏蔽非白名单播放器和震动请求。不改变系统音量、铃声模式、震动设置或免打扰规则。
- 闹钟音频和震动放行，以 Android 的 ALARM usage 为准。
- 独立路由到有线耳机、USB 耳机或蓝牙设备的声音放行。如果系统同时路由到扬声器与耳机，则整体屏蔽该播放器，避免扬声器漏声。震动不因耳机连接自动放行。
- 号码白名单共同用于电话及短信，可选取通讯录号码或手动录入。按号码精确匹配，规范化中国 +86/0086 前缀和分隔符，不按尾号模糊匹配。
- 应用白名单允许该应用音频与震动；通知通过原始发布应用识别，而不是把所有 SystemUI 声音放开。
- 通话状态/通信音频模式期间，通话语音和通话信令放行，不同时解除其他媒体的屏蔽。挂断后重新评估播放器。
- 放行只是取消本模块的拦截，不强制发声、提高音量、开启震动或绕过免打扰。
- 配置不可读或必要 Hook 初始化失败时保持/恢复不屏蔽，并在界面显示未就绪。

## 首版边界与待验证事项

- APK 编译和独立规则测试通过不代表已经完成系统 Hook 的实机验证。当前尚未重启加载模块，来电、短信、耳机切换、震动及播放恢复需要随后逐项验收。
- 短信号码优先使用最新 MessagingStyle 消息的 `tel:` 标识；没有号码标识时，将通知里的最新正文与最近两分钟短信收件箱做精确匹配。信息应用若隐藏/改写正文或使用特殊通知格式，无法确定发送人时不放行。彩信和 RCS 尚未专门适配。
- 不识别微信特定联系人；可把整个微信加入应用白名单。
- 普通 AudioTrack、MediaPlayer、SoundPool 等已登记播放器使用系统 PlayerProxy 独立音量乘数控制。绕过 Android 播放器登记、厂商特殊音频/震动路径可能需要额外适配，首版不承诺硬件级绝对静音。
- 对已在播放的播放器，开关切换会立即重新评估；设备/通话状态变更也会重新评估，并以 400ms 周期补偿遗漏事件。原始系统音量和应用音量保持不变。
- VoIP 语音通过 Android 通信模式判断。应用在接通前提前进入通信模式时，语音 usage 的放行时间也会提前；不等同于识别其内部接通事件。
- 共享 UID 的应用无法在播放器 UID 层完全区分。当前配置面向主用户，工作资料/多用户未适配。

## 构建

需要 JDK 17+、Android SDK 36；项目通过 Gradle Wrapper 使用 Gradle 8.13。在项目根目录新建或修改 `local.properties`，填写本机 Android SDK 路径（该文件不提交到 Git），例如：

```properties
sdk.dir=D:/Android/Sdk
```

然后运行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

当前 Windows 主机的 JDK Unix Socket 本地通信存在异常，可在构建进程临时设置以下变量，令 JDK 回退 TCP；不需要修改系统全局设置：

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=Z:\unavailable'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

签名是本机 Android 调试签名，仅供当前开发与实机测试。正式分发前应配置固定发布签名。

## 验收清单

- 未启用 LSPosed 时开关不可用，首次启用后屏蔽仍默认关闭。
- 非白名单媒体在开启时静音，关闭后同一播放器恢复；白名单应用不受影响。
- 白名单/非白名单号码分别来电、短信；锁屏与解锁各一次。
- 任意号码来电接通后听筒/免提正常，挂断后其他媒体仍被屏蔽。
- 白名单通知播放时，其他应用媒体不会一起出声。
- 系统震动关闭、静音、免打扰时，白名单仍遵循系统规则。
- 闹钟、耳机播放与拔出耳机后扬声器屏蔽分别检查。
- 快捷设置开关与应用内开关状态一致。
