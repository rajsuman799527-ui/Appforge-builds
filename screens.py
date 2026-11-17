"""AppForge premium screens. Runs after prepare.py.
Usage: python3 scripts/screens.py builds/<id>.json
- Writes a premium offline/error screen (unless you supplied your own offlineHtml)
- Writes a sign in / sign up screen when cfg.auth.enabled (needs cfg.auth.apiUrl)
- Writes a premium animated splash (unless a Lottie splash is in use)
Uses only the Python standard library.
"""
import json, os, re, sys

cfg = json.load(open(sys.argv[1], encoding="utf-8"))
A = "app/src/main/assets/appforge"
T = os.path.join(os.path.dirname(os.path.abspath(__file__)), "screens")

def hexc(v, d):
    return v if isinstance(v, str) and re.match(r"^#[0-9a-fA-F]{6}$", v) else d

def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))

def light(h):
    r, g, b = rgb(h)
    return (0.299 * r + 0.587 * g + 0.114 * b) > 150

def esc(s):
    return (s or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

def fill(tpl, m):
    for k, v in m.items():
        tpl = tpl.replace(k, v)
    return tpl

b = cfg.get("branding") or {}
sp = b.get("splash") or {}
off = cfg.get("offline") or {}
acc = hexc(b.get("themeColor"), "#4352EB")
name = esc(cfg.get("name") or "My App")
common = {"__ACC__": acc, "__ACC_RGB__": ",".join(str(x) for x in rgb(acc)), "__ONACC__": "#14151B" if light(acc) else "#FFFFFF", "__NAME__": name}

# ---- Offline / error screen ----
custom = (cfg.get("offlineHtml") or "").strip()
if (not custom or custom == "<h1>Offline</h1>") and off.get("premium", True) is not False:
    html = fill(open(os.path.join(T, "offline.html"), encoding="utf-8").read(), dict(common, **{"__SAVED__": "true" if off.get("mode") == "cache" else "false"}))
    open(os.path.join(A, "offline.html"), "w", encoding="utf-8").write(html)
    print("::notice::Premium offline screen added.")

# ---- Sign in / sign up (only when auth is enabled) ----
au = cfg.get("auth") or {}
if au.get("enabled"):
    if not re.match(r"^https://", au.get("apiUrl") or ""):
        print("::error title=Sign in::Auth is on but the API URL is missing or not https://")
        sys.exit(1)
    html = fill(open(os.path.join(T, "auth.html"), encoding="utf-8").read(), common)
    open(os.path.join(A, "auth.html"), "w", encoding="utf-8").write(html)
    print("::notice::Sign in / sign up screen added.")

# ---- Splash ----
has_lottie = sp.get("type") == "lottie" and os.path.exists(os.path.join(A, "splash_anim.js"))
if sp and not has_lottie and sp.get("premium", True) is not False:
    bg = hexc(sp.get("bg"), "#FFFFFF")
    tag = esc(sp.get("tagline") or "")
    html = fill(open(os.path.join(T, "splash.html"), encoding="utf-8").read(), dict(common, **{
        "__BG__": bg, "__FG__": "#14151B" if light(bg) else "#FFFFFF",
        "__TAG__": ("<p>" + tag + "</p>") if tag else ""}))
    open(os.path.join(A, "splash.html"), "w", encoding="utf-8").write(html)
    cp = os.path.join(A, "config.json")
    c2 = json.load(open(cp, encoding="utf-8"))
    c2.setdefault("branding", {}).setdefault("splash", {})["type"] = "premium"
    json.dump(c2, open(cp, "w", encoding="utf-8"))
    print("::notice::Premium splash screen added.")
