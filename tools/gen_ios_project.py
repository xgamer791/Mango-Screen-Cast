#!/usr/bin/env python3
"""Writes ios/MangoScreenCast.xcodeproj.

    python3 tools/gen_ios_project.py

The project is generated so that the C sources it compiles (the AirPlay core
shared with the Android TV app: UxPlay, libplist, mango_server.c) always
match what's in the submodules. Run it again after adding or removing a
file, and commit the result. Object ids are derived from names, so
regenerating an unchanged tree gives an identical file.
"""
import glob
import hashlib
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
IOS = os.path.join(ROOT, "ios")
PROJECT = os.path.join(IOS, "MangoScreenCast.xcodeproj")

APP = "MangoScreenCast"
CORE = "MangoCore"
TESTS = "MangoScreenCastTests"
UI_TESTS = "MangoScreenCastUITests"
BUNDLE_ID = "com.mangoscreencast.ios"
DEPLOYMENT_TARGET = "16.0"

OPENSSL_PACKAGE = "https://github.com/krzyzanowskim/OpenSSL-Package.git"
OPENSSL_VERSION = "3.6.5000"

# Paths relative to ios/ (the project's SRCROOT).
SHARED_CPP = "../android-tv/app/src/main/cpp"
UXPLAY = "../android-tv/third_party/uxplay/lib"
PLIST = "../android-tv/third_party/libplist"
# The libplist sources the Android build compiles.
PLIST_SOURCES = ["base64.c", "bplist.c", "bytearray.c", "common.c", "hashtable.c", "jplist.c", "jsmn.c",
                 "oplist.c", "out-default.c", "out-limd.c", "out-plutil.c", "plist.c", "ptrarray.c",
                 "time64.c", "xplist.c"]
# App sources the (host-less) unit tests compile too.
TESTED_APP_SOURCES = ["Media/NalUnits.swift", "Media/AudioCookie.swift", "Media/VideoRenderer.swift",
                      "AirPlay/MirrorProfile.swift", "Updates/ReleaseFeed.swift", "MangoCast/MangoCast.swift",
                      "MangoCast/MangoCastServer.swift"]

FILE_TYPES = {
    ".swift": "sourcecode.swift",
    ".c": "sourcecode.c.c",
    ".h": "sourcecode.c.h",
    ".plist": "text.plist.xml",
    ".xcassets": "folder.assetcatalog",
}


def ident(*parts):
    return hashlib.md5("/".join(parts).encode()).hexdigest()[:24].upper()


objects = {}


def add(kind, key, **fields):
    oid = ident(kind, key)
    objects[oid] = dict(isa=kind, **fields)
    return oid


def file_ref(path, name=None, source_tree="<group>"):
    ext = os.path.splitext(path)[1]
    fields = dict(lastKnownFileType=FILE_TYPES[ext], path=path, sourceTree=source_tree)
    if name:
        fields["name"] = name
    return add("PBXFileReference", source_tree + ":" + path, **fields)


def group(key, children, path=None, name=None):
    fields = dict(children=children, sourceTree="<group>")
    if path:
        fields["path"] = path
    if name:
        fields["name"] = name
    return add("PBXGroup", key, **fields)


def build_file(target, ref, settings=None):
    fields = dict(fileRef=ref)
    if settings:
        fields["settings"] = settings
    return add("PBXBuildFile", target + ":" + ref, **fields)


def rel(path):
    return os.path.relpath(path, IOS)


# --- App sources: groups mirror ios/MangoScreenCast ------------------------

def folder_group(directory, key):
    """A group for `directory`, with Swift/plist/header files and subfolders."""
    children, sources = [], {}
    for entry in sorted(os.listdir(directory)):
        full = os.path.join(directory, entry)
        ext = os.path.splitext(entry)[1]
        if os.path.isdir(full) and ext != ".xcassets":
            sub, sub_sources = folder_group(full, key + "/" + entry)
            children.append(sub)
            sources.update(sub_sources)
        elif ext in FILE_TYPES:
            ref = add("PBXFileReference", key + "/" + entry, lastKnownFileType=FILE_TYPES[ext], path=entry,
                      sourceTree="<group>")
            children.append(ref)
            sources[os.path.relpath(full, os.path.join(IOS, key.split("/")[0]))] = ref
    return group(key, children, path=os.path.basename(directory)), sources


