#!/usr/bin/env python3
"""Prepare and build LiftFree. No user GPS data, credentials or private keys in artifacts."""
from pathlib import Path
import os, sys, shutil, subprocess, struct, zlib, zipfile, urllib.request, json, hashlib
ROOT=Path(__file__).resolve().parents[1]
DIST=ROOT/'dist'; DIST.mkdir(exist_ok=True)
WORK=ROOT/'.build'; WORK.mkdir(exist_ok=True)
IMAGE='ghcr.io/matco/connectiq-tester:latest'

def write(p,s):
    p=ROOT/p; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(s)

def run(args,log,timeout=600,cwd=None,check=True):
    print('+', ' '.join(map(str,args)),flush=True)
    r=subprocess.run(list(map(str,args)),cwd=cwd or ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=timeout)
    with (DIST/log).open('a') as f:f.write(r.stdout)
    print(r.stdout,flush=True)
    if check and r.returncode:raise RuntimeError(f'{log}: command exited {r.returncode}')
    return r

def prepare():
    manifest='''<?xml version="1.0"?>
<iq:manifest xmlns:iq="http://www.garmin.com/xml/connectiq" version="3">
 <iq:application id="6eacb38288464b09a6cd268018d8f007" type="watch-app" name="@Strings.AppName" entry="LiftFreeApp" launcherIcon="@Drawables.LauncherIcon" minApiLevel="3.2.0">
 <iq:products><iq:product id="fenix6"/><iq:product id="fenix6pro"/></iq:products>
 <iq:permissions><iq:uses-permission id="Fit"/><iq:uses-permission id="FitContributor"/><iq:uses-permission id="Positioning"/><iq:uses-permission id="Sensor"/><iq:uses-permission id="Communications"/></iq:permissions>
 <iq:languages><iq:language>eng</iq:language></iq:languages><iq:barrels/>
 </iq:application>
</iq:manifest>
'''
    write('watch/manifest.xml',manifest)
    write('watch/monkey.jungle','project.manifest = manifest.xml\nbase.sourcePath = source\nbase.resourcePath = resources\nfenix6pro.resourcePath = $(base.resourcePath);resources-pro\n')
    write('watch/resources/strings/strings.xml','<strings><string id="AppName">LiftFree MTB</string><string id="Product">3289</string></strings>')
    write('watch/resources-pro/strings/strings.xml','<strings><string id="Product">3290</string></strings>')
    write('watch/resources/drawables/drawables.xml','<drawables><bitmap id="LauncherIcon" filename="launcher.png"/></drawables>')
    w=h=40; raw=bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            on=(9<=y<=31 and abs(x-20)<=int((y-9)*0.72) and (y>=27 or abs(x-20)>=int((y-12)*0.50)))
            raw.extend((255,255,255,255) if on else (0,0,0,0))
    def chunk(k,b):return struct.pack('>I',len(b))+k+b+struct.pack('>I',zlib.crc32(k+b)&0xffffffff)
    png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b'')
    (ROOT/'watch/resources/drawables/launcher.png').write_bytes(png)
    (ROOT/'watch/bin').mkdir(exist_ok=True)
    if (ROOT/'android/src').exists():
        write('android/settings.gradle',"pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositories { google(); mavenCentral() } }\nrootProject.name='LiftFree'\ninclude ':app'\n")
        write('android/build.gradle',"plugins { id 'com.android.application' version '8.9.2' apply false }\n")
        write('android/gradle.properties','org.gradle.jvmargs=-Xmx2048m\nandroid.useAndroidX=true\n')
        write('android/app/build.gradle',"plugins { id 'com.android.application' }\nandroid { namespace 'org.liftfree.mtb'; compileSdk 35\n defaultConfig { applicationId 'org.liftfree.mtb'; minSdk 30; targetSdk 35; versionCode 3; versionName '0.3.0-test' }\n compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }\n sourceSets { main { java.srcDirs=['../src']; manifest.srcFile('../AndroidManifest.xml') } }\n}\ndependencies { implementation 'com.garmin.connectiq:ciq-companion-app-sdk:2.4.0@aar' }\n")

