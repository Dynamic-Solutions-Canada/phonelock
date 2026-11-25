# PhoneLock's Android build image (PLAT-12): the JDK, the Android SDK packages the build needs, and git (the
# version code is the commit count). scripts/dev/gradle runs Gradle in a disposable container from it, under a
# hard memory cap; nothing is installed on the host. Build it with `npm run build:image`.
# Versions are pinned: change them here, and keep them matching compileSdk in the build files.
FROM eclipse-temurin:17.0.21_9-jdk-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends git unzip curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
# sdkmanager is a JVM tool; keep it small so the image build stays light on memory.
ENV SDKMANAGER_OPTS=-Xmx512m

# The Android command-line tools (cmdline-tools 19.0).
ARG CMDLINE_TOOLS=16111833
RUN mkdir -p $ANDROID_HOME/cmdline-tools \
    && curl -fsSL -o /tmp/clt.zip "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip" \
    && unzip -q /tmp/clt.zip -d $ANDROID_HOME/cmdline-tools \
    && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
    && rm /tmp/clt.zip

# compileSdk is the android-37.2 package; build-tools 36.0.0 is what AGP 9.5 uses.
RUN yes | sdkmanager --licenses >/dev/null \
    && sdkmanager "platforms;android-37.2" "build-tools;36.0.0" >/dev/null \
    && chmod -R a+rX $ANDROID_HOME