app_group, app_files = folder_group(os.path.join(IOS, APP), APP)
tests_group, test_files = folder_group(os.path.join(IOS, TESTS), TESTS)
ui_tests_group, ui_test_files = folder_group(os.path.join(IOS, UI_TESTS), UI_TESTS)

# --- MangoCore: the bridge plus the shared AirPlay core --------------------

core_own = [file_ref("mango_bridge.c"), file_ref("mango_bridge.h")]
shared_sources = (
    [SHARED_CPP + "/mango_server.c", SHARED_CPP + "/android_dnssd/dns_sd_android.c"]
    + sorted(rel(p) for p in glob.glob(os.path.join(IOS, UXPLAY, "*.c")))
    + sorted(rel(p) for p in glob.glob(os.path.join(IOS, UXPLAY, "playfair", "*.c")))
    + sorted(rel(p) for p in glob.glob(os.path.join(IOS, UXPLAY, "llhttp", "*.c")))
    + [UXPLAY + "/dns_sd/dns_sd.c"]
    + [PLIST + "/src/" + name for name in PLIST_SOURCES]
    + [PLIST + "/libcnary/node.c", PLIST + "/libcnary/node_list.c"]
)
missing = [p for p in shared_sources if not os.path.exists(os.path.join(IOS, p))]
if missing:
    raise SystemExit("Missing " + ", ".join(missing) + ". Run: git submodule update --init")


def shared_name(path):
    for prefix, label in ((SHARED_CPP + "/", ""), (UXPLAY + "/", "uxplay/"), (PLIST + "/", "libplist/")):
        if path.startswith(prefix):
            return label + path[len(prefix):]
    return path


shared_refs = [file_ref(p, name=shared_name(p), source_tree="SOURCE_ROOT") for p in shared_sources]
shared_group = group("SharedCore", shared_refs, name="Shared AirPlay core")
core_group = group(CORE, core_own + [shared_group], path=CORE)

# --- Products, packages ----------------------------------------------------

app_product = add("PBXFileReference", "product:" + APP, explicitFileType="wrapper.application",
                  includeInIndex="0", path=APP + ".app", sourceTree="BUILT_PRODUCTS_DIR")
core_product = add("PBXFileReference", "product:" + CORE, explicitFileType="archive.ar", includeInIndex="0",
                   path="lib" + CORE + ".a", sourceTree="BUILT_PRODUCTS_DIR")
tests_product = add("PBXFileReference", "product:" + TESTS, explicitFileType="wrapper.cfbundle",
                    includeInIndex="0", path=TESTS + ".xctest", sourceTree="BUILT_PRODUCTS_DIR")
ui_tests_product = add("PBXFileReference", "product:" + UI_TESTS, explicitFileType="wrapper.cfbundle",
                       includeInIndex="0", path=UI_TESTS + ".xctest", sourceTree="BUILT_PRODUCTS_DIR")
products_group = group("Products", [app_product, core_product, tests_product, ui_tests_product], name="Products")
main_group = group("Main", [app_group, core_group, tests_group, ui_tests_group, products_group])

package = add("XCRemoteSwiftPackageReference", "OpenSSL", repositoryURL=OPENSSL_PACKAGE,
              requirement=dict(kind="upToNextMajorVersion", minimumVersion=OPENSSL_VERSION))
openssl_for = {t: add("XCSwiftPackageProductDependency", "OpenSSL:" + t, package=package, productName="OpenSSL")
               for t in (APP, CORE)}

# --- Build settings ----------------------------------------------------------

