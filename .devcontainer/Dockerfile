# Same devcontainers base family as the other projects. Nothing here needs the
# old 22.04 libraries the Garmin simulator does, so this one is on 24.04.
# The vscode user, sudo, git, curl and jq come from the base image.
FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

ARG DEBIAN_FRONTEND=noninteractive

# JDK: javac for build.sh, and the SDK's own tools (sdkmanager, d8,
# apksigner) are Java. Python runs tools/package.py.
RUN apt-get update && apt-get install -y --no-install-recommends \
      openjdk-21-jdk-headless python3 make unzip zip \
    && rm -rf /var/lib/apt/lists/*

# Android SDK. No login is needed, so it lives in the image rather than a
# volume: command-line tools first, then only the three packages build.sh uses.
# Build number of the command-line tools: https://developer.android.com/studio
ARG CMDLINE_TOOLS_BUILD=15859902
ARG ANDROID_PLATFORM=android-30
ARG ANDROID_BUILD_TOOLS=34.0.0

ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}

# `sdkmanager --licenses` accepts the Android SDK licence agreements for you.
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && curl -fsSL -o /tmp/cmdline-tools.zip \
         https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip \
    && unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_HOME}/cmdline-tools \
    && mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && rm /tmp/cmdline-tools.zip \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager "platform-tools" "platforms;${ANDROID_PLATFORM}" "build-tools;${ANDROID_BUILD_TOOLS}" \
    && chown -R vscode:vscode ${ANDROID_HOME}

USER vscode
