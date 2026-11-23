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

# ---- Push provider: exactly one per APK (OneSignal default keeps older projects unchanged) ----
push = cfg.get("push") or {}
provider = "beams" if (push.get("enabled") and push.get("provider") == "beams") else "onesignal"
with open("appforge.properties", "a") as f:
    f.write("pushProvider=%s\n" % provider)
if provider == "beams":
    bm = push.get("beams") or {}
    if not re.match(r"^[0-9a-fA-F-]{36}$", bm.get("instanceId") or ""):
        print("::error::Beams setup failed: the Pusher Beams Instance ID is missing or invalid.")
        sys.exit(1)
    try:
        gs = json.loads(fetch(bm.get("googleServicesUrl") or "").decode("utf-8"))
    except Exception as e:
        print("::error::Beams setup failed: could not download google-services.json: " + str(e))
        sys.exit(1)
    pkgs = [c.get("client_info", {}).get("android_client_info", {}).get("package_name") for c in gs.get("client", [])]
    if pkg not in pkgs:
        print("::error::Beams setup failed: google-services.json is for " + ", ".join([x for x in pkgs if x]) + " but this app is " + pkg + ". Add an Android app with this package name in Firebase and upload the new file.")
        sys.exit(1)
    json.dump(gs, open("app/google-services.json", "w", encoding="utf-8"))
    print("::notice::Pusher Beams enabled (Firebase project " + str(gs.get("project_info", {}).get("project_id")) + "). OneSignal is not included in this APK.")
elif push.get("enabled"):
    print("::notice::OneSignal push enabled.")

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
if provider == "beams": perms.append("android.permission.POST_NOTIFICATIONS")
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
# Custom-scheme callback (<packageName>://...) so OAuth flows opened in a secure browser tab return to this APK.
deep += '<intent-filter><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.DEFAULT"/><category android:name="android.intent.category.BROWSABLE"/><data android:scheme="%s"/></intent-filter>' % pkg.lower()
m = m.replace("<!--AF_DEEPLINKS-->", deep)
svc = ""
if provider == "beams":
    svc = '<service android:name=".BeamsMessagingService" android:exported="false"><intent-filter><action android:name="com.google.firebase.MESSAGING_EVENT"/></intent-filter></service>'
m = m.replace("<!--AF_SERVICES-->", svc)
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
beams:{
addInterest:function(i){return n.pushAddInterest(String(i))},
removeInterest:function(i){return n.pushRemoveInterest(String(i))},
getInterests:function(){try{return JSON.parse(n.pushGetInterests())}catch(e){return []}},
setUserId:function(id){return new Promise(function(res,rej){var k='c'+Date.now()+Math.random();window.__afCb=window.__afCb||{};window.__afCb[k]=function(ok,err){delete window.__afCb[k];ok?res():rej(new Error(err||'setUserId failed'))};n.pushSetUserId(String(id),k)})},
clearAllState:function(){n.pushClearUser()},
getStatus:function(){try{return JSON.parse(n.pushStatus())}catch(e){return {active:false}}}},
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