PROJECT_SETTINGS = {
    "ALWAYS_SEARCH_USER_PATHS": "NO",
    "CLANG_ANALYZER_NONNULL": "YES",
    "CLANG_ENABLE_MODULES": "YES",
    "CLANG_ENABLE_OBJC_ARC": "YES",
    "CLANG_WARN_DOCUMENTATION_COMMENTS": "NO",
    "COPY_PHASE_STRIP": "NO",
    "ENABLE_STRICT_OBJC_MSGSEND": "YES",
    "GCC_C_LANGUAGE_STANDARD": "gnu11",
    "GCC_NO_COMMON_BLOCKS": "YES",
    "IPHONEOS_DEPLOYMENT_TARGET": DEPLOYMENT_TARGET,
    "SDKROOT": "iphoneos",
    "SWIFT_VERSION": "5.0",
    "TARGETED_DEVICE_FAMILY": "1,2",
}
DEBUG_SETTINGS = {
    "DEBUG_INFORMATION_FORMAT": "dwarf",
    "ENABLE_TESTABILITY": "YES",
    "GCC_OPTIMIZATION_LEVEL": "0",
    "GCC_PREPROCESSOR_DEFINITIONS": ["DEBUG=1", "$(inherited)"],
    "ONLY_ACTIVE_ARCH": "YES",
    "SWIFT_ACTIVE_COMPILATION_CONDITIONS": "DEBUG",
    "SWIFT_OPTIMIZATION_LEVEL": "-Onone",
}
RELEASE_SETTINGS = {
    "DEBUG_INFORMATION_FORMAT": "dwarf-with-dsym",
    "ENABLE_NS_ASSERTIONS": "NO",
    "SWIFT_COMPILATION_MODE": "wholemodule",
    "SWIFT_OPTIMIZATION_LEVEL": "-O",
    "VALIDATE_PRODUCT": "YES",
}
APP_SETTINGS = {
    "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon",
    "CODE_SIGN_STYLE": "Automatic",
    "CURRENT_PROJECT_VERSION": "1",
    "DEVELOPMENT_TEAM": "",
    "ENABLE_PREVIEWS": "NO",
    "INFOPLIST_FILE": APP + "/Info.plist",
    "LD_RUNPATH_SEARCH_PATHS": ["$(inherited)", "@executable_path/Frameworks"],
    # CI sets these per build (1.0.<run>, <run>, release); see ios.yml.
    "MANGO_BUILD_CHANNEL": "local",
    "MARKETING_VERSION": "1.0",
    "PRODUCT_BUNDLE_IDENTIFIER": BUNDLE_ID,
    "PRODUCT_NAME": "$(TARGET_NAME)",
    "SWIFT_OBJC_BRIDGING_HEADER": APP + "/" + APP + "-Bridging-Header.h",
}
CORE_SETTINGS = {
    # Third-party C: always optimised, plain C (no modules or header maps,
    # so UxPlay's threads.h etc. can't shadow system headers).
    "CLANG_ENABLE_MODULES": "NO",
    "GCC_OPTIMIZATION_LEVEL": "s",
    "GCC_PREPROCESSOR_DEFINITIONS": [
        "$(inherited)", "STANDALONE", "TARGET_POSIX", "_REENTRANT", "NOHOLD", "PLIST_210", "PLIST_230",
        "HAVE_CONFIG_H", "LIBPLIST_STATIC", "OPENSSL_API_COMPAT=0x10101000L", "__STDC_CONSTANT_MACROS",
        "__STDC_LIMIT_MACROS"],
    # <openssl/...>, <dns_sd.h>, <config.h>, <plist/plist.h>, <node.h>, <hashtable.h>
    "HEADER_SEARCH_PATHS": [
        "$(SRCROOT)/" + CORE + "/openssl-shim",
        "$(SRCROOT)/" + SHARED_CPP + "/android_dnssd",
        "$(SRCROOT)/" + SHARED_CPP + "/plist_config",
        "$(SRCROOT)/" + PLIST + "/include",
        "$(SRCROOT)/" + PLIST + "/libcnary/include",
        "$(SRCROOT)/" + PLIST + "/src",
    ],
    # "raop.h", "logger.h", "mango_server.h", ...
    "USER_HEADER_SEARCH_PATHS": [
        "$(SRCROOT)/" + UXPLAY,
        "$(SRCROOT)/" + UXPLAY + "/playfair",
        "$(SRCROOT)/" + UXPLAY + "/llhttp",
        "$(SRCROOT)/" + SHARED_CPP,
    ],
    "PRODUCT_NAME": "$(TARGET_NAME)",
    "SKIP_INSTALL": "YES",
    "USE_HEADERMAP": "NO",
    "WARNING_CFLAGS": ["-Wno-unused-parameter", "-Wno-unused-function", "-Wno-unused-variable",
                       "-Wno-deprecated-declarations", "-Wno-shorten-64-to-32", "-Wno-sign-compare",
                       "-Wno-comma", "-Wno-strict-prototypes", "-Wno-parentheses",
                       "-Wno-incompatible-pointer-types-discards-qualifiers"],
}
TESTS_SETTINGS = {
    "CODE_SIGN_STYLE": "Automatic",
    "GENERATE_INFOPLIST_FILE": "YES",
    "PRODUCT_BUNDLE_IDENTIFIER": BUNDLE_ID + ".tests",
    "PRODUCT_NAME": "$(TARGET_NAME)",
}


