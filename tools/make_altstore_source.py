#!/usr/bin/env python3
"""Writes the AltStore / SideStore source for the iPad and iPhone app.

CI publishes it with every build, next to the IPA in the ios-latest
pre-release. AltStore and SideStore read it to offer updates, and the app
reads it to say when a newer version is out (ios/.../Updates/ReleaseFeed.swift).

    python3 tools/make_altstore_source.py --version 1.0.12 --build 12 \\
        --ipa-url https://.../MangoCast-1.0.12.ipa --ipa-size 2649914 \\
        --icon-url https://.../icon-1024.png --notes "What changed" > altstore-source.json
"""
import argparse
import datetime
import json
import plistlib
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INFO_PLIST = os.path.join(ROOT, "ios", "MangoScreenCast", "Info.plist")
BUNDLE_ID = "com.mangoscreencast.ios"
MIN_OS = "16.0"


def source(version, build, ipa_url, ipa_size, icon_url, notes, website, date=None):
    with open(INFO_PLIST, "rb") as f:
        info = plistlib.load(f)
    # AltStore lists the privacy prompts an app shows before installing it.
    privacy = {k: v for k, v in info.items() if k.startswith("NS") and k.endswith("UsageDescription")}
    date = date or datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    return {
        "name": "Mango Screen Cast",
        "identifier": BUNDLE_ID + ".source",
        "subtitle": "AirPlay receiver for iPad and iPhone",
        "description": "Mango Cast puts your Mac's screen on your iPad: mirror it, or use the iPad as a second display.",
        "iconURL": icon_url,
        "website": website,
        "tintColor": "FFA41B",
        "nsfw": False,
        "apps": [{
            "name": "Mango Cast",
            "bundleIdentifier": BUNDLE_ID,
            "developerName": "Mango Screen Cast",
            "subtitle": "Your Mac's screen on your iPad",
            "localizedDescription": (
                "An AirPlay receiver: your Mac finds this iPad or iPhone in Screen Mirroring. Mirror the Mac, or "
                "choose Use As Separate Display to use the iPad as a second display at its full resolution. "
                "Keep Mango Cast open while you use it."),
            "iconURL": icon_url,
            "tintColor": "FFA41B",
            "category": "utilities",
            "appPermissions": {"entitlements": [], "privacy": privacy},
            "versions": [{
                "version": version,
                "buildVersion": str(build),
                "date": date.strftime("%Y-%m-%dT%H:%M:%SZ"),
                "localizedDescription": notes,
                "downloadURL": ipa_url,
                "size": int(ipa_size),
                "minOSVersion": MIN_OS,
            }],
        }],
        "news": [],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--version", required=True)
    parser.add_argument("--build", required=True)
    parser.add_argument("--ipa-url", required=True)
    parser.add_argument("--ipa-size", required=True)
    parser.add_argument("--icon-url", required=True)
    parser.add_argument("--notes", default="")
    parser.add_argument("--website", default="https://github.com/xgamer791/Mango-Screen-Cast")
    args = parser.parse_args()
    data = source(args.version, args.build, args.ipa_url, args.ipa_size, args.icon_url, args.notes, args.website)
    print(json.dumps(data, indent=2))


if __name__ == "__main__":
    main()
