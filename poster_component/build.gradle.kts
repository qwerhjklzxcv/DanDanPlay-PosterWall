import setup.moduleSetup

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
}

moduleSetup()

kapt {
    arguments {
        arg("AROUTER_MODULE_NAME", name)
    }
}

android {
    namespace = "com.xyoye.poster_component"
}

dependencies {
    implementation(project(":common_component"))

    kapt(Dependencies.Alibaba.arouter_compiler)
}
