import json, os, sys, re, io, urllib.request, urllib.parse
from PIL import Image, ImageDraw, ImageFont

cfg = json.load(open(sys.argv[1], encoding="utf-8"))
RES = "app/src/main/res"
ASSETS = "app/src/main/assets/appforge"
os.makedirs(ASSETS, exist_ok=True)

def esc(s):
    return (s or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'").replace('"', '\\"')

def hexcolor(v, d):
    return v if isinstance(v, str) and re.match(r"^#[0-9a-fA-F]{6}$", v) else d

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "AppForge-Builder"})
    return urllib.request.urlopen(req, timeout=40).read()

def fetch_image(url):
    if not url:
        return None
    try:
        return Image.open(io.BytesIO(fetch(url))).convert("RGBA")
    except Exception as e:
        print("::warning::Could not load image " + url + ": " + str(e))
        return None

b = cfg.get("branding", {})
name = cfg.get("name") or "My App"
pkg = cfg.get("packageName") or "com.appforge.app"
theme = hexcolor(b.get("themeColor"), "#4352EB")
adaptive_bg = hexcolor(b.get("adaptiveBg"), "#FFFFFF")
print("Preparing " + name + " (" + pkg + ")")

with open("appforge.properties", "w") as f:
    f.write("applicationId=%s\nversionCode=%d\nversionName=%s\n" % (pkg, int(cfg.get("versionCode") or 1), cfg.get("versionName") or "1.0.0"))

os.makedirs(os.path.join(RES, "values"), exist_ok=True)
open(os.path.join(RES, "values/strings.xml"), "w", encoding="utf-8").write('<?xml version="1.0" encoding="utf-8"?>\n<resources><string name="app_name">%s</string></resources>\n' % esc(name))
open(os.path.join(RES, "values/colors.xml"), "w").write('<?xml version="1.0" encoding="utf-8"?>\n<resources><color name="theme">%s</color><color name="ic_launcher_background">%s</color></resources>\n' % (theme, adaptive_bg))

# ---- Icons (all densities, round + adaptive) ----
icon = fetch_image(b.get("iconUrl"))
if icon is None:
    icon = Image.new("RGBA", (512, 512), theme)
    d = ImageDraw.Draw(icon)
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 280)
    except Exception:
        font = ImageFont.load_default(size=280)
    d.text((256, 256), name[:1].upper(), fill="white", font=font, anchor="mm")