# ---- Bundled site (fully offline): crawl the website and pack it inside the APK ----
OFF = cfg.get("offline") or {}
if OFF.get("mode") == "bundled":
    import hashlib, time, mimetypes
    from concurrent.futures import ThreadPoolExecutor
    from html.parser import HTMLParser
    from urllib.parse import urlparse, urljoin, urldefrag

    OB = OFF.get("bundled") or {}
    if not OB.get("ownershipConfirmed"):
        print("::error title=Ownership not confirmed::Bundled site needs the ownership confirmation in the Offline step.")
        sys.exit(1)

    def bnum(v, lo, hi, d):
        try:
            v = int(v)
        except Exception:
            v = d
        return max(lo, min(hi, v))

    B_START_RAW = (OB.get("startUrl") or cfg.get("url") or "").strip()
    B_DIR = os.path.join(ASSETS, "site")
    os.makedirs(B_DIR, exist_ok=True)
    B_DEPTH = bnum(OB.get("depth"), 1, 5, 2)
    B_PAGES = bnum(OB.get("maxPages"), 1, 500, 100)
    B_MAX = bnum(OB.get("maxSizeMB"), 5, 150, 50) * 1024 * 1024
    B_EXT = OB.get("includeExternalAssets", True) is not False
    B_RENDER = bool(OB.get("renderJs"))
    B_FILE_CAP = 30 * 1024 * 1024
    B_DEADLINE = time.time() + 20 * 60
    B_BLOCK = re.compile(r"(google-analytics|googletagmanager|doubleclick|facebook\.net|connect\.facebook|hotjar|clarity\.ms|segment\.com|mixpanel|sentry\.io|onesignal|adsbygoogle)", re.I)
    B_SKIP_EXT = (".zip", ".apk", ".exe", ".dmg", ".iso", ".gz", ".rar", ".7z", ".mp4", ".mov", ".avi", ".mkv", ".tar")
    B_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 AppForge-Builder"

    def bclean(u):
        u = urldefrag((u or "").strip())[0]
        p = urlparse(u)
        path = urllib.parse.quote(p.path, safe="/:@!&()*+,;=~'%") or "/"
        query = urllib.parse.quote(p.query, safe="=&+,;:/?@!~*'()%") if p.query else ""
        return p.scheme.lower() + "://" + p.netloc.lower() + path + ("?" + query if query else "")

    def bkey(u):
        return u.split("://", 1)[-1]

    def bbare(h):
        return h[4:] if h.startswith("www.") else h

    def bhost(u):
        return (urlparse(u).netloc or "").lower()

    B_START = bclean(B_START_RAW)
    B_HOST = bbare(bhost(B_START))
    if not B_START.startswith("https://") or not B_HOST:
        print("::error title=Bundled site::The start URL must be a valid https:// address.")
        sys.exit(1)

    def bsame(u):
        return bbare(bhost(u)) == B_HOST

    def bresolve(base, ref):
        ref = (ref or "").strip()
        if not ref or ref.lower().startswith(("data:", "#", "javascript:", "mailto:", "tel:", "about:", "blob:", "sms:", "whatsapp:")):
            return None
        try:
            u = bclean(urljoin(base, ref))
        except Exception:
            return None
        return u if u.startswith(("http://", "https://")) else None

    class BLinks(HTMLParser):
        SKIP_REL = ("canonical", "alternate", "dns-prefetch", "preconnect", "author", "prev", "next", "search", "shortlink")

        def __init__(self):
            HTMLParser.__init__(self, convert_charrefs=True)
            self.pages, self.assets, self.css, self._style = [], [], [], False

        def handle_starttag(self, tag, attrs):
            a = dict(attrs)
            if tag in ("a", "area"):
                if a.get("href"):
                    self.pages.append(a["href"])
            elif tag == "link":
                rel = (a.get("rel") or "").lower()
                if a.get("href") and not any(r in rel for r in self.SKIP_REL):
                    self.assets.append(a["href"])
            elif tag in ("script", "img", "source", "audio", "video", "track", "embed", "input"):
                for k in ("src", "poster", "data-src", "data-poster"):
                    if a.get(k):
                        self.assets.append(a[k])
                for k in ("srcset", "data-srcset"):
                    for part in (a.get(k) or "").split(","):
                        tok = part.strip().split(" ")[0]
                        if tok:
                            self.assets.append(tok)
            if a.get("style"):
                self.css.append(a["style"])
            if tag == "style":
                self._style = True

        def handle_endtag(self, tag):
            if tag == "style":
                self._style = False

        def handle_data(self, data):
            if self._style:
                self.css.append(data)

    B_CSS_URL = re.compile(r"url\(\s*['\"]?([^'\")]+?)['\"]?\s*\)", re.I)
    B_CSS_IMP = re.compile(r"@import\s+['\"]([^'\"]+)['\"]", re.I)

    def bcss_refs(text):
        return B_CSS_URL.findall(text) + B_CSS_IMP.findall(text)

    def bfetch(u):
        try:
            req = urllib.request.Request(u, headers={"User-Agent": B_UA, "Accept": "*/*", "Accept-Language": "en"})
            with urllib.request.urlopen(req, timeout=25) as r:
                final = r.geturl()
                ctype = (r.headers.get("Content-Type") or "").split(";")[0].strip().lower()
                charset = r.headers.get_content_charset() or ""
                etag = r.headers.get("ETag") or ""
                lmod = r.headers.get("Last-Modified") or ""
                data = r.read(B_FILE_CAP + 1)
            if len(data) > B_FILE_CAP:
                return {"url": u, "err": "file is larger than 30 MB"}
            if not ctype or ctype == "application/octet-stream":
                ctype = mimetypes.guess_type(urlparse(final).path)[0] or ctype or "application/octet-stream"
            return {"url": u, "final": bclean(final), "ctype": ctype, "charset": charset, "data": data, "etag": etag, "lm": lmod}
        except Exception as e:
            return {"url": u, "err": str(e)[:120]}

    B_POOL = ThreadPoolExecutor(max_workers=8)
    b_manifest, b_fallback, b_failed = {}, {}, []
    b_st = {"total": 0, "files": 0, "pages": 0}
    b_meta = {"start": B_START, "etag": "", "lm": ""}

    def bsave(res):
        final, ctype = res["final"], res["ctype"]
        k = bkey(final)
        if k not in b_manifest:
            ext = {"text/javascript": ".js", "application/javascript": ".js", "text/html": ".html", "application/json": ".json", "image/jpeg": ".jpg", "image/svg+xml": ".svg"}.get(ctype) or mimetypes.guess_extension(ctype) or os.path.splitext(urlparse(final).path)[1][:8] or ".bin"
            name = hashlib.sha1(k.encode("utf-8")).hexdigest()[:20] + ext
            open(os.path.join(B_DIR, name), "wb").write(res["data"])
            entry = {"f": name, "m": ctype}
            if res["charset"]:
                entry["c"] = res["charset"]
            b_manifest[k] = entry
            b_st["total"] += len(res["data"])
            b_st["files"] += 1
            if b_st["total"] > B_MAX:
                print("::error title=Bundled site is too large::The site is bigger than your limit of " + str(B_MAX // (1024 * 1024)) + " MB. Raise the limit or lower the depth/pages in the Offline step.")
                sys.exit(1)
        entry = b_manifest[k]
        for u in (res["url"], final):
            kk = bkey(u)
            b_manifest.setdefault(kk, entry)
            b_fallback.setdefault(kk.split("?")[0], entry)
        return entry

    def btext(res):
        return res["data"].decode(res["charset"] or "utf-8", "ignore")

    def bfetch_assets(urls):
        pending = list(urls)
        while pending and time.time() < B_DEADLINE:
            batch = []
            for u in pending:
                if not u or bkey(u) in b_manifest or bkey(u) in b_seen_assets or B_BLOCK.search(u):
                    continue
                if not B_EXT and not bsame(u):
                    continue
                b_seen_assets.add(bkey(u))
                batch.append(u)
            pending = []
            for res in B_POOL.map(bfetch, batch):
                if "err" in res:
                    b_failed.append(res["url"] + "  (" + res["err"] + ")")
                    continue
                bsave(res)
                if res["ctype"] == "text/css":
                    for ref in bcss_refs(btext(res)):
                        r = bresolve(res["final"], ref)
                        if r:
                            pending.append(r)

    b_pw = {"tried": False, "mod": None}

    def bplaywright():
        if not b_pw["tried"]:
            b_pw["tried"] = True
            try:
                import subprocess
                try:
                    import playwright
                except ImportError:
                    subprocess.check_call([sys.executable, "-m", "pip", "install", "--quiet", "--break-system-packages", "playwright==1.47.0"])
                subprocess.check_call([sys.executable, "-m", "playwright", "install", "--with-deps", "chromium"])
                from playwright.sync_api import sync_playwright
                b_pw["mod"] = sync_playwright
            except Exception as e:
                print("::warning title=JavaScript rendering unavailable::" + str(e)[:150] + " - the pages are saved without JavaScript rendering.")
        return b_pw["mod"]

    def brender(urls):
        """Open each page in a real browser: collect the links it shows and every file it loads (scripts, JSON, images, fonts)."""
        out = {}
        sp = bplaywright()
        if not sp or not urls:
            return out
        try:
            with sp() as pw:
                browser = pw.chromium.launch(args=["--no-sandbox", "--disable-dev-shm-usage"])
                ctx = browser.new_context(user_agent=B_UA, viewport={"width": 412, "height": 915})
                for u in urls:
                    if time.time() > B_DEADLINE:
                        break
                    seen_resp = []
                    page = ctx.new_page()
                    page.on("response", lambda r, acc=seen_resp: acc.append(r))
                    links = []
                    try:
                        page.goto(u, wait_until="networkidle", timeout=30000)
                    except Exception:
                        pass
                    try:
                        page.wait_for_timeout(800)
                        page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
                        page.wait_for_timeout(500)
                        links = page.eval_on_selector_all("a[href]", "els => els.map(e => e.href)")
                    except Exception:
                        pass
                    cap = []
                    for r in seen_resp:
                        try:
                            if r.request.method != "GET" or r.status != 200 or r.request.resource_type in ("document", "websocket", "eventsource", "ping"):
                                continue
                            ru = bclean(r.url)
                            if not ru.startswith(("http://", "https://")) or B_BLOCK.search(ru) or bkey(ru) in b_manifest:
                                continue
                            if not B_EXT and not bsame(ru):
                                continue
                            body = r.body()
                            if len(body) > B_FILE_CAP:
                                continue
                            hdr = r.headers.get("content-type") or ""
                            ct = hdr.split(";")[0].strip().lower() or mimetypes.guess_type(urlparse(ru).path)[0] or "application/octet-stream"
                            cm = re.search(r"charset=([A-Za-z0-9_-]+)", hdr)
                            cap.append({"url": ru, "final": ru, "ctype": ct, "charset": cm.group(1) if cm else "", "data": body})
                        except Exception:
                            pass
                    out[u] = {"links": links, "captured": cap}
                    page.close()
                browser.close()
        except Exception as e:
            print("::warning title=JavaScript rendering stopped::" + str(e)[:150])
        return out

    b_seen_assets, b_seen_pages = set(), {bkey(B_START)}
    level = [B_START]
    for depth in range(B_DEPTH + 1):
        if not level or time.time() > B_DEADLINE:
            break
        print("::group::Bundled site - level " + str(depth) + " (" + str(len(level)) + " pages)")
        nxt, assets_found = [], []
        level_results = list(B_POOL.map(bfetch, level))
        rend = {}
        if B_RENDER:
            rend = brender([r["final"] for r in level_results if "err" not in r and r["ctype"] in ("text/html", "application/xhtml+xml") and bsame(r["final"])])
        for res in level_results:
            if "err" in res:
                b_failed.append(res["url"] + "  (" + res["err"] + ")")
                if res["url"] == B_START:
                    print("::error title=Bundled site::Could not open the start page: " + res["err"])
                    sys.exit(1)
                continue
            if res["ctype"] in ("text/html", "application/xhtml+xml"):
                if not bsame(res["final"]):
                    continue
                bsave(res)
                b_st["pages"] += 1
                if res["url"] == B_START:
                    b_meta["etag"], b_meta["lm"] = res.get("etag", ""), res.get("lm", "")
                lp = BLinks()
                try:
                    lp.feed(btext(res))
                except Exception:
                    pass
                for ref in lp.assets:
                    r = bresolve(res["final"], ref)
                    if r:
                        assets_found.append(r)
                for css in lp.css:
                    for ref in bcss_refs(css):
                        r = bresolve(res["final"], ref)
                        if r:
                            assets_found.append(r)
                rr = rend.get(res["final"]) or {"links": [], "captured": []}
                for c in rr["captured"]:
                    bsave(c)
                    if c["ctype"] == "text/css":
                        for ref in bcss_refs(btext(c)):
                            r = bresolve(c["final"], ref)
                            if r:
                                assets_found.append(r)
                for ref in lp.pages + rr["links"]:
                    r = bresolve(res["final"], ref)
                    if not r or not bsame(r) or r.lower().split("?")[0].endswith(B_SKIP_EXT):
                        continue
                    if bkey(r) in b_seen_pages or bkey(r) in b_manifest or len(b_seen_pages) >= B_PAGES:
                        continue
                    b_seen_pages.add(bkey(r))
                    nxt.append(r)
            else:
                bsave(res)
                if res["ctype"] == "text/css":
                    for ref in bcss_refs(btext(res)):
                        r = bresolve(res["final"], ref)
                        if r:
                            assets_found.append(r)
        bfetch_assets(assets_found)
        print("::endgroup::")
        level = nxt

    if b_st["pages"] == 0:
        print("::error title=Bundled site::No pages could be saved. Check the start URL.")
        sys.exit(1)

    b_files = dict(b_manifest)
    for k, v in b_fallback.items():
        b_files.setdefault(k, v)
    for k in list(b_files.keys()):
        if "?" not in k and b_files[k].get("m") in ("text/html", "application/xhtml+xml"):
            b_files.setdefault(k[:-1] if k.endswith("/") else k + "/", b_files[k])
    for k in list(b_files.keys()):
        h = k.split("/", 1)[0]
        if bbare(h) == B_HOST:
            b_files.setdefault(k[4:] if h.startswith("www.") else "www." + k, b_files[k])
    json.dump({"v": 1, "host": B_HOST, "start": b_meta["start"], "etag": b_meta["etag"], "lm": b_meta["lm"], "files": b_files}, open(os.path.join(B_DIR, "manifest.json"), "w", encoding="utf-8"))

    print("::group::Bundled site report")
    print("Pages saved: " + str(b_st["pages"]))
    print("Files saved: " + str(b_st["files"]))
    print("Total size: " + str(round(b_st["total"] / 1048576.0, 2)) + " MB")
    print("Failed files: " + str(len(b_failed)))
    for line in b_failed[:50]:
        print("  - " + line)
    print("::endgroup::")
    print("::notice title=Bundled site::" + str(b_st["pages"]) + " pages and " + str(b_st["files"]) + " files (" + str(round(b_st["total"] / 1048576.0, 1)) + " MB) are packed inside the app, " + str(len(b_failed)) + " failed.")
    B_POOL.shutdown()

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
