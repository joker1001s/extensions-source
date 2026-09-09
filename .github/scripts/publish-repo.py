import gzip
import hashlib
import html
import json
import math
import os
import sys
from pathlib import Path

import index_pb2
from google.protobuf import json_format


ARTIFACTS_DIR = Path.home() / "apk-artifacts"

# main 分支源码目录
SOURCE_DIR = Path(os.environ["SOURCE_DIR"])

# repo 分支工作目录
REPO_DIR = Path(os.environ["REPO_DIR"])

REPO_NAME = os.environ["EXTENSIONS_REPO"]
SOURCE_REPO = os.environ["SOURCE_REPO"]
SIGNING_KEY = os.environ["SIGNING_KEY_FINGERPRINT"]

ICON_BASE_URL = (
    f"https://cdn.jsdelivr.net/gh/{SOURCE_REPO}@main"
)

RELEASE_BASE_URL = (
    f"https://github.com/{REPO_NAME}/releases/download"
)

CURRENT_SHA = os.environ["GITHUB_SHA"]
CURRENT_SHA_SHORT = CURRENT_SHA[:7]

ICON_FILE = "res/mipmap-xhdpi/ic_launcher.png"


def get_icon_url(module: str, theme: str | None) -> str:
    module_icon = f"src/{module.replace('.', '/')}/{ICON_FILE}"

    if (SOURCE_DIR / module_icon).exists():
        return f"{ICON_BASE_URL}/{module_icon}"

    if theme:
        theme_icon = f"lib-multisrc/{theme}/{ICON_FILE}"

        if (SOURCE_DIR / theme_icon).exists():
            return f"{ICON_BASE_URL}/{theme_icon}"

    return f"{ICON_BASE_URL}/core/src/main/{ICON_FILE}"


def load_existing_index():
    index_file = REPO_DIR / "index.json"

    if not index_file.exists():
        return index_pb2.Index()

    if index_file.stat().st_size == 0:
        return index_pb2.Index()

    with index_file.open(encoding="utf-8") as f:
        return json_format.Parse(
            f.read(),
            index_pb2.Index(),
        )


def load_release_assets():
    path = REPO_DIR / "release-assets.json"

    if not path.exists():
        return {}

    with path.open(encoding="utf-8") as f:
        return json.load(f)


def find_build_artifacts():
    results = []

    for info_file in ARTIFACTS_DIR.glob(
        "**/keiyoushi-source-info.json"
    ):
        with info_file.open(encoding="utf-8") as f:
            info = json.load(f)

        package_name = info["packageName"]

        apk_dir = (
            info_file.parent
            / "outputs/apk/release"
        )

        jar_dir = (
            info_file.parent
            / "outputs/jar/release"
        )

        apk = next(apk_dir.glob("*.apk"), None)

        if apk is None:
            raise FileNotFoundError(
                f"{package_name}: release APK not found"
            )

        jar = next(jar_dir.glob("*.jar"), None)

        if jar is None:
            raise FileNotFoundError(
                f"{package_name}: release JAR not found"
            )

        results.append(
            (info, apk, jar)
        )

    if not results:
        raise RuntimeError(
            "No keiyoushi-source-info.json found"
        )

    return results


