
```
# 仅构建当前平台安装包（输出到 dist/）
./scripts/release-local.sh --version 1.2.3

# 构建并发布到 GitHub Releases
./scripts/release-local.sh --version 1.2.3 --publish

# 若当前 HEAD 已打 v* tag，可省略 --version
git checkout v1.2.3 && ./scripts/release-local.sh --publish
```

## Git hooks（推荐）

Release 脚本会在工作区改写 `BuildInfo.java`（正式版号），不应提交进仓库。启用 pre-commit 检查：

```bash
./scripts/setup-git-hooks.sh
```

提交时若 staged 的 `BuildInfo.java` 中 `VERSION != "dev"`，commit 会被拒绝。




## macOS 签名与公证（可选）
未设置 MACOS_SIGNING_IDENTITY 时走 unsigned 路径（类似 desktop-build.yml），并生成 INSTALL-MACOS.txt。

配置签名环境变量后，流程与 CI 的 release.yml 一致：app-image → SQLite 补丁 → codesign → DMG → notarization。

```
export MACOS_SIGNING_IDENTITY="Developer ID Application: ..."
export MACOS_NOTARIZATION_APPLE_ID="..."
export MACOS_NOTARIZATION_PWD="..."
export MACOS_NOTARIZATION_TEAM_ID="..."
./scripts/release-local.sh --version 1.2.3 --publish
```