def config_list(key, base, extra_debug=None, extra_release=None):
    configs = []
    for name, variant, extra in (("Debug", DEBUG_SETTINGS, extra_debug), ("Release", RELEASE_SETTINGS, extra_release)):
        settings = dict(base)
        if key == "Project":
            settings.update(variant)
        settings.update(extra or {})
        configs.append(add("XCBuildConfiguration", key + ":" + name, buildSettings=settings, name=name))
    return add("XCConfigurationList", key, buildConfigurations=configs,
               defaultConfigurationIsVisible="0", defaultConfigurationName="Release")


def phase(kind, target, files):
    return add(kind, target, buildActionMask="2147483647", files=files, runOnlyForDeploymentPostprocessing="0")


def target(name, product_type, product, settings, phases, dependencies=(), packages=()):
    fields = dict(buildConfigurationList=config_list(name, settings), buildPhases=phases, buildRules=[],
                  dependencies=list(dependencies), name=name, productName=name, productReference=product,
                  productType=product_type)
    if packages:
        fields["packageProductDependencies"] = list(packages)
    return add("PBXNativeTarget", name, **fields)


project_id = ident("PBXProject", "project")

core_sources = [build_file(CORE, r) for r in [core_own[0]] + shared_refs]
core_target = target(
    CORE, "com.apple.product-type.library.static", core_product, CORE_SETTINGS,
    [phase("PBXSourcesBuildPhase", CORE, core_sources),
     phase("PBXFrameworksBuildPhase", CORE, [add("PBXBuildFile", CORE + ":openssl", productRef=openssl_for[CORE])])],
    packages=[openssl_for[CORE]])

core_proxy = add("PBXContainerItemProxy", "core", containerPortal=project_id, proxyType="1",
                 remoteGlobalIDString=core_target, remoteInfo=CORE)
core_dependency = add("PBXTargetDependency", "core", target=core_target, targetProxy=core_proxy)

app_sources = [build_file(APP, ref) for path, ref in sorted(app_files.items()) if path.endswith(".swift")]
app_resources = [build_file(APP, app_files["Assets.xcassets"])]
app_frameworks = [build_file(APP, core_product), add("PBXBuildFile", APP + ":openssl", productRef=openssl_for[APP])]
app_target = target(
    APP, "com.apple.product-type.application", app_product, APP_SETTINGS,
    [phase("PBXSourcesBuildPhase", APP, app_sources),
     phase("PBXFrameworksBuildPhase", APP, app_frameworks),
     phase("PBXResourcesBuildPhase", APP, app_resources)],
    dependencies=[core_dependency], packages=[openssl_for[APP]])

test_sources = ([build_file(TESTS, ref) for path, ref in sorted(test_files.items()) if path.endswith(".swift")]
                + [build_file(TESTS, app_files[p]) for p in TESTED_APP_SOURCES])
tests_target = target(
    TESTS, "com.apple.product-type.bundle.unit-test", tests_product, TESTS_SETTINGS,
    [phase("PBXSourcesBuildPhase", TESTS, test_sources),
     phase("PBXFrameworksBuildPhase", TESTS, []),
     phase("PBXResourcesBuildPhase", TESTS, [])])

# Screenshots of the app's screens on each simulator, for checking layouts
# (the Screenshots scheme; see ios.yml).
app_proxy = add("PBXContainerItemProxy", "app", containerPortal=project_id, proxyType="1",
                remoteGlobalIDString=app_target, remoteInfo=APP)
app_dependency = add("PBXTargetDependency", "app", target=app_target, targetProxy=app_proxy)
ui_tests_target = target(
    UI_TESTS, "com.apple.product-type.bundle.ui-testing", ui_tests_product,
    dict(TESTS_SETTINGS, PRODUCT_BUNDLE_IDENTIFIER=BUNDLE_ID + ".uitests", TEST_TARGET_NAME=APP),
    [phase("PBXSourcesBuildPhase", UI_TESTS,
           [build_file(UI_TESTS, ref) for path, ref in sorted(ui_test_files.items()) if path.endswith(".swift")]),
     phase("PBXFrameworksBuildPhase", UI_TESTS, []),
     phase("PBXResourcesBuildPhase", UI_TESTS, [])],
    dependencies=[app_dependency])

