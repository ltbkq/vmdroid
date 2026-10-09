plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.emulator"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        externalNativeBuild {
            ndkBuild {
                cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-fno-stack-protector")
                // Linker flags (16 KB page alignment + gc-sections) live in Android.mk via LOCAL_LDFLAGS.
            }
        }

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // vendored Termux 测试在 JVM 上执行，SystemClock.uptimeMillis 等框架方法
        // 默认抛 "Method ... not mocked"（108 个用例因此全挂）。返回默认值即可跑通。
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.0")
    // 测试源码用了 junit.framework.*（JUnit3 风格）与 org.junit.Assert（JUnit4）
    // —— 没有这条依赖时 :terminal-emulator:compileDebugUnitTestJavaWithJavac 会报
    // 「程序包 junit.framework 不存在」（100 个错误），单测整体跑不起来。
    testImplementation(libs.junit)
}