prepare()
if '--prepare' in sys.argv:sys.exit(0)
errors=[]
try:
    run(['docker','pull',IMAGE],'watch-build.log',900)
    run(['docker','image','inspect','--format','{{index .RepoDigests 0}}',IMAGE],'watch-build.log')
    run(['openssl','genrsa','-out',WORK/'key.pem','4096'],'watch-build.log')
    run(['openssl','pkcs8','-topk8','-inform','PEM','-outform','DER','-in',WORK/'key.pem','-out',WORK/'key.der','-nocrypt'],'watch-build.log')
    for device in ('fenix6','fenix6pro'):
        run(['docker','run','--rm','-v',f'{ROOT}:/app','-w','/app/watch','--entrypoint','/bin/bash',IMAGE,'-lc',f'/connectiq/bin/monkeyc -f monkey.jungle -d {device} -o /app/dist/LiftFree-{device}.prg -y /app/.build/key.der -l 0 -w'],f'watch-{device}.log')
    script='''set -eu
/connectiq/bin/monkeyc -f monkey.jungle -d fenix6 -o bin/tests.prg -y /app/.build/key.der -t -l 0
export DISPLAY=:99
Xvfb :99 -screen 0 1280x1024x24 >/tmp/xvfb.log 2>&1 &
/connectiq/bin/simulator >/tmp/simulator.log 2>&1 &
sleep 5
set +e
timeout 120 /connectiq/bin/monkeydo bin/tests.prg fenix6 -t >/app/dist/garmin-tests.log 2>&1
cat /app/dist/garmin-tests.log
grep -q '^PASSED' /app/dist/garmin-tests.log
'''
    run(['docker','run','--rm','-v',f'{ROOT}:/app','-w','/app/watch','--entrypoint','/bin/bash',IMAGE,'-lc',script],'watch-tests-runner.log',200)
except Exception as e:errors.append('Watch: '+str(e))
if (ROOT/'android/src/org/liftfree/mtb/Core.java').exists():
    try:
        out=WORK/'classes';out.mkdir(exist_ok=True)
        run(['javac','-d',out,ROOT/'android/src/org/liftfree/mtb/Core.java',ROOT/'tests/CoreTest.java'],'core-tests.log')
        run(['java','-cp',out,'org.liftfree.mtb.CoreTest',str(DIST/'SYNTHETIC_TEST_DO_NOT_UPLOAD.fit')],'core-tests.log')
        run([sys.executable,'-m','pip','install','--disable-pip-version-check','garmin-fit-sdk==21.217.0'],'fit-sdk-test.log',180)
        run([sys.executable,ROOT/'tests/validate_fit.py',DIST/'SYNTHETIC_TEST_DO_NOT_UPLOAD.fit'],'fit-sdk-test.log')
        sdk=Path(os.environ.get('ANDROID_HOME','/usr/local/lib/android/sdk'))
        manager=shutil.which('sdkmanager') or str(sdk/'cmdline-tools/latest/bin/sdkmanager')
        run([manager,'platforms;android-35','build-tools;35.0.0'],'android-build.log',300)
        gradlezip=WORK/'gradle.zip'
        urllib.request.urlretrieve('https://services.gradle.org/distributions/gradle-8.11.1-bin.zip',gradlezip)
        with zipfile.ZipFile(gradlezip) as z:z.extractall(WORK)
        gradle=WORK/'gradle-8.11.1/bin/gradle';gradle.chmod(0o755)
        run([gradle,'--no-daemon','--console=plain','assembleDebug'],'android-build.log',600,ROOT/'android')
        shutil.copy2(ROOT/'android/app/build/outputs/apk/debug/app-debug.apk',DIST/'LiftFree-Android.apk')
        signer=sdk/'build-tools/35.0.0/apksigner'
        run([signer,'verify','--verbose',DIST/'LiftFree-Android.apk'],'android-verification.log')
        if (ROOT/'ci/android_smoke.py').exists():
            run([sys.executable,ROOT/'ci/android_smoke.py'],'android-smoke.log',600)
    except Exception as e:errors.append('Android: '+str(e))
if (ROOT/'README.md').exists():shutil.copy2(ROOT/'README.md',DIST/'README.md')
with zipfile.ZipFile(DIST/'LiftFree-source.zip','w',zipfile.ZIP_DEFLATED) as z:
    for folder in ('watch/source','watch/resources','watch/resources-pro','android/src','tests','ci'):
        for p in (ROOT/folder).rglob('*'):
            if p.is_file() and p.suffix in ('.mc','.java','.xml','.png','.py'):z.write(p,p.relative_to(ROOT))
    for file in ('watch/manifest.xml','watch/monkey.jungle','android/AndroidManifest.xml','android/build.gradle','android/settings.gradle','android/gradle.properties','android/app/build.gradle','README.md'):
        p=ROOT/file
        if p.is_file():z.write(p,file)
summary={'commit':os.environ.get('GITHUB_SHA'),'errors':errors,'android_emulator_smoke_passed':(DIST/'android-smoke-PASSED').exists(),'hardware_tested':False,'strava_upload_tested':False,'outputs':[p.name for p in DIST.iterdir() if p.suffix in ('.prg','.apk')]}
(DIST/'BUILD-STATUS.json').write_text(json.dumps(summary,indent=2))
(DIST/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in sorted(DIST.iterdir()) if p.is_file() and p.name!='SHA256SUMS.txt'))
if errors:print('\n'.join(errors));sys.exit(1)