def main():
    remote_proto = load_existing_index()

    remote_extensions = {
        ext.packageName: ext
        for ext in remote_proto.extensionList.extensions
    }

    release_assets = load_release_assets()

    updated_release_assets = dict(
        release_assets
    )

    new_extensions = []

    for info, apk, jar in find_build_artifacts():

        package_name = info["packageName"]

        assets = {
            "apk": {
                "name": apk.name,
                "sha256": hashlib.sha256(
                    apk.read_bytes()
                ).hexdigest(),
            },
            "jar": {
                "name": jar.name,
                "sha256": hashlib.sha256(
                    jar.read_bytes()
                ).hexdigest(),
            },
        }

        old_assets = release_assets.get(
            package_name,
            {},
        )

        apk_changed = (
            package_name not in remote_extensions
            or old_assets.get("apk") != assets["apk"]
        )

        jar_changed = (
            package_name not in remote_extensions
            or old_assets.get("jar") != assets["jar"]
        )

        updated_release_assets[
            package_name
        ] = assets

        extension = index_pb2.Extension(
            name=info["name"],
            packageName=package_name,

            resources=index_pb2.Resources(
                iconUrl=get_icon_url(
                    info["module"],
                    info.get("theme"),
                ),
            ),

            extensionLib=info["extensionLib"],
            versionCode=info["versionCode"],
            versionName=info["versionName"],
            contentWarning=info["contentWarning"],

            sources=[
                index_pb2.Source(
                    id=int(source["id"]),
                    name=source["name"],
                    language=source["lang"],
                    homeUrl=source["baseUrl"],
                    mirrorUrls=source.get(
                        "mirrorUrls",
                        [],
                    ),
                )
                for source in info["sources"]
            ],
        )

        new_extensions.append(
            (
                extension,
                apk,
                jar,
                apk_changed,
                jar_changed,
            )
        )

    new_extensions.sort(
        key=lambda item: item[0].packageName
    )

    changed_extensions = [
        item
        for item in new_extensions
        if item[3] or item[4]
    ]

    total_changed = len(changed_extensions)

    ASSET_LIMIT = 495

    release_count = (
        math.ceil(
            total_changed / ASSET_LIMIT
        )
        if total_changed
        else 0
    )

    ext_per_release = (
        math.ceil(
            total_changed / release_count
        )
        if release_count
        else 0
    )

    def get_release_tag(batch_index):
        if release_count > 1:
            return f"{CURRENT_SHA_SHORT}-{batch_index}"

        return CURRENT_SHA_SHORT

    changed_index = 0

    for (
        extension,
        apk,
        jar,
        apk_changed,
        jar_changed,
    ) in new_extensions:

        package_name = extension.packageName

        if apk_changed or jar_changed:

            tag = get_release_tag(
                changed_index // ext_per_release
            )

            old = remote_extensions.get(
                package_name
            )

            if apk_changed:
                extension.resources.apkUrl = (
                    f"{RELEASE_BASE_URL}/"
                    f"{tag}/"
                    f"{apk.name}"
                )
            elif old:
                extension.resources.apkUrl = (
                    old.resources.apkUrl
                )

            if jar_changed:
                extension.resources.jarUrl = (
                    f"{RELEASE_BASE_URL}/"
                    f"{tag}/"
                    f"{jar.name}"
                )
            elif old:
                extension.resources.jarUrl = (
                    old.resources.jarUrl
                )

            changed_index += 1

        else:
            old = remote_extensions[
                package_name
            ]

            extension.resources.apkUrl = (
                old.resources.apkUrl
            )

            extension.resources.jarUrl = (
                old.resources.jarUrl
            )

    # 只保留我们自己仓库已经发布的扩展，
    # 再用当前新构建版本替换。
    final_extensions = list(
        remote_proto.extensionList.extensions
    )

    for extension, *_ in new_extensions:
        final_extensions = [
            old
            for old in final_extensions
            if old.packageName
            != extension.packageName
        ]

        final_extensions.append(
            extension
        )

    final_extensions.sort(
        key=lambda ext: ext.packageName
    )

    index = index_pb2.Index(
        name=os.environ.get(
            "REPO_DISPLAY_NAME",
            "My Extensions",
        ),

        badgeLabel="CUSTOM",

        signingKey=SIGNING_KEY,

        contact=index_pb2.Contact(
            website=(
                f"https://github.com/"
                f"{REPO_NAME}"
            ),
        ),

        extensionList=index_pb2.ExtensionList(
            extensions=final_extensions
        ),
    )

    REPO_DIR.mkdir(
        parents=True,
        exist_ok=True,
    )

    # index.json
    with (
        REPO_DIR / "index.json"
    ).open(
        "w",
        encoding="utf-8",
    ) as f:
        f.write(
            json_format.MessageToJson(
                index,
                always_print_fields_with_no_presence=False,
                preserving_proto_field_name=True,
            )
        )

    # index.min.json
    with (
        REPO_DIR / "index.min.json"
    ).open(
        "w",
        encoding="utf-8",
    ) as f:
        json.dump(
            json.loads(
                json_format.MessageToJson(
                    index,
                    always_print_fields_with_no_presence=False,
                    preserving_proto_field_name=True,
                )
            ),
            f,
            separators=(",", ":"),
        )

    # index.pb
    with (
        REPO_DIR / "index.pb"
    ).open("wb") as f:
        f.write(
            gzip.compress(
                index.SerializeToString(
                    deterministic=True
                ),
                mtime=0,
            )
        )

    # release-assets.json
    with (
        REPO_DIR / "release-assets.json"
    ).open(
        "w",
        encoding="utf-8",
    ) as f:
        json.dump(
            updated_release_assets,
            f,
            indent=2,
            sort_keys=True,
        )
        f.write("\n")

    # repo.json
    repo_json = {
        "index_v2": (
            f"https://github.com/"
            f"{REPO_NAME}/raw/repo/index.pb"
        ),
        "meta": {
            "name": os.environ.get(
                "REPO_DISPLAY_NAME",
                "My Extensions",
            ),
            "website": (
                f"https://github.com/"
                f"{REPO_NAME}"
            ),
            "signingKeyFingerprint": SIGNING_KEY,
        },
    }

    with (
        REPO_DIR / "repo.json"
    ).open(
        "w",
        encoding="utf-8",
    ) as f:
        json.dump(
            repo_json,
            f,
            indent=2,
        )
        f.write("\n")

    # index.html
    with (
        REPO_DIR / "index.html"
    ).open(
        "w",
        encoding="utf-8",
    ) as f:

        f.write(
            "<!DOCTYPE html>\n"
            "<html>\n"
            "<head>\n"
            '<meta charset="UTF-8">\n'
            "<title>Extensions</title>\n"
            "</head>\n"
            "<body>\n"
            "<pre>\n"
        )

        for extension in final_extensions:

            apk_url = html.escape(
                extension.resources.apkUrl
            )

            name = html.escape(
                f"Tachiyomi: {extension.name}"
            )

            f.write(
                f'<a href="{apk_url}">'
                f"{name}</a>\n"
            )

        f.write(
            "</pre>\n"
            "</body>\n"
            "</html>\n"
        )

    print(
        f"Extensions in index: "
        f"{len(final_extensions)}"
    )

    print(
        f"Changed extensions: "
        f"{total_changed}"
    )

    # 没有新的 APK/JAR，不需要创建 Release
    if not changed_extensions:
        return

    # GitHub CLI 必须使用 GITHUB_TOKEN
    for batch_index in range(
        release_count
    ):

        start = (
            batch_index
            * ext_per_release
        )

        end = (
            start
            + ext_per_release
        )

        batch = changed_extensions[
            start:end
        ]

        tag = get_release_tag(
            batch_index
        )

        release_exists = os.system(
            f'gh release view "{tag}" '
            f'--repo "{REPO_NAME}" '
            f'>/dev/null 2>&1'
        ) == 0

        if not release_exists:

            os.system(
                f'gh release create '
                f'"{tag}" '
                f'--repo "{REPO_NAME}" '
                f'--draft '
                f'--title "Repository Update {tag}" '
                f'--notes "Automated Roumanwu update"'
            )

        files = []

        for (
            _,
            apk,
            jar,
            apk_changed,
            jar_changed,
        ) in batch:

            if apk_changed:
                files.append(apk)

            if jar_changed:
                files.append(jar)

        if files:

            command = [
                "gh",
                "release",
                "upload",
                tag,
                *[
                    str(file)
                    for file in files
                ],
                "--repo",
                REPO_NAME,
                "--clobber",
            ]

            os.system(
                " ".join(
                    f'"{part}"'
                    for part in command
                )
            )

        os.system(
            f'gh release edit '
            f'"{tag}" '
            f'--repo "{REPO_NAME}" '
            f'--draft=false'
        )


if __name__ == "__main__":
    main()
