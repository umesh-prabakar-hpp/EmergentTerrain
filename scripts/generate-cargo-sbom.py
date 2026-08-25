#!/usr/bin/env python3
"""Convert `cargo metadata --format-version 1` stdin into a path-free CycloneDX BOM."""

import datetime as dt
import json
import sys


metadata = json.load(sys.stdin)
packages = metadata["packages"]
ref_by_id = {
    package["id"]: f"pkg:cargo/{package['name']}@{package['version']}"
    for package in packages
}


def component(package):
    result = {
        "type": "library",
        "name": package["name"],
        "version": package["version"],
        "purl": ref_by_id[package["id"]],
        "bom-ref": ref_by_id[package["id"]],
    }
    if package.get("license"):
        result["licenses"] = [{"expression": package["license"]}]
    references = []
    for kind, url in (("website", package.get("homepage")), ("vcs", package.get("repository"))):
        if url:
            references.append({"type": kind, "url": url})
    if references:
        result["externalReferences"] = references
    return result


root_id = metadata["resolve"]["root"]
root = next(package for package in packages if package["id"] == root_id)
root_component = {
    "type": "application",
    "name": root["name"],
    "version": root["version"],
    "purl": ref_by_id[root_id],
}
if root.get("license"):
    root_component["licenses"] = [{"expression": root["license"]}]
dependencies = []
for node in metadata["resolve"]["nodes"]:
    ref = ref_by_id[node["id"]]
    dependencies.append({
        "ref": ref,
        "dependsOn": sorted(ref_by_id[dependency["pkg"]] for dependency in node["deps"]),
    })

bom = {
    "bomFormat": "CycloneDX",
    "specVersion": "1.5",
    "version": 1,
    "metadata": {
        "timestamp": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat(),
        "component": root_component,
        "tools": [{"vendor": "Cargo", "name": "cargo metadata", "version": 1}],
    },
    "components": sorted((component(package) for package in packages), key=lambda item: item["bom-ref"]),
    "dependencies": sorted(dependencies, key=lambda item: item["ref"]),
}
json.dump(bom, sys.stdout, indent=2)
sys.stdout.write("\n")
