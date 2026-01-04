#!/bin/bash

# 注意：M4 芯片需要使用 arm64 版本的 GraalVM
# 下载地址: https://github.com/gluonhq/graal/releases
# 选择 darwin-aarch64 版本，例如: graalvm-java23-darwin-aarch64-gluon-23+25.1-dev

# 如果你已下载 arm64 版本，请更新下面的路径
# export GRAALVM_HOME="/Library/Java/JavaVirtualMachines/graalvm-java23-darwin-aarch64-gluon-23+25.1-dev/Contents/Home"

# 当前使用 amd64 版本（通过 Rosetta 2 运行，不推荐）
export GRAALVM_HOME="/Library/Java/JavaVirtualMachines/graalvm-java23-darwin-aarch64-gluon-23+25.1-dev/Contents/Home"

#mvn gluonfx:build && mvn gluonfx:package

#echo "build android app"
#mvn gluonfx:build -Pandroid && mvn gluonfx:package -Pandroid

echo "build ios app"
mvn clean
mvn gluonfx:build -Pios
mvn gluonfx:package -Pios
