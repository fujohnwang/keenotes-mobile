实际上**notarization是可以完全自动化的**，而且不需要人工审批。你可能把它和App Store审核搞混了：

- **App Store审核**：需要人工审批，几天到几周
- **Notarization（公证）**：完全自动化，通常几分钟内完成

Notarization流程：
1. 上传app到Apple服务器
2. 自动扫描恶意软件
3. 几分钟后返回结果（通过/失败）
4. 将ticket staple到app上

这个过程可以完全集成到GitHub Actions中。让我给你添加一个完整的签名+公证workflow：

现在workflow已经支持自动签名和公证了！它的工作方式：

**如果没有配置secrets**（当前状态）：
- 构建未签名的app
- 包含INSTALL-MACOS.txt说明文件

**如果配置了secrets**：
- 自动签名app
- 自动提交公证（几分钟内完成）
- Staple公证ticket到DMG/PKG
- 用户下载后可以直接打开，无需任何额外步骤

**需要的GitHub Secrets**（如果你想启用签名+公证）：

1. **MACOS_CERTIFICATE**: 开发者证书（.p12文件，base64编码）
2. **MACOS_CERTIFICATE_PWD**: 证书密码
3. **MACOS_SIGNING_IDENTITY**: 签名身份（如"Developer ID Application: Your Name (TEAM_ID)"）
4. **MACOS_INSTALLER_IDENTITY**: 安装包签名身份（如"Developer ID Installer: Your Name (TEAM_ID)"）
5. **MACOS_NOTARIZATION_APPLE_ID**: Apple ID邮箱
6. **MACOS_NOTARIZATION_PWD**: App-specific password（不是Apple ID密码）
7. **MACOS_NOTARIZATION_TEAM_ID**: Team ID（10位字符）
8. **KEYCHAIN_PASSWORD**: 临时keychain密码（随机字符串即可）

**获取这些的步骤**：
1. 注册Apple Developer账号（$99/年）
2. 在Xcode中创建Developer ID证书
3. 导出证书为.p12文件
4. 在Apple ID账号设置中创建app-specific password
5. 将这些信息添加到GitHub仓库的Secrets中

**Notarization时间**：通常2-5分钟，最多15分钟，完全自动化，无需人工审批。

现在的配置是向后兼容的：没有secrets时构建未签名版本，有secrets时自动签名+公证。


---

## .p12文件怎么base64编码？

在macOS上，使用Terminal命令：

```bash
base64 -i your-certificate.p12 | pbcopy
```

这个命令会：
1. 读取.p12文件
2. 转换为base64编码
3. 自动复制到剪贴板

然后直接粘贴到GitHub Secrets中即可。

**完整步骤**：

1. **导出证书为.p12**（在Xcode中）：
   - 打开Xcode → Preferences → Accounts
   - 选择你的Apple ID → Manage Certificates
   - 右键点击"Developer ID Application"证书
   - 选择"Export Certificate..."
   - 保存为`certificate.p12`，设置一个密码

2. **Base64编码**：
   ```bash
   base64 -i certificate.p12 | pbcopy
   ```

3. **添加到GitHub Secrets**：
   - 进入GitHub仓库 → Settings → Secrets and variables → Actions
   - 点击"New repository secret"
   - Name: `MACOS_CERTIFICATE`
   - Value: 粘贴刚才复制的base64字符串
   - 点击"Add secret"

4. **添加证书密码**：
   - Name: `MACOS_CERTIFICATE_PWD`
   - Value: 你在导出.p12时设置的密码

**如果想验证base64编码是否正确**：
```bash
# 编码
base64 -i certificate.p12 -o certificate.txt

# 解码验证
base64 -d -i certificate.txt -o certificate-test.p12

# 比较文件
diff certificate.p12 certificate-test.p12
```

如果没有输出，说明编码/解码成功，文件一致。

