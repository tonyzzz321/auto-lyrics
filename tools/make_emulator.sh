#!/bin/bash

# Set SDK Path
# SDK_PATH="$HOME/Library/Android/sdk"
# SDK_PATH="$HOME/Downloads/android_sdk"
SDK_PATH="."


SDK_PATH="$(realpath "$SDK_PATH")"
export ANDROID_HOME=$SDK_PATH
export ANDROID_SDK_ROOT=$SDK_PATH


# Define Variables
AVD_NAME="Pixel10"
SYSTEM_IMAGE="system-images;android-35;google_apis;arm64-v8a"
DEVICE="pixel_10"

echo "Installing system image..."
$SDK_PATH/cmdline-tools/latest/bin/sdkmanager "$SYSTEM_IMAGE"

echo "Recreating AVD (image change requires a fresh AVD)..."
$SDK_PATH/cmdline-tools/latest/bin/avdmanager delete avd -n $AVD_NAME >/dev/null 2>&1
$SDK_PATH/cmdline-tools/latest/bin/avdmanager create avd -n $AVD_NAME -k "$SYSTEM_IMAGE" -d $DEVICE

CONFIG="$HOME/.android/avd/$AVD_NAME.avd/config.ini"
sed -i -e '/disk.dataPartition.path=<temp>/d' \
       -e 's/^hw.keyboard=.*/hw.keyboard=yes/' \
       -e 's/^hw.ramSize=.*/hw.ramSize=4G/' "$CONFIG"
grep -E '^(hw.keyboard|hw.ramSize|disk.dataPartition.path)=' "$CONFIG"

echo "Launching Emulator..."
cd $SDK_PATH/emulator
./emulator -avd $AVD_NAME -no-snapshot -gpu host -memory 4096 -no-boot-anim
