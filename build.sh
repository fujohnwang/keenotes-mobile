export GRAALVM_HOME="/Library/Java/JavaVirtualMachines/graalvm-java23-darwin-amd64-gluon-23+25.1-dev/Contents/Home"

echo "build android app"
mvn gluonfx:build -Pandroid && mvn gluonfx:package -Pandroid

echo "build ios app"
mvn gluonfx:build -Pios && mvn gluonfx:package -Pios


