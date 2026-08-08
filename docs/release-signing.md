# 发布签名

正式签名只保存在维护者本机，禁止上传私钥、密钥库或密码到 GitHub、Actions
Secrets、Release 或其他网络服务。GitHub Actions 仅生成 Debug 签名的 CI 快照，
不得把该快照作为正式 Release 资产。

正式发布流程：

1. 在本机从仓库外的签名备份读取密钥库和凭据。
2. 运行完整单元测试和 Release/R8 构建。
3. 使用 `apksigner verify --print-certs` 校验 APK，并确认其证书 SHA-256 与正式证书一致；
   当前 `minSdk 26` 产物必须通过 v2/v3，v1 仅在工具实际生成时记录，不作为发布门槛。
4. 只上传最终 APK；APK 包含公钥证书，不包含私钥或密码。
5. Release 发布后核对 `update.json` / `update-beta.json` 中的版本、下载源和 APK SHA-256。
