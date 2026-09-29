pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // Lyane: sherpa-onnx AAR doğrudan k2-fsa GitHub Release'lerinden çekilir.
        // Bağımlılık: com.k2-fsa:sherpa-onnx:<version>
        // Örüntü: https://github.com/k2-fsa/sherpa-onnx/releases/download/v<version>/sherpa-onnx-<version>.aar
        //
        // Çevrimdışı derleme için AAR'ı elle indirip app/libs/ içine koyun ve
        // app/build.gradle.kts içindeki "files(...)" satırını etkinleştirin.
        ivy {
            name = "SherpaOnnxGitHub"
            url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
            patternLayout {
                artifact("v[revision]/[artifact]-[revision].[ext]")
            }
            metadataSources { artifact() }
        }
    }
}

rootProject.name = "Lyane"
include(":app")
