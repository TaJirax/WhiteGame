plugins {
    kotlin("jvm") version "1.9.22"
    application
}

version = "7.3.0"

kotlin {
    jvmToolchain(17)
    sourceSets.named("main") {
        kotlin.srcDir("../app/src/main/java")
        kotlin.include("com/whitegame/desktop/**", "com/whitegame/app/network/RouteTlsPlatform.kt")
        kotlin.include(
            listOf(
                "connection/AwgConfig", "connection/Curve25519", "connection/EnrollmentKey",
                "connection/WarpProvisioning", "connection/HostedProvisioning", "connection/WireGuardProbe",
                "connection/TunnelExport", "data/DnsData", "data/SubscriptionSource", "data/SubscriptionScan",
                "model/Models", "network/CloudflareSpeedTest", "network/Dns", "network/ProbeStats",
                "network/RouteProbe", "network/NetworkProber", "network/GameComparison", "xray/XrayConfig"
            ).map { "com/whitegame/app/$it.kt" }
        )
    }
}

sourceSets.named("main") {
    resources.srcDir("../app/src/main/assets")
    resources.include("dns.json", "games_online.json", "*.png", "whitegame/*.ps1")
}

tasks.processResources {
    from("../app/src/main/res/drawable/logo_bw.png")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    implementation("org.json:json:20240303")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.whitegame.desktop.MainKt")
}
