#!/usr/bin/env bash
set -euo pipefail
cd /home/bers/Projects/mypiazzetta-app/tools
if [ ! -d jdk-21 ]; then
  mkdir -p jdk-21
  tar xf jdk21.tar.gz -C jdk-21 --strip-components=1
fi
export JAVA_HOME=/home/bers/Projects/mypiazzetta-app/tools/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
java -version
if [ ! -d /home/bers/Projects/mypiazzetta-app/tools/sdk/cmdline-tools/latest ]; then
  rm -rf /home/bers/Projects/mypiazzetta-app/tools/cmdline-tools-unz /home/bers/Projects/mypiazzetta-app/tools/sdk/cmdline-tools
  mkdir -p /home/bers/Projects/mypiazzetta-app/tools/sdk/cmdline-tools
  unzip -qq cmdline-tools.zip -d /home/bers/Projects/mypiazzetta-app/tools/cmdline-tools-unz
  mv /home/bers/Projects/mypiazzetta-app/tools/cmdline-tools-unz/cmdline-tools /home/bers/Projects/mypiazzetta-app/tools/sdk/cmdline-tools/latest
fi
export ANDROID_HOME=/home/bers/Projects/mypiazzetta-app/tools/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
yes | /home/bers/Projects/mypiazzetta-app/tools/sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME --licenses >/dev/null 2>&1 || true
/home/bers/Projects/mypiazzetta-app/tools/sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME "platform-tools" "platforms;android-34" "build-tools;34.0.0" "emulator" "system-images;android-34;google_apis;x86_64"
/home/bers/Projects/mypiazzetta-app/tools/sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME --list_installed
echo TOOLCHAIN_READY
