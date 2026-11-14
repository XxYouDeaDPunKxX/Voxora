# Third-party software and licenses

This list covers the direct dependencies in `app/build.gradle` and the license declarations in the corresponding Maven POM files. Dependencies are downloaded during the build; their archives are not stored in this repository.

| Dependency | Version | Declared license | Upstream |
| --- | --- | --- | --- |
| `io.github.webrtc-sdk:android` | `144.7559.15` | BSD 3-Clause | [WebRTC Android SDK](https://github.com/webrtc-sdk/android) |
| `androidx.wear:wear-ongoing` | `1.1.0` | Apache License 2.0 | [AndroidX](https://developer.android.com/jetpack/androidx) |
| `androidx.wear.tiles:tiles` | `1.5.0` | Apache License 2.0 | [AndroidX](https://developer.android.com/jetpack/androidx) |
| `androidx.wear.protolayout:protolayout` | `1.3.0` | Apache License 2.0 | [AndroidX](https://developer.android.com/jetpack/androidx) |
| `androidx.concurrent:concurrent-futures` | `1.1.0` | Apache License 2.0 | [AndroidX](https://developer.android.com/jetpack/androidx) |
| `androidx.wear.watchface:watchface-complications-data-source` | `1.2.1` | Apache License 2.0 | [AndroidX](https://developer.android.com/jetpack/androidx) |
| `junit:junit` (tests) | `4.13.2` | Eclipse Public License 1.0 | [JUnit 4](https://github.com/junit-team/junit4) |
| `org.json:json` (tests) | `20240303` | Public Domain (as declared in POM) | [JSON-java](https://github.com/stleary/JSON-java) |

The experimental protocol integration includes adaptations from [opencoredev/login-with-chatgpt](https://github.com/opencoredev/login-with-chatgpt), commit `3befb7fb625170cb305b116a654c7e2f8672bae4`, under the MIT License. Its license text is preserved in `third_party/login-with-chatgpt-LICENSE.txt`.

The Gradle Wrapper files are generated with Gradle 8.10.2; see [Gradle's licensing information](https://gradle.org/licensing/).

The source repository and installation bundle include these license files:

- [`third_party/APACHE-2.0.txt`](third_party/APACHE-2.0.txt): Apache License 2.0 text for AndroidX runtime dependencies.
- [`third_party/protobuf-BSD-3-Clause-LICENSE.txt`](third_party/protobuf-BSD-3-Clause-LICENSE.txt): Protocol Buffers license, for the protobuf-lite code repackaged by Wear ProtoLayout.
- [`third_party/webrtc-third-party-licenses.md`](third_party/webrtc-third-party-licenses.md): WebRTC's complete third-party license compilation published with `webrtc-sdk/android` tag `v144.7559.15`.
- [`third_party/webrtc-sdk-android-LICENSE.txt`](third_party/webrtc-sdk-android-LICENSE.txt): license of the `webrtc-sdk/android` packaging project at the same tag.

These files accompany the binary distribution. The WebRTC license compilation was copied intact from the pinned upstream release.

### Runtime dependency metadata reviewed

A private audit checked Maven POM metadata for **63 resolved runtime dependencies** in the local build. It found **61 Apache License 2.0** declarations (including one inherited from the Guava parent POM) and **2 BSD 3-Clause** declarations: `io.github.webrtc-sdk:android:144.7559.15` and `androidx.wear.protolayout:protolayout-external-protobuf:1.3.0`.

The Maven audit establishes the declared licenses of the resolved dependencies. The separate WebRTC license compilation supplies the native library's upstream third-party notices. The APK itself is distributed with this notice bundle, not only the POM-based table above.
