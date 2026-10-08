#!/usr/bin/env python3
"""Same release tags/version increments as the old app; publishing requires explicit --publish."""
import argparse
from datetime import datetime
from pathlib import Path
import re
import subprocess
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parent.parent

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True).strip()

def release(scope, publish):
    path = ROOT / 'versions.properties'
    text = path.read_text()
    environments = ['dev', 'demo', 'prod'] if scope == 'all' else [scope]
    descriptions = []
    for environment in environments:
        code = int(re.search(rf'^{environment}\.versionCode=(\d+)$', text, re.M)[1]) + 1
        version = re.search(rf'^{environment}\.versionName=(\d+\.\d+\.\d+)$', text, re.M)[1]
        parts = list(map(int, version.split('.'))); parts[2] += 1
        version = '.'.join(map(str, parts))
        text = re.sub(rf'^{environment}\.versionCode=.+$', f'{environment}.versionCode={code}', text, flags=re.M)
        text = re.sub(rf'^{environment}\.versionName=.+$', f'{environment}.versionName={version}', text, flags=re.M)
        descriptions.append(f'{environment} {version} ({code})')
    tag = f'android-{scope}-v{version}' if scope != 'all' else 'android-all-r' + datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y%m%d%H%M%S')
    print('Release:', ', '.join(descriptions), 'tag:', tag)
    if not publish:
        print('Dry run: no files, commits, tags or remote state changed')
        return
    branch = git('branch', '--show-current')
    if branch not in ['main', 'master'] or git('status', '--porcelain'):
        raise RuntimeError('Release requires a clean main/master branch')
    git('fetch', '--tags', 'origin', branch)
    if git('rev-parse', 'HEAD') != git('rev-parse', f'origin/{branch}'):
        raise RuntimeError('Local branch must exactly match origin')
    existing = subprocess.run(['git', 'show-ref', '--verify', '--quiet', f'refs/tags/{tag}'], cwd=ROOT)
    if existing.returncode == 0:
        raise RuntimeError('Release tag already exists')
    path.write_text(text)
    git('add', 'versions.properties')
    git('commit', '-m', 'chore(android): release ' + ', '.join(descriptions))
    git('tag', '-a', tag, '-m', 'Release Android ' + ', '.join(descriptions))
    try:
        git('push', '--atomic', 'origin', branch, tag)
    except subprocess.CalledProcessError:
        raise RuntimeError('Push failed; local version commit and tag are retained for inspection') from None

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('scope', choices=['dev', 'demo', 'prod', 'all'])
    parser.add_argument('--publish', action='store_true')
    args = parser.parse_args()
    release(args.scope, args.publish)
