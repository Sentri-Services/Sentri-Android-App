#!/usr/bin/env python3
"""Prepare existing OSS update artifacts locally. This script never uploads anything."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
CONTRACT = json.loads((ROOT / 'deployment/contract.json').read_text())
bundled_jdk = Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home')
if not os.environ.get('JAVA_HOME') and bundled_jdk.is_dir():
    os.environ['JAVA_HOME'] = str(bundled_jdk)

def sdk_tool(name):
    if shutil.which(name):
        return shutil.which(name)
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        local = ROOT / 'local.properties'
        if local.exists():
            match = re.search(r'^sdk.dir=(.+)$', local.read_text(), re.M)
            if match:
                sdk = match.group(1)
    if not sdk:
        raise RuntimeError('Configure ANDROID_HOME or local.properties')
    pattern = 'cmdline-tools/*/bin/apkanalyzer' if name == 'apkanalyzer' else 'build-tools/*/apksigner'
    matches = sorted(Path(sdk).glob(pattern), key=lambda p: [int(n) for n in re.findall(r'\d+', str(p))])
    if not matches:
        raise RuntimeError(f'{name} is missing')
    return str(matches[-1])

def analyze(apk):
    analyzer = sdk_tool('apkanalyzer')
    def field(name):
        return subprocess.check_output([analyzer, 'manifest', name, str(apk)], text=True).strip()
    return {'applicationId': field('application-id'), 'versionCode': int(field('version-code')), 'versionName': field('version-name')}

def verify_signing(apk):
    output = subprocess.check_output([sdk_tool('apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)', output)
    if certificates != [CONTRACT['certificateSha256']]:
        raise RuntimeError('APK does not use the existing Sentri signing certificate')
    with zipfile.ZipFile(apk) as archive:
        enterprise = archive.read('META-INF/ENTERPRISE.CER')
    if hashlib.sha256(enterprise).hexdigest() != CONTRACT['enterpriseCertificateSha256']:
        raise RuntimeError('Enterprise certificate differs from the existing app')

def remote_version(environment):
    url = f"{CONTRACT['updateBaseUrl']}/{environment}/debug/manifest.json"
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            manifest = json.load(response)
        if manifest['applicationId'] != CONTRACT['applicationIds'][environment] or manifest['buildType'] != 'debug':
            raise RuntimeError('Remote channel identity differs')
        return int(manifest['versionCode'])
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
    # Earlier clients/releases only have last.zip, whose bytes are a signed APK.
    legacy_url = f"{CONTRACT['legacyBaseUrl']}/{environment}/debug/last.zip"
    with tempfile.TemporaryDirectory() as directory:
        apk = Path(directory) / 'previous.apk'
        try:
            with urllib.request.urlopen(legacy_url, timeout=30) as response, apk.open('wb') as target:
                shutil.copyfileobj(response, target)
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return 0
            raise
        verify_signing(apk)
        metadata = analyze(apk)
        if metadata['applicationId'] != CONTRACT['applicationIds'][environment]:
            raise RuntimeError('Legacy remote package differs')
        return metadata['versionCode']

def prepare(scope, tag_version=None, offline=False):
    environments = ['dev', 'demo', 'prod'] if scope == 'all' else [scope]
    release_root = ROOT / 'release-upload'
    if release_root.exists():
        raise RuntimeError('release-upload already exists; preserve or remove old artifacts first')
    with tempfile.TemporaryDirectory(dir=ROOT, prefix='.release-staging-') as temporary:
        staging = Path(temporary)
        for environment in environments:
            output = ROOT / f'app/build/outputs/apk/{environment}/debug'
            metadata = json.loads((output / 'output-metadata.json').read_text())
            if len(metadata['elements']) != 1:
                raise RuntimeError('Expected exactly one APK')
            apk = output / metadata['elements'][0]['outputFile']
            actual = analyze(apk)
            if actual['applicationId'] != CONTRACT['applicationIds'][environment]:
                raise RuntimeError('Local Debug cannot be published; build with -PenableApkUpdate=true')
            if scope != 'all' and tag_version != actual['versionName']:
                raise RuntimeError('Release tag and APK versionName differ')
            verify_signing(apk)
            if not offline:
                remote = remote_version(environment)
                if actual['versionCode'] <= remote:
                    raise RuntimeError(f'Refusing stale version: {environment} local={actual["versionCode"]}, remote={remote}')
            destination = staging / environment / 'debug'
            destination.mkdir(parents=True)
            filename = f'Sentri_{actual["versionName"]}_{actual["versionCode"]}_{environment}_debug.apk'
            shutil.copyfile(apk, destination / filename)
            shutil.copyfile(apk, destination / 'last.zip')
            manifest = {'schemaVersion': 1, **actual, 'buildType': 'debug', 'apkUrl': filename,
                'fileSize': apk.stat().st_size, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'forceUpdate': True}
            (destination / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
            print(f'Prepared {environment}: {actual["versionName"]} ({actual["versionCode"]})')
        staging.rename(release_root)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('scope', choices=['dev', 'demo', 'prod', 'all'])
    parser.add_argument('tag_version', nargs='?')
    parser.add_argument('--offline', action='store_true', help='Local artifact verification only; never use in deployment')
    args = parser.parse_args()
    try:
        prepare(args.scope, args.tag_version, args.offline)
    except Exception as error:
        parser.exit(1, f'Release preparation failed: {error}\n')
