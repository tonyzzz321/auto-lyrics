#!/bin/bash

# Android Automotive (AAOS) emulator for visually testing the Android Auto
# browse UI (Lyrics / Karaoke / Sync / More tabs) without a head unit.
# The app renders as a media source in the car launcher's Media app.

# Run from the Android SDK root (same convention as make_emulator.sh).
SDK_PATH="$(realpath .)"
export ANDROID_HOME=$SDK_PATH
export ANDROID_SDK_ROOT=$SDK_PATH

AVD_NAME="AAHeadUnit"
SYSTEM_IMAGE="system-images;android-34-ext9;android-automotive;arm64-v8a"
DEVICE="automotive_1024p_landscape"

echo "Installing system image (~2 GB on first run)..."
$SDK_PATH/cmdline-tools/latest/bin/sdkmanager "$SYSTEM_IMAGE"

echo "Recreating AVD..."
$SDK_PATH/cmdline-tools/latest/bin/avdmanager delete avd -n $AVD_NAME >/dev/null 2>&1
echo no | $SDK_PATH/cmdline-tools/latest/bin/avdmanager create avd -n $AVD_NAME -k "$SYSTEM_IMAGE" -d $DEVICE

CONFIG="$HOME/.android/avd/$AVD_NAME.avd/config.ini"
sed -i -e '/disk.dataPartition.path=<temp>/d' \
       -e 's/^hw.keyboard=.*/hw.keyboard=yes/' \
       -e 's/^hw.ramSize=.*/hw.ramSize=4G/' "$CONFIG"
grep -E '^(hw.keyboard|hw.ramSize|disk.dataPartition.path)=' "$CONFIG"

echo "Launching Emulator..."
cd $SDK_PATH/emulator
# Fixed console port 5556 so adb reaches the car at :5557 even when the
# phone emulator already holds 5554/5555 on this machine.
./emulator -avd $AVD_NAME -port 5556 -no-snapshot -gpu host -memory 4096 -no-boot-anim -no-audio
