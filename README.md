# 知了

少一点打扰，多一点内容。

知了是面向知乎 Android 客户端的 LSPosed/Xposed 模块，用于移除广告、精简界面并改善部分交互体验。项目同时维护国内版与 Google Play 版适配，并通过远程兼容配置和 DexKit 回退提高对未知版本的适应能力。

[![Android CI](https://github.com/daxiaamu/Zhiliao/actions/workflows/AndroidCI.yml/badge.svg)](https://github.com/daxiaamu/Zhiliao/actions/workflows/AndroidCI.yml)
[![Release](https://img.shields.io/github/v/release/daxiaamu/Zhiliao?include_prereleases&label=Release)](https://github.com/daxiaamu/Zhiliao/releases)
[![Downloads](https://img.shields.io/github/downloads/daxiaamu/Zhiliao/total?label=Downloads)](https://github.com/daxiaamu/Zhiliao/releases)
[![License](https://img.shields.io/github/license/daxiaamu/Zhiliao?label=License)](LICENSE.md)

> 本项目仅用于学习、研究和改善个人使用体验，与知乎无关。请遵守所在地法律法规及知乎服务协议。

## 主要功能

### 广告与启动

- 移除启动广告并缩短广告等待路径
- 移除信息流、回答列表、评论、分享、回答底部和搜索广告

### 内容与交互

- 过滤推荐流中的视频、文章或想法
- 过滤会员推荐、回答圈子、商品推荐、相关搜索和关键字搜索
- 使用正则表达式按标题、作者或内容自定义过滤
- 跳过外链确认页，并选择在知乎内或外部浏览器打开
- 禁止首页自动刷新，保留当前阅读位置
- 左右滑动切换回答并调节手势灵敏度
- 保持色彩模式、显示卡片类别、状态栏沉浸或禁止全屏
- 解锁知乎隐藏的第三方登录方式
- 移除回答网页背景水印

### 界面精简

- 隐藏直播入口、未读小红点、会员卡片和热点通知
- 隐藏推荐页置顶热门及“我的”页运营卡片
- 精简文章页相关推荐
- 隐藏回答关注头像
- 按需隐藏会员、视频、关注、发布和发现导航
- 禁用导航栏活动主题并隐藏底部导航凸起

### WebView 与维护

- 按需开启 WebView 调试或注入自定义 JavaScript
- 自动、静默或单次清理知乎临时文件
- 可隐藏 Hook 加载失败 Toast，错误仍会保留在 LSPosed 日志中

所有功能均可在知了设置页单独控制。知乎设置页顶部提供知了入口；也可以直接从桌面或 LSPosed 模块详情页打开设置。桌面图标可隐藏，隐藏后仍可从 LSPosed 打开。

## 运行要求

- Android 8.0（API 26）及以上
- 支持现代 libxposed API 102 的 LSPosed 环境
- 知乎包名：`com.zhihu.android`

项目当前使用 `compileSdk 37`、`targetSdk 37` 和 Java 17 构建。旧版 Android、旧版 Xposed 框架及很早的知乎版本不再作为兼容目标。

## 已验证的知乎版本

| 渠道 | 版本 | versionCode | 状态 |
| --- | --- | ---: | --- |
| 国内版 | 11.4.0 | 40408 | 已适配 |
| Google Play 版 | 10.95.0 | 29522 | 已适配 |

[下载已适配的知乎安装包](https://pan.quark.cn/s/4f43a6eab295)

上述清单表示经过自动兼容测试和真机验证的版本，并不代表其他版本一定不能使用。未知版本会优先使用数据驱动的候选符号和 DexKit 回退解析；解析结果存在歧义或结构校验失败时，对应 Hook 会安全跳过，避免盲目执行。

知乎更新后如果部分功能失效，请在提交 Issue 时注明：

- 国内版或 Google Play 版
- 知乎版本名与 versionCode
- Android 和 LSPosed 版本
- 失效的具体功能
- LSPosed 日志或可复现步骤

## 安装与使用

1. 从 [GitHub Releases](https://github.com/daxiaamu/Zhiliao/releases) 下载并安装最新 APK。
2. 在 LSPosed 中启用知了模块。
3. 将作用域设为知乎 `com.zhihu.android`。知了连接 LSPosed 后会自动请求并同步该作用域。
4. 打开知了设置需要的功能，然后强制停止并重新启动知乎。

如果桌面没有知了图标，请从 LSPosed 的模块详情页打开。设置页中的“显示桌面图标”可重新显示图标。

## 更新与安全

- 自动检查知了模块更新，无需额外开启开关
- 支持手动检查、跳过指定版本、暂时忽略以及下载后调用系统安装器
- 模块 APK 使用 GitHub Releases 原始地址和多个 CDN/代理源下载
- 下载完成后必须通过 SHA-256 校验才会进入安装流程
- 模块更新的自动检查和手动检查共享同一会话，避免重复请求和重复弹窗
- 兼容配置也使用多 CDN，必须通过清单中的 SHA-256 校验后才会加载
- 新兼容配置加载后会提示重启知乎；Banner 仅在有效云配置已加载时显示云配版本

正式发布包使用项目维护者的固定证书签名。请优先从本仓库 Releases 下载，不要安装来源不明的二次打包版本。

## 自动兼容检查

GitHub Actions 每天检查国内版和 Google Play 版是否发布了新的知乎 APK，并执行以下流程：

1. 下载候选 APK，检查包名、版本和官方签名证书。
2. 提取 DEX 并运行全部 Hook 兼容测试。
3. 不兼容时使工作流失败并创建或更新 Issue。
4. 测试通过时生成待人工审核的兼容配置 PR。

自动化用于发现版本和验证现有 Hook，不会在缺少证据时猜测 Hook 点。兼容配置合并后，Actions 会校验配置并自动生成多 CDN 清单；发布模块版本后，也会自动生成稳定版或预发布版更新 JSON。

相关工作流：

- [Android CI](https://github.com/daxiaamu/Zhiliao/actions/workflows/AndroidCI.yml)
- [知乎版本巡检](https://github.com/daxiaamu/Zhiliao/actions/workflows/ZhihuCompatibility.yml)
- [兼容配置发布](https://github.com/daxiaamu/Zhiliao/actions/workflows/CompatibilityConfig.yml)
- [模块更新元数据](https://github.com/daxiaamu/Zhiliao/actions/workflows/UpdateJson.yml)

## 常见问题

### 模块已启用，但功能没有生效

确认 LSPosed 作用域中只有知乎 `com.zhihu.android`，然后强制停止并重新启动知乎。升级知乎或加载新云配置后也需要重启知乎进程。

### 打开知乎后提示部分功能不兼容

这表示某些 Hook 未通过结构校验并已被跳过，其他成功初始化的功能仍可继续使用。请携带知乎版本信息和 LSPosed 日志提交 Issue。

### 云配版本为什么没有显示

只有远程兼容配置成功下载、通过 SHA-256 校验并加载后才会显示。断网、所有 CDN 均不可用或配置校验失败时不会显示，也不会影响内置兼容配置继续工作。

### 为什么没有“下一个回答”按钮

新版知乎已经调整或移除了相关界面，知了保留的是“左右滑动切换回答”功能，不再维护单独移除“下一个回答”按钮的旧逻辑。

### 自动更新失败怎么办

可以稍后在“更新与关于”中重新检查。应用会依次尝试多个下载源；只有 APK 的 SHA-256 与发布元数据一致时才允许安装。

## 本地构建

准备 Android SDK、JDK 17，并克隆包含子模块的仓库：

```bash
git clone --recurse-submodules https://github.com/daxiaamu/Zhiliao.git
cd Zhiliao
./gradlew testDebugUnitTest assembleDebug
```

Windows 使用：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/`。正式 Release 需要维护者私有签名，签名材料不会提交到仓库。

兼容配置位于 `update/compatibility-v1.json`。修改后应先执行测试，不要手工编辑由 Actions 生成的 `update/update.json`、`update/update-beta.json` 或 `update/compatibility-manifest.json`。

## 反馈与贡献

- [提交问题或适配反馈](https://github.com/daxiaamu/Zhiliao/issues)
- [查看源码与 Pull Requests](https://github.com/daxiaamu/Zhiliao)
- [下载发行版本](https://github.com/daxiaamu/Zhiliao/releases)

提交代码前请至少运行 `testDebugUnitTest` 和 `assembleDebug`，并说明测试使用的知乎渠道和版本。

## License

本项目依据 [GNU General Public License v3.0](LICENSE.md) 开源。
