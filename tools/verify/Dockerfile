# Verification image for the AI-cleanup feature.
#
# Deliberately has NO Android NDK, no Rust toolchain and no speech model: none of
# them are needed to type-check the Java sources or run the post-processing
# tests, and together they are several gigabytes. Building the actual APK is a
# separate job for a machine with a phone attached.
#
# Everything lives inside the image, so removing it reclaims all of it:
#   docker rmi ovi-verify && docker volume rm ovi-gradle-cache
FROM eclipse-temurin:17-jdk-jammy

# AGP 8.7.3 needs JDK 17; compileSdk 35 needs build-tools 35.
ARG ANDROID_PLATFORM=android-35
ARG ANDROID_BUILD_TOOLS=35.0.0
ARG CMDLINE_TOOLS_URL=https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
ARG JSON_JAR_URL=https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
ARG JUNIT_JAR_URL=https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.11.3/junit-platform-console-standalone-1.11.3.jar

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/gradle \
    TESTLIBS=/opt/testlibs

# app/build.gradle.kts resolves an NDK path while *configuring* the cargo task,
# falling back to android.ndkDirectory (which would try to provision an NDK).
# Pointing these at a path that doesn't exist short-circuits that lookup; the
# task itself is excluded from every command this image runs.
ENV ANDROID_NDK_HOME=/nonexistent-ndk \
    ANDROID_NDK=/nonexistent-ndk \
    ANDROID_NDK_ROOT=/nonexistent-ndk

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
 && curl -fsSL "${CMDLINE_TOOLS_URL}" -o /tmp/cmdline-tools.zip \
 && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
 && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
 && rm /tmp/cmdline-tools.zip

ENV PATH="${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools"

RUN yes | sdkmanager --licenses > /dev/null 2>&1 \
 && sdkmanager --install "platform-tools" "platforms;${ANDROID_PLATFORM}" \
      "build-tools;${ANDROID_BUILD_TOOLS}" > /dev/null

# Test-only jars. org.json is needed because android.jar ships throwing stubs of
# it ("Stub!"), so the real implementation has to come from Maven for JVM tests.
RUN mkdir -p "${TESTLIBS}" \
 && curl -fsSL "${JSON_JAR_URL}"  -o "${TESTLIBS}/json.jar" \
 && curl -fsSL "${JUNIT_JAR_URL}" -o "${TESTLIBS}/junit-console.jar"

WORKDIR /work
ENTRYPOINT ["/bin/bash", "tools/verify/verify.sh"]
CMD ["all"]