objects[project_id] = dict(
    isa="PBXProject",
    attributes=dict(BuildIndependentTargetsInParallel="1", LastSwiftUpdateCheck="1600", LastUpgradeCheck="1600",
                    TargetAttributes={t: dict(CreatedOnToolsVersion="16.0")
                                      for t in (app_target, core_target, tests_target, ui_tests_target)}),
    buildConfigurationList=config_list("Project", PROJECT_SETTINGS),
    compatibilityVersion="Xcode 15.0",
    developmentRegion="en",
    hasScannedForEncodings="0",
    knownRegions=["en", "Base"],
    mainGroup=main_group,
    packageReferences=[package],
    productRefGroup=products_group,
    projectDirPath="",
    projectRoot="",
    targets=[app_target, core_target, tests_target, ui_tests_target],
)

# --- Serialisation (Xcode's old-style property list) ------------------------

SAFE = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_./")


def quote(value):
    if value and all(c in SAFE for c in value) and not value.startswith("//"):
        return value
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'


def comment(oid):
    o = objects.get(oid)
    if not o:
        return None
    isa = o["isa"]
    if isa == "PBXBuildFile":
        ref = o.get("fileRef") or o.get("productRef")
        return f"{comment(ref)} in {section_of(oid)}"
    if isa in ("PBXFileReference", "PBXGroup"):
        return o.get("name") or o.get("path")
    if isa == "PBXNativeTarget":
        return o["name"]
    if isa == "XCBuildConfiguration":
        return o["name"]
    if isa == "XCConfigurationList":
        owner = next((v for v in objects.values() if v.get("buildConfigurationList") == oid), None)
        what = "PBXProject" if owner and owner["isa"] == "PBXProject" else "PBXNativeTarget"
        name = APP if what == "PBXProject" else owner["name"]
        return f'Build configuration list for {what} "{name}"'
    if isa == "PBXProject":
        return "Project object"
    if isa == "XCRemoteSwiftPackageReference":
        return 'XCRemoteSwiftPackageReference "OpenSSL-Package"'
    if isa == "XCSwiftPackageProductDependency":
        return o["productName"]
    if isa in ("PBXSourcesBuildPhase", "PBXFrameworksBuildPhase", "PBXResourcesBuildPhase"):
        return {"PBXSourcesBuildPhase": "Sources", "PBXFrameworksBuildPhase": "Frameworks",
                "PBXResourcesBuildPhase": "Resources"}[isa]
    if isa in ("PBXContainerItemProxy", "PBXTargetDependency"):
        return isa
    return None


def section_of(build_file_id):
    for o in objects.values():
        if build_file_id in o.get("files", []):
            return comment_for_phase(o["isa"])
    return ""


def comment_for_phase(isa):
    return {"PBXSourcesBuildPhase": "Sources", "PBXFrameworksBuildPhase": "Frameworks",
            "PBXResourcesBuildPhase": "Resources"}[isa]


def value(v, indent):
    pad = "\t" * indent
    if isinstance(v, dict):
        lines = ["{"]
        for k in sorted(v, key=lambda k: (k != "isa", k)):
            lines.append(f"{pad}\t{quote(k)} = {value(v[k], indent + 1)};")
        lines.append(pad + "}")
        return "\n".join(lines)
    if isinstance(v, list):
        if not v:
            return "(\n" + pad + ")"
        return "(\n" + "".join(f"{pad}\t{value(x, indent + 1)},\n" for x in v) + pad + ")"
    if v in objects:
        c = comment(v)
        return f"{v} /* {c} */" if c else v
    return quote(v)


def write_pbxproj():
    out = ["// !$*UTF8*$!", "{", "\tarchiveVersion = 1;", "\tclasses = {", "\t};", "\tobjectVersion = 60;",
           "\tobjects = {"]
    for isa in sorted({o["isa"] for o in objects.values()}):
        out.append("")
        out.append(f"/* Begin {isa} section */")
        for oid in sorted(k for k, o in objects.items() if o["isa"] == isa):
            c = comment(oid)
            head = f"\t\t{oid} /* {c} */" if c else f"\t\t{oid}"
            out.append(f"{head} = {value(objects[oid], 2)};")
        out.append(f"/* End {isa} section */")
    out += ["\t};", f"\trootObject = {project_id} /* Project object */;", "}", ""]
    os.makedirs(PROJECT, exist_ok=True)
    with open(os.path.join(PROJECT, "project.pbxproj"), "w") as f:
        f.write("\n".join(out))


