# A headless Android emulator for trying the parent app (or a test lock app) without a spare phone, built on the
# build image (android/docker/Dockerfile), which already has the SDK. Started by scripts/dev/emulator, which runs it
# with KVM passed through and a memory cap, so nothing is installed on the host and the user needs no kvm group.
FROM phonelock-build
USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
      libpulse0 libnss3 libxcomposite1 libxcursor1 libxdamage1 libxi6 libxtst6 libasound2t64 libgl1 libegl1 \
      libxkbfile1 libxrandr2 libxkbcommon0 libdbus-1-3 libbsd0 socat \
    && rm -rf /var/lib/apt/lists/*
# Android 16 (API 36) with Google APIs, a Pixel 7 profile.
RUN yes | sdkmanager --licenses >/dev/null \
    && sdkmanager "emulator" "platform-tools" "system-images;android-36;google_apis;x86_64" >/dev/null \
    && echo no | avdmanager create avd -n parent -k "system-images;android-36;google_apis;x86_64" -d pixel_7 \
    && chmod -R a+rX $ANDROID_HOME
ENV PATH=$PATH:/opt/android-sdk/platform-tools:/opt/android-sdk/emulator
# 1.5 GB for Android: with 2 GB the emulator outgrew a 4 GB container cap and stopped answering adb. Its adb port
# listens on 127.0.0.1:5555 inside the container, so socat exposes it on 5556 for the host.
CMD ["sh", "-c", "emulator -avd parent -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -memory 1536 -cores 2 -no-snapshot & sleep 5; socat TCP-LISTEN:5556,fork,reuseaddr TCP:127.0.0.1:5555 & wait"]
