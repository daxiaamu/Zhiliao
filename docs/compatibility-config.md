# 兼容配置

知乎版本适配数据统一保存在
`app/src/main/assets/compatibility/compatibility-v1.json`。Hook 不再维护版本号或
混淆类名列表，而是按能力键从 `CompatibilityRegistry` 获取候选符号；候选失效时
继续使用反射结构特征和 DexKit 签名查询。

## 分层策略

1. 版本 Profile：处理已经验证过的发行渠道、版本范围和少量特殊符号。
2. 默认候选：保存跨多个版本仍有效的历史符号，作为低成本快速路径。
3. 特征匹配：候选失效后按父类、字段类型和方法签名解析。
4. DexKit：混淆名称完全变化时按稳定的方法结构动态定位。
5. 能力降级：单项解析失败只停用该项，不阻断其他功能。

## 将来云下发

远程 JSON 使用相同 schema，通过 libxposed RemotePreferences 写入
`compatibility_config_json_v1`，知乎进程与模块 UI 因而读取同一份配置。

接入下载器时必须遵循以下顺序：

1. 从可信、可认证的更新清单取得配置 URL、revision 和 SHA-256。
2. 下载 JSON 后调用 `CompatibilityRegistry.installRemoteConfig` 校验大小、
   SHA-256、schema、目标包名、版本范围和符号格式。
3. 只有 revision 不低于内置配置时才启用远程配置；任何错误自动回退内置资源。
4. 配置只能提供数据和符号候选，禁止下发脚本、DEX、表达式或任意反射调用。
5. Actions 应对国内版、Play 版和未知版本回退路径分别运行兼容测试后再发布配置。
6. 新 revision 完成校验、写入并重新加载后，仅提示一次“请重启知乎后生效”。
   该 Toast 必须由知了模块自身的 Activity 和 Android 系统 API 发出，不得依赖知乎内部
   类、布局或 Hook 点；已提示的 revision 持久化保存，避免重复弹出。

每次正式发布仍应把最新稳定配置写回 assets，避免首次安装依赖网络。

定期版本发现、APK 身份校验、兼容测试和候选 PR 流程见
[`compatibility-sentinel.md`](compatibility-sentinel.md)。

## 修改流程

1. 在 `profiles` 中新增或收窄版本范围；跨版本稳定的候选才放进 `defaults`。
2. 优先使用字段类型、父类和方法签名等结构特征，只有无法可靠探测的差异才新增能力键。
3. 新能力键必须同时加入代码中的白名单和消费逻辑，云配置不能自行创造可执行行为。
4. 提交前运行 `testDebugUnitTest`。标准构建和定期知乎兼容性 Actions 也会执行同一套测试。