def buildable(target_id, name, product):
    return (f'            <BuildableReference\n'
            f'               BuildableIdentifier = "primary"\n'
            f'               BlueprintIdentifier = "{target_id}"\n'
            f'               BuildableName = "{product}"\n'
            f'               BlueprintName = "{name}"\n'
            f'               ReferencedContainer = "container:{APP}.xcodeproj">\n'
            f'            </BuildableReference>\n')


def write_scheme():
    app = buildable(app_target, APP, APP + ".app")
    tests = buildable(tests_target, TESTS, TESTS + ".xctest")
    # Run uses Release: this app is about speed, and nobody needs to step
    # through it to use it. Debug is a scheme edit away.
    scheme = f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme
   LastUpgradeVersion = "1600"
   version = "1.7">
   <BuildAction
      parallelizeBuildables = "YES"
      buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry
            buildForTesting = "YES"
            buildForRunning = "YES"
            buildForProfiling = "YES"
            buildForArchiving = "YES"
            buildForAnalyzing = "YES">
{app}         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <TestAction
      buildConfiguration = "Debug"
      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
      shouldUseLaunchSchemeArgsEnv = "YES">
      <Testables>
         <TestableReference
            skipped = "NO">
{tests}         </TestableReference>
      </Testables>
   </TestAction>
   <LaunchAction
      buildConfiguration = "Release"
      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
      launchStyle = "0"
      useCustomWorkingDirectory = "NO"
      ignoresPersistentStateOnLaunch = "NO"
      debugDocumentVersioning = "YES"
      debugServiceExtension = "internal"
      allowLocationSimulation = "YES">
      <BuildableProductRunnable
         runnableDebuggingMode = "0">
{app}      </BuildableProductRunnable>
   </LaunchAction>
   <ProfileAction
      buildConfiguration = "Release"
      shouldUseLaunchSchemeArgsEnv = "YES"
      savedToolIdentifier = ""
      useCustomWorkingDirectory = "NO"
      debugDocumentVersioning = "YES">
      <BuildableProductRunnable
         runnableDebuggingMode = "0">
{app}      </BuildableProductRunnable>
   </ProfileAction>
   <AnalyzeAction
      buildConfiguration = "Debug">
   </AnalyzeAction>
   <ArchiveAction
      buildConfiguration = "Release"
      revealArchiveInOrganizer = "YES">
   </ArchiveAction>
</Scheme>
'''
    path = os.path.join(PROJECT, "xcshareddata", "xcschemes", APP + ".xcscheme")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(scheme)


def write_screenshots_scheme():
    """Builds the app and runs only the UI tests that take screenshots."""
    app = buildable(app_target, APP, APP + ".app")
    ui_tests = buildable(ui_tests_target, UI_TESTS, UI_TESTS + ".xctest")
    scheme = f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme
   LastUpgradeVersion = "1600"
   version = "1.7">
   <BuildAction
      parallelizeBuildables = "YES"
      buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry
            buildForTesting = "YES"
            buildForRunning = "YES"
            buildForProfiling = "NO"
            buildForArchiving = "NO"
            buildForAnalyzing = "NO">
{app}         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <TestAction
      buildConfiguration = "Debug"
      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
      shouldUseLaunchSchemeArgsEnv = "YES">
      <Testables>
         <TestableReference
            skipped = "NO">
{ui_tests}         </TestableReference>
      </Testables>
   </TestAction>
   <LaunchAction
      buildConfiguration = "Debug"
      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
      launchStyle = "0"
      useCustomWorkingDirectory = "NO"
      ignoresPersistentStateOnLaunch = "NO"
      debugDocumentVersioning = "YES"
      debugServiceExtension = "internal"
      allowLocationSimulation = "YES">
      <BuildableProductRunnable
         runnableDebuggingMode = "0">
{app}      </BuildableProductRunnable>
   </LaunchAction>
</Scheme>
'''
    path = os.path.join(PROJECT, "xcshareddata", "xcschemes", "Screenshots.xcscheme")
    with open(path, "w") as f:
        f.write(scheme)


def write_workspace():
    path = os.path.join(PROJECT, "project.xcworkspace", "contents.xcworkspacedata")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n<Workspace\n   version = "1.0">\n'
                '   <FileRef\n      location = "self:">\n   </FileRef>\n</Workspace>\n')


if __name__ == "__main__":
    write_pbxproj()
    write_scheme()
    write_screenshots_scheme()
    write_workspace()
    print("wrote", os.path.relpath(PROJECT, ROOT))
