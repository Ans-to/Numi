#!/usr/bin/env python3
"""Optional sandbox transport: unreferenced Git blobs, never committed to a branch/tree.

Normal users should download Actions artifacts. This permits a network-restricted owner
workspace to retrieve public APK bytes through api.github.com, without a binary commit.
No signing material, credentials, plaintext user files, or source archives are uploaded.
"""
import base64
import glob
import hashlib
import json
import os
import urllib.request

repo = os.environ['GITHUB_REPOSITORY']
files = glob.glob('app/build/outputs/apk/debug/*.apk') + glob.glob('app/build/outputs/apk/release/*.apk')
files.append(os.path.join(os.environ['ANDROID_HOME'], 'build-tools/36.0.0/lib/apksigner.jar'))
for filename in files:
    data = open(filename, 'rb').read()
    body = json.dumps({'content': base64.b64encode(data).decode('ascii'), 'encoding': 'base64'}).encode()
    request = urllib.request.Request(f'https://api.github.com/repos/{repo}/git/blobs', data=body, headers={
        'Authorization': f'Bearer {os.environ["GH_TOKEN"]}',
        'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json',
        'X-GitHub-Api-Version': '2022-11-28',
    }, method='POST')
    with urllib.request.urlopen(request) as response:
        blob = json.load(response)
    print(f'::notice title=Build delivery::{os.path.basename(filename)} blob={blob["sha"]} sha256={hashlib.sha256(data).hexdigest()} bytes={len(data)}')
