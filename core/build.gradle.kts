plugins {
    alias(libs.plugins.jotter.jvm.library)
}

dependencies {
    // JSON trees for reading library files; the writer reproducing Qt's output is our own.
    api(libs.kotlinx.serialization.json)
    // SQLite with FTS5 for the index; the platform SQLite lacks FTS5.
    implementation(libs.androidx.sqlite.bundled)
    // SFTP for synchronisation: sshj creates files exclusively and renames through
    // posix-rename@openssh.com; Bouncy Castle gives it Ed25519 and X25519 on Android versions whose
    // platform lacks them, aligned with the version sshj asks for at least.
    implementation(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    testImplementation(libs.junit)
}
