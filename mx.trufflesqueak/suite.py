#
# Copyright (c) 2017-2025 Software Architecture Group, Hasso Plattner Institute
# Copyright (c) 2021-2025 Oracle and/or its affiliates
#
# Licensed under the MIT License.
#

suite = {
    # ==========================================================================
    #  METADATA
    # ==========================================================================
    "name": "trufflesqueak",
    "mxversion": "7.33.1",
    "versionConflictResolution": "latest",
    "version": "24.1.2",
    "trufflesqueak:dependencyMap": {
        "cuis_test_image": "CuisTestImage-6.0-5053.zip",
        "cuis_test_image_tag": "21.3.0",
        "test_image": "TruffleSqueakTestImage-6.0-22104-64bit.zip",
        "test_image_tag": "22.3.0",
    },
    "release": True,
    "groupId": "de.hpi.swa.trufflesqueak",
    "url": "https://github.com/hpi-swa/trufflesqueak",
    "developer": {
        "name": "Fabio Niephaus and contributors",
        "email": "code+trufflesqueak@fniephaus.com",
        "organization": "Software Architecture Group, HPI, Potsdam, Germany",
        "organizationUrl": "https://www.hpi.uni-potsdam.de/swa/",
    },
    "scm": {
        "url": "https://github.com/hpi-swa/trufflesqueak/",
        "read": "https://github.com/hpi-swa/trufflesqueak.git",
        "write": "git@github.com:hpi-swa/trufflesqueak.git",
    },
    "licenses": {
        "GFTC": {
            "name": "GraalVM Free Terms and Conditions (GFTC) including License for Early Adopter Versions",
            "url": "https://www.oracle.com/downloads/licenses/graal-free-license.html",
        },
    },
    # ==========================================================================
    #  DEPENDENCIES
    # ==========================================================================
    "imports": {
        "suites": [
            {
                "name": "truffle",
                "subdir": True,
                "version": "vm-24.1.2",
                "urls": [{"url": "https://github.com/oracle/graal", "kind": "git"}],
            }
        ],
    },
    # ==========================================================================
    #  LIBRARIES
    # ==========================================================================
    "libraries": {
        "BOUNCYCASTLE-PROVIDER": {
            "digest": "sha512:dbc5b525d805823b53dbaded11547155a14f795212ce6fe1e93d6da431081ea9480718ea2fc17dc7906f8489aadb68e781afd1e771d26f9f8a09b21552bb165c",
            "sourceDigest": "sha512:4ce8b88e26af98c3cb8a3691ace366e960e36a8225d14685447b4aa9838b92334bdb63f8ba4baf651d28c8e063e21d0cbca8f2fcf8eecd003362ae62b6c87dbd",
            "maven": {
                "groupId": "org.bouncycastle",
                "artifactId": "bcprov-jdk18on",
                "version": "1.76",
            },
            "moduleName": "org.bouncycastle.provider",
        },
        "BOUNCYCASTLE-PKIX": {
            "digest": "sha512:b924374168e25f21ab7f6dd4f6755e55a401cbbbaa0d6f17a0c9bf59e61dc42750b200c494c413f6f8c27fc16d9312f51fc15c979e4298916f5bd0c329cbbffa",
            "sourceDigest": "sha512:6945aedc041f9282ee3569aef46c6df8940643e5a66236c5e95fafdc4dead4b94d3d64f32750ce2f131b4fdd398aacd200968103fc3e4d22eb2dc171aedb48dd",
            "maven": {
                "groupId": "org.bouncycastle",
                "artifactId": "bcpkix-jdk18on",
                "version": "1.76",
            },
            "moduleName": "org.bouncycastle.pkix",
        },
        "BOUNCYCASTLE-UTIL": {
            "digest": "sha512:385d95b4c32053bb3734c342d5f3255bcc1cee7e35649965bb5fbf8733ec37009fd5f5e06817a45e7857a2e62e923563ce1231ee3a1de411f788dfa93d39ce41",
            "sourceDigest": "sha512:8d2068b8a90381dde75f25059dfdf3073a2657ea8f7d65872f972aaae6b780a4156b39d922e10302f4c4ddaf22d5057c02e9a0cb2a228f0a43730dfba46b1b22",
            "maven": {
                "groupId": "org.bouncycastle",
                "artifactId": "bcutil-jdk18on",
                "version": "1.76",
            },
            "moduleName": "org.bouncycastle.util",
        },
        "OSVM_PLUGINS": {
            "baseurl": "https://github.com/hpi-swa/trufflesqueak/releases/download/23.1.0/osvm-plugins-202312181441",
            "os_arch": {
                "linux": {
                    "amd64": {
                        "urls": ["{baseurl}-linux-amd64.zip"],
                        "digest": "sha512:5e94f289e5e1c71772b3033fda31e637cdcbea17321f2a4448a6755dff6db2db210086cffc993320249bcb6a1df395c17a2a06aedc9636159623336ca92e8008",
                    },
                    "aarch64": {
                        "urls": ["{baseurl}-linux-aarch64.zip"],
                        "digest": "sha512:b4801b2a442ca383c6d5718c5a085b1446e66010e73587f166ff2726d393ecc47d7a195bba9d586e7f6c40d587e9a89c874a39adb3f65e9633a12703b40268e9",
                    },
                },
                "windows": {
                    "amd64": {
                        "urls": ["{baseurl}-windows-amd64.zip"],
                        "digest": "sha512:10ec2b4b783bb83a814866ea237a424138802a99ee63b3cfbe2d2b2c6607e94ea000922f58f8a159108f66c0509764bc48b62885337d2a198534337eb2ed6f8e",
                    },
                },
                "<others>": {"<others>": {"optional": True}},
            },
        },
        "TRUFFLE-ENTERPRISE": {
            "digest": "sha512:ce4cefe6706110cf352271c58d519cfed5bc73d419f1946ff03f0064a80d1a64e633cd4a1f6aa93c3e732b422ff69c132ae73767972526dbf4063cae7cc9df60",
            "maven": {
                "groupId": "org.graalvm.truffle",
                "artifactId": "truffle-enterprise",
                "version": "24.1.2",
            },
            "useModulePath": True,
            "licence": "GFTC",
        },
        "SDK-NATIVEBRIDGE": {
            "digest": "sha512:1e25b3b58ae01ef929ad71c9c1bb01376258919384810634e23a483836f53e9e77f1c5317c6220fee6bc3e7f5f36359c2ce84e5287c8335208431c675fa0925a",
            "maven": {
                "groupId": "org.graalvm.sdk",
                "artifactId": "nativebridge",
                "version": "24.1.2",
            },
            "useModulePath": True,
        },
    },
    # ==========================================================================
    #  PROJECTS
    # ==========================================================================
    "projects": {
        "de.hpi.swa.trufflesqueak": {
            "subDir": "src",
            "sourceDirs": [
                "src",
                "resources",
            ],
            "dependencies": [
                "TRUFFLESQUEAK_SHARED",
                "truffle:TRUFFLE_API",
                "truffle:TRUFFLE_NFI",
                "BOUNCYCASTLE-PROVIDER",
                "BOUNCYCASTLE-PKIX",
                "BOUNCYCASTLE-UTIL",
            ],
            "requires": [
                "java.datatransfer",
                "java.desktop",
                "java.logging",
                "java.management",
                "jdk.management",
                "jdk.unsupported",
                "jdk.jfr",
            ],
            "requiresConcealed": {
                "java.base": ["jdk.internal.module", "jdk.internal.vm.annotation"],
            },
            "checkstyleVersion": "10.7.0",
            "jacoco": "include",
            "javaCompliance": "17+",
            "annotationProcessors": ["truffle:TRUFFLE_DSL_PROCESSOR"],
            "workingSets": "TruffleSqueak",
        },
        "de.hpi.swa.trufflesqueak.launcher": {
            "subDir": "src",
            "sourceDirs": ["src"],
            "dependencies": [
                "TRUFFLESQUEAK_SHARED",
                "sdk:LAUNCHER_COMMON",
                "sdk:MAVEN_DOWNLOADER",
            ],
            "requires": [
                "java.desktop",
            ],
            "checkstyle": "de.hpi.swa.trufflesqueak",
            "jacoco": "include",
            "javaCompliance": "17+",
            "workingSets": "TruffleSqueak",
        },
        "de.hpi.swa.trufflesqueak.ffi.native": {
            "subDir": "src",
            "class": "CMakeNinjaProject",
            "vpath": True,
            "ninja_targets": ["all"],
            "os_arch": {
                "<others>": {
                    "<others>": {
                        "cmakeConfig": {},
                        "results": [
                            "<lib:SqueakFFIPrims>",
                            "<lib:InterpreterProxy>",
                        ],
                    },
                },
            },
        },
        "de.hpi.swa.trufflesqueak.shared": {
            "subDir": "src",
            "sourceDirs": ["src"],
            "dependencies": [
                "sdk:GRAAL_SDK",
            ],
            "checkstyle": "de.hpi.swa.trufflesqueak",
            "jacoco": "include",
            "javaCompliance": "17+",
            "workingSets": "TruffleSqueak",
        },
        "de.hpi.swa.trufflesqueak.tck": {
            "subDir": "src",
            "sourceDirs": ["src"],
            "dependencies": ["TRUFFLESQUEAK_SHARED", "sdk:POLYGLOT_TCK", "mx:JUNIT"],
            "checkstyle": "de.hpi.swa.trufflesqueak",
            "javaCompliance": "17+",
            "workingSets": "TruffleSqueak",
            "testProject": True,
        },
        "de.hpi.swa.trufflesqueak.test": {
            "subDir": "src",
            "sourceDirs": ["src"],
            "dependencies": [
                "de.hpi.swa.trufflesqueak",
                "mx:JUNIT",
                "sdk:MAVEN_DOWNLOADER",
            ],
            "checkstyle": "de.hpi.swa.trufflesqueak",
            "jacoco": "include",
            "javaCompliance": "17+",
            "workingSets": "TruffleSqueak",
            "testProject": True,
        },
    },
    # ==========================================================================
    #  DISTRIBUTIONS
    # ==========================================================================
    "distributions": {
        "TRUFFLESQUEAK": {
            "description": "TruffleSqueak virtual machine",
            "moduleInfo": {
                "name": "de.hpi.swa.trufflesqueak",
                "exports": [
                    "de.hpi.swa.trufflesqueak to org.graalvm.truffle",
                    "de.hpi.swa.trufflesqueak*",  # allow reflection
                ],
                "requires": [
                    "jdk.unsupported",  # sun.misc.Unsafe
                ],
            },
            "useModulePath": True,
            "dependencies": [
                "de.hpi.swa.trufflesqueak",
            ],
            "distDependencies": [
                "TRUFFLESQUEAK_SHARED",
                "truffle:TRUFFLE_API",
                "truffle:TRUFFLE_NFI",
                "truffle:TRUFFLE_NFI_LIBFFI",
            ],
            "exclude": [
                "BOUNCYCASTLE-PROVIDER",
                "BOUNCYCASTLE-PKIX",
                "BOUNCYCASTLE-UTIL",
            ],
            "javaProperties": {
                "org.graalvm.language.smalltalk.home": "<path:TRUFFLESQUEAK_HOME>",
            },
            "maven": {
                "artifactId": "smalltalk-language",
                "groupId": "de.hpi.swa.trufflesqueak",
                "tag": ["default", "public"],
            },
            "noMavenJavadoc": True,
            "license": ["MIT"],
        },
        "TRUFFLE_ENTERPRISE_PLACEHOLDER": {
            "maven": {
                "groupId": "org.graalvm.truffle",
                "artifactId": "truffle-enterprise",
            },
            "testDistribution": True,  # ensure it does not get 'maven-deploy'ed
            "noMavenJavadoc": True,
            "license": ["GFTC"],
        },
        "SMALLTALK": {
            "type": "pom",
            "runtimeDependencies": [
                "TRUFFLESQUEAK",
                "TRUFFLE_ENTERPRISE_PLACEHOLDER",
            ],
            "description": "TruffleSqueak virtual machine for Oracle GraalVM",
            "maven": {
                "groupId": "de.hpi.swa.trufflesqueak",
                "artifactId": "smalltalk",
                "tag": ["default", "public"],
            },
            "license": ["MIT", "GFTC"],
        },
        "SMALLTALK_COMMUNITY": {
            "type": "pom",
            "runtimeDependencies": [
                "TRUFFLESQUEAK",
                "truffle:TRUFFLE_RUNTIME",
            ],
            "description": "TruffleSqueak virtual machine for GraalVM CE",
            "maven": {
                "groupId": "de.hpi.swa.trufflesqueak",
                "artifactId": "smalltalk-community",
                "tag": ["default", "public"],
            },
            "license": ["MIT"],
        },
        "TRUFFLESQUEAK_HOME": {
            "native": True,
            "platformDependent": True,
            "description": "TruffleSqueak home distribution",
            "layout": {
                "LICENSE_TRUFFLESQUEAK.txt": "file:LICENSE",
                "README_TRUFFLESQUEAK.md": "file:README.md",
                "lib/": [
                    "dependency:de.hpi.swa.trufflesqueak.ffi.native/*",
                    {
                        "source_type": "extracted-dependency",
                        "dependency": "OSVM_PLUGINS",
                        "path": "*",
                    },
                ],
            },
            "maven": False,
        },
        "TRUFFLESQUEAK_LAUNCHER": {
            "description": "TruffleSqueak launcher",
            "moduleInfo": {
                "name": "de.hpi.swa.trufflesqueak.launcher",
                "exports": [
                    "de.hpi.swa.trufflesqueak.launcher to org.graalvm.launcher",
                ],
            },
            "useModulePath": True,
            "dependencies": [
                "de.hpi.swa.trufflesqueak.launcher",
            ],
            "distDependencies": [
                "TRUFFLESQUEAK_SHARED",
                "sdk:LAUNCHER_COMMON",
                "sdk:MAVEN_DOWNLOADER",
            ],
            "maven": {
                "groupId": "de.hpi.swa.trufflesqueak",
                "artifactId": "smalltalk-launcher",
                "tag": ["default", "public"],
            },
            "noMavenJavadoc": True,
            "license": ["MIT"],
        },
        "TRUFFLESQUEAK_SHARED": {
            "description": "TruffleSqueak shared distribution",
            "moduleInfo": {
                "name": "de.hpi.swa.trufflesqueak.shared",
                "exports": [
                    "de.hpi.swa.trufflesqueak.shared",
                ],
            },
            "dependencies": [
                "de.hpi.swa.trufflesqueak.shared",
            ],
            "distDependencies": [
                "sdk:GRAAL_SDK",
            ],
            "maven": {
                "groupId": "de.hpi.swa.trufflesqueak",
                "artifactId": "trufflesqueak-shared",
                "tag": ["default", "public"],
            },
            "noMavenJavadoc": True,
            "license": ["MIT"],
        },
        "TRUFFLESQUEAK_TCK": {
            "description": "TruffleSqueak TCK-based interoperability tests",
            "dependencies": [
                "de.hpi.swa.trufflesqueak.tck",
            ],
            "exclude": ["mx:JUNIT", "mx:HAMCREST"],
            "distDependencies": [
                # <workaround>TCK does not load languages correctly in 19.3
                # https://github.com/oracle/graal/commit/d5de10b9cc889104ac4c381fc17e8e92ff9cd186
                "TRUFFLESQUEAK",
                # </workaround>
                "TRUFFLESQUEAK_SHARED",
                "sdk:POLYGLOT_TCK",
            ],
            "testDistribution": True,
            "maven": False,
        },
        "TRUFFLESQUEAK_TEST": {
            "description": "TruffleSqueak JUnit and SUnit tests",
            "moduleInfo": {
                "name": "de.hpi.swa.trufflesqueak.test",
                "exports": [
                    # Export everything to junit and dependent test distributions.
                    "de.hpi.swa.trufflesqueak.test*",
                ],
            },
            "useModulePath": True,
            "javaCompliance": "17+",
            "dependencies": [
                "de.hpi.swa.trufflesqueak.test",
            ],
            "exclude": ["mx:JUNIT", "mx:HAMCREST"],
            "distDependencies": [
                "TRUFFLESQUEAK",
                "TRUFFLESQUEAK_SHARED",
                "sdk:MAVEN_DOWNLOADER",
                "truffle:TRUFFLE_API",
            ],
            "testDistribution": True,
            "maven": False,
        },
    },
}
