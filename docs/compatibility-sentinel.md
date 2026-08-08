# 知乎版本哨兵

`Zhihu version sentinel` 每天检查国内版和 Google Play 版。它的职责是发现新版、
验证现有 Hook 是否仍兼容，并生成需要人工审核的云配置候选，不会直接发布机器猜测的
Hook 点。

## 数据源

- 国内版：知乎官网的 arm64 下载地址。
- Google Play 版版本号：Google Play 官方应用页面。
- Google Play 版包体：默认使用 APKPure 的 latest XAPK CDN 作为下载镜像，因为
  Google Play 没有提供匿名的官方 APK 下载接口。

第三方镜像不能决定测试版本。CI 会从 XAPK 中提取基础 APK，并核对以下信息：

1. 包名必须是 `com.zhihu.android`。
2. APK 的 `versionName` 必须与 Google Play 官方页面一致。
3. APK 签名证书 SHA-256 必须是已验证的知乎官方证书。
4. APK/ZIP 必须完整，包体大小必须位于合理范围内。

任一项不一致都会让工作流失败。因此 APKPure 只承担文件传输，不能替换或伪造测试
对象。可以在仓库 Secret 或 Variable 中设置 `ZHIHU_PLAY_APK_URL` 覆盖默认镜像，
也可以在手动运行工作流时临时填写 `play_apk_url`。覆盖地址同样执行全部校验。

## 执行结果

- 已适配版本：不下载 dex2jar，也不重复执行完整 Hook 测试。
- 新版本且全部测试通过：创建只修改 `update/compatibility-v1.json` 的候选 PR。
  新 Profile 精确限定到本次测试的 versionCode，且不会凭空生成符号。
- 下载、页面解析、身份校验、DEX 转换或任一 Hook 测试失败：上传测试报告，创建或
  更新去重 Issue，最后以失败状态结束。

候选 PR 合并到 `master` 后，`Publish compatibility config` 工作流生成带 SHA-256 和
多 CDN 地址的清单。知了加载新的云配置后，“关于”中的国内版/Play 版适配列表也会
随配置更新。

GitHub 的失败邮件依赖仓库维护者启用 Actions 失败通知。工作流本身会保持真正的
失败结论，而不是用 `continue-on-error` 把兼容性错误伪装成成功。

## 手动检查

在 Actions 中运行 `Zhihu version sentinel`，可以选择单独检查 `domestic`、`play`
或两者。勾选 `force` 会对已记录版本重新运行完整测试；Play 版强制测试时会下载约
数百 MB 的 XAPK。

APP 继续保留当前已有的按需 DexKit 兜底，但不新增未知版本后台全量扫描。大规模 APK/
DEX 分析和回归测试由 Actions 完成。
