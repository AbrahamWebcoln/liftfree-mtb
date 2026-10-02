#!/usr/bin/env python3
"""Android runtime smoke test. No Strava credentials, uploads, or physical-device claims."""
from pathlib import Path
import os, sys, subprocess, time, re, getpass, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]; DIST=ROOT/'dist'
SDK=Path(os.environ.get('ANDROID_HOME','/usr/local/lib/android/sdk'))
TOOLS=SDK/'cmdline-tools/latest/bin'; ADB=SDK/'platform-tools/adb'
PKG='org.liftfree.mtb'

def run(args,timeout=60,check=True,input=None):
    r=subprocess.run([str(x) for x in args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,input=input,timeout=timeout)
    print(r.stdout,flush=True)
    if check and r.returncode:raise RuntimeError('Command failed: '+' '.join(map(str,args)))
    return r.stdout

def adb(*args,timeout=45,check=True):return run([ADB,'-s','emulator-5554',*args],timeout=timeout,check=check)

if not Path('/dev/kvm').exists():
    print('SKIPPED: runner has no KVM. No Android runtime test is claimed.');sys.exit(0)
if not os.access('/dev/kvm',os.R_OK|os.W_OK):
    run(['sudo','-n','setfacl','-m','u:'+getpass.getuser()+':rw','/dev/kvm'],check=False)
if not os.access('/dev/kvm',os.R_OK|os.W_OK):
    print('SKIPPED: runner cannot access KVM. No Android runtime test is claimed.');sys.exit(0)
image='system-images;android-35;google_apis;x86_64'
run([TOOLS/'sdkmanager','emulator',image],timeout=240)
run([TOOLS/'avdmanager','create','avd','--force','-n','LiftFreeTest','-k',image],timeout=90,input='no\n')
config=Path.home()/'.android/avd/LiftFreeTest.avd/config.ini'
values={}
for line in config.read_text().splitlines():
    if '=' in line:
        k,v=line.split('=',1);values[k]=v
values.update({'hw.lcd.width':'1080','hw.lcd.height':'1920','hw.lcd.density':'420','hw.keyboard':'yes','hw.ramSize':'2048'})
config.write_text(''.join(k+'='+v+'\n' for k,v in values.items()))
log=(DIST/'android-emulator.log').open('w')
emulator=subprocess.Popen([str(SDK/'emulator/emulator'),'-avd','LiftFreeTest','-port','5554','-no-window','-no-audio','-no-boot-anim','-no-snapshot','-gpu','swiftshader_indirect','-camera-back','none','-camera-front','none','-memory','2048','-cores','2'],stdout=log,stderr=subprocess.STDOUT)
try:
    deadline=time.monotonic()+180
    while time.monotonic()<deadline:
        if emulator.poll() is not None:raise RuntimeError('Android emulator stopped before boot')
        boot=adb('shell','getprop','sys.boot_completed',timeout=8,check=False)
        if boot.strip()=='1':break
        time.sleep(3)
    else:raise RuntimeError('Android boot timed out')
    adb('shell','input','keyevent','82')
    for setting in ('window_animation_scale','transition_animation_scale','animator_duration_scale'):
        adb('shell','settings','put','global',setting,'0')
    adb('install','-r',str(DIST/'LiftFree-Android.apk'),timeout=90)
    for permission in ('android.permission.BLUETOOTH_CONNECT','android.permission.POST_NOTIFICATIONS'):
        adb('shell','pm','grant',PKG,permission)
    adb('logcat','-b','crash','-c')
    adb('shell','am','start','-W','-n',PKG+'/.MainActivity')
    time.sleep(2)
    adb('shell','uiautomator','dump','/sdcard/liftfree-window.xml')
    xml=adb('shell','cat','/sdcard/liftfree-window.xml')
    (DIST/'android-initial-ui.xml').write_text(xml)
    node=next((n for n in ET.fromstring(xml).iter('node') if n.attrib.get('text')=='Start phone companion'),None)
    assert node is not None, 'Main screen did not show Start phone companion'
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.attrib['bounds']))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    time.sleep(4)
    assert adb('shell','pidof',PKG,check=False).strip(), 'Application is no longer running'
    crashes=adb('logcat','-b','crash','-d');(DIST/'android-crash-log.txt').write_text(crashes)
    assert 'Process: '+PKG not in crashes, 'App crashed: '+crashes
    services=adb('shell','dumpsys','activity','services',PKG)
    (DIST/'android-service-state.txt').write_text(services)
    assert 'BridgeService' in services, 'The foreground bridge service did not start'
    screenshot=subprocess.check_output([str(ADB),'-s','emulator-5554','exec-out','screencap','-p'],timeout=20)
    (DIST/'android-smoke-screen.png').write_bytes(screenshot)
    (DIST/'android-smoke-PASSED').write_text('APK installed; main screen displayed; Start button launched foreground service; process alive; no app crash. Garmin Connect, Bluetooth watch transport, and Strava upload NOT tested.\n')
    print('PASSED: Android install, launch and foreground service smoke test. Real Garmin connection and Strava upload remain untested.')
finally:
    try:adb('emu','kill',check=False,timeout=10)
    except Exception:pass
    emulator.terminate();log.close()