icon = icon.resize((512, 512), Image.LANCZOS)
sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
fg_sizes = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
flat = Image.new("RGBA", (512, 512), adaptive_bg)
flat.alpha_composite(icon)
for dens, s in sizes.items():
    folder = os.path.join(RES, "mipmap-" + dens)
    os.makedirs(folder, exist_ok=True)
    flat.resize((s, s), Image.LANCZOS).save(os.path.join(folder, "ic_launcher.png"))
    small = flat.resize((s, s), Image.LANCZOS)
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, s, s), fill=255)
    rnd = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    rnd.paste(small, (0, 0), mask)
    rnd.save(os.path.join(folder, "ic_launcher_round.png"))
    fs = fg_sizes[dens]
    inner = int(fs * 0.62)
    fg = Image.new("RGBA", (fs, fs), (0, 0, 0, 0))
    fg.alpha_composite(icon.resize((inner, inner), Image.LANCZOS), ((fs - inner) // 2, (fs - inner) // 2))
    fg.save(os.path.join(folder, "ic_launcher_foreground.png"))
anyd = os.path.join(RES, "mipmap-anydpi-v26")
os.makedirs(anyd, exist_ok=True)
xml = '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/ic_launcher_background"/><foreground android:drawable="@mipmap/ic_launcher_foreground"/></adaptive-icon>\n'
for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
    open(os.path.join(anyd, n), "w").write(xml)

# ---- Splash ----
sp = b.get("splash") or {}
logo = fetch_image(sp.get("logoUrl")) or icon
logo.thumbnail((512, 512))
logo.save(os.path.join(ASSETS, "splash.png"))

# ---- Lottie (bundled locally: works with no internet) ----
LOTTIE_JS_URL = "https://cdnjs.cloudflare.com/ajax/libs/lottie-web/5.12.2/lottie.min.js"
def load_lottie(url, what):
    try:
        data = json.loads(fetch(url).decode("utf-8"))
        for k in ("v", "fr", "ip", "op", "w", "h", "layers"):
            if k not in data:
                raise Exception("missing " + k)
        return data
    except Exception as e:
        print("::error::Could not load the " + what + " Lottie file: " + str(e))
        sys.exit(1)
need_player = False
if sp.get("type") == "lottie" and sp.get("lottieUrl"):
    anim = load_lottie(sp["lottieUrl"], "startup")
    open(os.path.join(ASSETS, "splash_anim.js"), "w", encoding="utf-8").write("window.AF_ANIM=" + json.dumps(anim) + ";")
    px = {"small": "35vw", "medium": "55vw", "large": "75vw", "full": "100vw"}.get(sp.get("size"), "55vw")
    bg = hexcolor(sp.get("bg"), "#FFFFFF")
    tag = (sp.get("tagline") or "").replace("&", "&amp;").replace("<", "&lt;")
    loop = "true" if sp.get("loop", True) else "false"
    speed = float(sp.get("speed") or 1)
    html = ('<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
        '<style>html,body{margin:0;height:100%%;background:%s;overflow:hidden}body{display:flex;flex-direction:column;align-items:center;justify-content:center;font-family:sans-serif}'
        '#a{width:%s;height:%s;max-height:80vh}p{margin:12px 0 0;font-size:16px;opacity:.8;color:%s}</style></head><body><div id="a"></div>%s'
        '<script src="lottie.min.js"></script><script src="splash_anim.js"></script>'
        '<script>var x=lottie.loadAnimation({container:document.getElementById("a"),renderer:"svg",loop:%s,autoplay:true,animationData:window.AF_ANIM});x.setSpeed(%s);</script></body></html>') % (
        bg, px, px, "#111" if bg.upper() in ("#FFFFFF", "#F4F5F8") else "#fff", ("<p>" + tag + "</p>") if tag else "", loop, speed)
    open(os.path.join(ASSETS, "splash.html"), "w", encoding="utf-8").write(html)
    need_player = True
    print("::notice::Your startup Lottie file is included in this build.")
ol = (cfg.get("offline") or {}).get("lottie") or {}
if ol.get("url"):
    anim = load_lottie(ol["url"], "offline")
    open(os.path.join(ASSETS, "offline_anim.js"), "w", encoding="utf-8").write("window.AF_ANIM=" + json.dumps(anim) + ";")
    need_player = True
    print("::notice::Your offline Lottie file is included in this build.")
if need_player:
    try:
        js = fetch(LOTTIE_JS_URL)
        if len(js) < 50000:
            raise Exception("player file too small")
        open(os.path.join(ASSETS, "lottie.min.js"), "wb").write(js)
        print("Bundled lottie.min.js (" + str(len(js)) + " bytes) inside the APK")
    except Exception as e:
        print("::error::Could not bundle the Lottie player: " + str(e))
        sys.exit(1)

# ---- Manifest: permissions + deep links ----
f = cfg.get("features", {})
perms = []
if f.get("camera"): perms.append("android.permission.CAMERA")
if f.get("microphone"): perms += ["android.permission.RECORD_AUDIO", "android.permission.MODIFY_AUDIO_SETTINGS"]
if f.get("location"): perms += ["android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"]
if f.get("vibration", True): perms.append("android.permission.VIBRATE")
lines = ['<uses-permission android:name="%s"/>' % p for p in perms]
if f.get("downloads", True):
    lines.append('<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28"/>')
mpath = "app/src/main/AndroidManifest.xml"
m = open(mpath, encoding="utf-8").read()
m = m.replace("<!--AF_PERMISSIONS-->", "\n    ".join(lines))
host = urllib.parse.urlparse(cfg.get("url", "")).hostname or ""
deep = ""
if host and cfg.get("navigation", {}).get("deepLinks", True):
    deep = '<intent-filter android:autoVerify="true"><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.DEFAULT"/><category android:name="android.intent.category.BROWSABLE"/><data android:scheme="https" android:host="%s"/></intent-filter>' % host
m = m.replace("<!--AF_DEEPLINKS-->", deep)
open(mpath, "w", encoding="utf-8").write(m)

# ---- Injected JS / CSS ----
SHIM = """(function(){if(window.AppForge)return;var n=window.AppForgeNative;if(!n)return;
window.AppForge={isApp:true,
share:function(t,u){n.share(String(t||''),String(u||''))},
toast:function(m){n.toast(String(m))},
vibrate:function(ms){n.vibrate(Math.round(ms||200))},
openExternal:function(u){n.openExternal(String(u))},
getDeviceInfo:function(){return JSON.parse(n.getDeviceInfo())},
requestPermission:function(p){n.requestPermission(String(p))},
hasPermission:function(p){return n.hasPermission(String(p))},
saveFile:function(u,f){n.saveFile(String(u),String(f||''))},
setStatusBarColor:function(c){n.setStatusBarColor(String(c))},
getPushToken:function(){return n.getPushToken()},
retry:function(){n.retry()}};
try{window.dispatchEvent(new Event('appforgeready'))}catch(e){}})();"""

def guard(pattern, body):
    p = (pattern or "").strip()
    if p in ("", "*"):
        return body
    rx = "^" + ".*".join(re.escape(x) for x in p.split("*")) + "$"
    return "if (new RegExp(" + json.dumps(rx) + ").test(location.href)) {\n" + body + "\n}"

def css_js(css):
    return "(function(){var s=document.createElement('style');s.textContent=" + json.dumps(css) + ";(document.head||document.documentElement).appendChild(s);})();"

start, end = [SHIM], []
for s in cfg.get("code", {}).get("snippets", []):
    if not s.get("enabled", True) or not (s.get("code") or "").strip():
        continue
    body = s["code"] if s.get("type") == "js" else css_js(s["code"])
    label = re.sub(r"[^A-Za-z0-9 _-]", "", s.get("name", ""))
    body = "try {\n" + body + "\n} catch (e) { console.error('AppForge snippet " + label + "', e); }"
    (start if s.get("runAt") == "start" else end).append(guard(s.get("urlPattern", ""), body))
open(os.path.join(ASSETS, "inject_start.js"), "w", encoding="utf-8").write("\n".join(start))
open(os.path.join(ASSETS, "inject_end.js"), "w", encoding="utf-8").write("\n".join(end))

# ---- Offline page + bundled files ----
open(os.path.join(ASSETS, "offline.html"), "w", encoding="utf-8").write(cfg.get("offlineHtml") or "<h1>Offline</h1>")
for a in cfg.get("offline", {}).get("assets", []):
    try:
        os.makedirs(os.path.join(ASSETS, "offline"), exist_ok=True)
        safe = re.sub(r"[^A-Za-z0-9._-]", "_", a.get("name", "file"))
        open(os.path.join(ASSETS, "offline", safe), "wb").write(fetch(a["url"]))
    except Exception as e:
        print("::warning::Could not bundle offline asset: " + str(e))

cfg.pop("offlineHtml", None)
json.dump(cfg, open(os.path.join(ASSETS, "config.json"), "w", encoding="utf-8"))
print("Project prepared.")
