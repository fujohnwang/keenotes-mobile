# GitHub Actions Runner版本更新

## 问题描述
GitHub Actions构建任务被取消，因为使用的macOS runner版本不再受支持。

## 解决方案
根据GitHub Actions的提示信息，更新了macOS runner版本：

### 更新前
```yaml
- arch: mac
  name-suffix: Intel
  runner: macos-13  # 已弃用
- arch: mac-aarch64  
  name-suffix: AppleSilicon
  runner: macos-latest  # 不明确版本
```

### 更新后
```yaml
- arch: mac
  name-suffix: Intel
  runner: macos-12  # Intel macOS runner - 支持的版本
- arch: mac-aarch64  
  name-suffix: AppleSilicon
  runner: macos-14  # Apple Silicon macOS runner - 明确版本
```

## 修改的文件
- `.github/workflows/desktop-build.yml` - 更新了macOS runner版本

## 其他Runner状态
- **Windows**: `windows-latest` - 无需更改，仍然受支持
- **Linux**: `ubuntu-latest` - 无需更改，仍然受支持
- **Android**: `ubuntu-latest` - 无需更改，仍然受支持

## 预期结果
- macOS Intel构建将使用`macos-12`运行器
- macOS Apple Silicon构建将使用`macos-14`运行器
- 构建任务不再被取消
- 所有平台的构建应该能正常执行

## 验证步骤
1. 提交更改到GitHub
2. 观察GitHub Actions是否正常启动
3. 确认macOS构建任务不再被取消
4. 验证生成的DMG和PKG文件

## 注意事项
- `macos-12`是Intel架构的最后一个稳定版本
- `macos-14`是Apple Silicon的推荐版本
- 如果将来这些版本也被弃用，需要继续更新到更新的版本