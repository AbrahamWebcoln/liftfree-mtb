#!/usr/bin/env python3
"""Run production Strava HTTP/state regressions using loopback mocks and synthetic data."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / '.build' / 'strava-tests'
DIST = ROOT / 'dist'
# Test-only dependencies; none is bundled into the Android application.
LIBRARIES = {
    'json.jar': ('org/json/json/20240303/json-20240303.jar', '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'),
    'junit.jar': ('junit/junit/4.13.2/junit-4.13.2.jar', '8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3'),
    'hamcrest.jar': ('org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar', '66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9'),
}


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    DIST.mkdir(exist_ok=True)
    result = DIST / 'strava-mock-results.json'
    result.unlink(missing_ok=True)
    jars = []
    for name, (url, digest) in LIBRARIES.items():
        path = WORK / name
        if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            with urllib.request.urlopen('https://repo.maven.apache.org/maven2/' + url, timeout=60) as response:
                path.write_bytes(response.read())
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise RuntimeError('Test dependency checksum mismatch: ' + name)
        jars.append(str(path))
    classes = WORK / 'classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir()
    sources = [ROOT / 'android/src/org/liftfree/mtb' / name for name in ('Core.java', 'RideStore.java', 'StravaClient.java')]
    sources += [ROOT / 'tests/CoreTest.java', *sorted((ROOT / 'tests/strava').glob('*.java'))]
    compiler = [shutil.which('javac')] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    subprocess.run([*compiler, '-cp', os.pathsep.join(jars), '-d', str(classes), *map(str, sources)], check=True, timeout=90)
    subprocess.run(['java', '-cp', os.pathsep.join([str(classes), *jars]), 'org.junit.runner.JUnitCore', 'org.liftfree.mtb.StravaClientTest'], check=True, timeout=120)
    result.write_text(json.dumps({
        'passed': True, 'transport': 'loopback mocked HTTP',
        'production_client': 'StravaClient.java',
        'real_strava_upload_tested': False, 'hardware_tested': False,
    }, indent=2) + '\n')


if __name__ == '__main__':
    main()
