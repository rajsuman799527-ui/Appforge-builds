// AppForge Studio: one Cloudflare Worker = website + API + app-user sign-up API.
// Bindings needed: D1 database "DB", variable GH_REPO (owner/repo), secret GH_TOKEN.
const J=(o,s=200)=>new Response(JSON.stringify(o),{status:s,headers:{"content-type":"application/json"}});
const enc=new TextEncoder(),b64=a=>btoa(String.fromCharCode(...new Uint8Array(a)));
const rid=n=>[...crypto.getRandomValues(new Uint8Array(n))].map(x=>"abcdefghjkmnpqrstuvwxyz23456789"[x%31]).join("");
async function hash(pw,salt){const k=await crypto.subtle.importKey("raw",enc.encode(pw),"PBKDF2",false,["deriveBits"]);return b64(await crypto.subtle.deriveBits({name:"PBKDF2",hash:"SHA-256",salt:enc.encode(salt),iterations:100000},k,256))}
const gh=(e,p,o={})=>fetch("https://api.github.com/repos/"+e.GH_REPO+p,{...o,headers:{authorization:"Bearer "+e.GH_TOKEN,"user-agent":"appforge","accept":"application/vnd.github+json","content-type":"application/json"}});
let ready;
const init=db=>ready||(ready=db.batch([
"CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,email TEXT UNIQUE,name TEXT,hash TEXT,salt TEXT,created INT)",
"CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY,uid TEXT,scope TEXT,exp INT)",
"CREATE TABLE IF NOT EXISTS app_users(id TEXT PRIMARY KEY,app_key TEXT,email TEXT,name TEXT,hash TEXT,salt TEXT,created INT,UNIQUE(app_key,email))",
"CREATE TABLE IF NOT EXISTS builds(id TEXT PRIMARY KEY,uid TEXT,name TEXT,pkg TEXT,btype TEXT,status TEXT,url TEXT,created INT)"
].map(s=>db.prepare(s))));
async function session(db,scope,uid){const t=rid(40)+rid(10);await db.prepare("INSERT INTO sessions VALUES(?,?,?,?)").bind(t,uid,scope,Date.now()+30*864e5).run();return t}
async function authz(req,db,scope){const t=(req.headers.get("authorization")||"").slice(7);if(!t)return null;const r=await db.prepare("SELECT uid FROM sessions WHERE token=? AND scope=? AND exp>?").bind(t,scope,Date.now()).first();return r&&r.uid}
const okMail=e=>/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(e);
// shared sign up / login for builder users (table users) and app users (table app_users)
async function account(db,table,scope,kind,b,extra){
 const email=String(b.email||"").trim().toLowerCase(),pw=String(b.password||""),name=String(b.name||"").trim().slice(0,80);
 if(!okMail(email))return J({ok:false,error:"Enter a valid email address."});
 if(kind==="forgot")return J({ok:true});
 if(pw.length<8)return J({ok:false,error:"Password must be at least 8 characters."});
 const w=extra?" AND app_key=?":"",bind=extra?[email,extra]:[email];
 if(kind==="signup"){
  if(!name)return J({ok:false,error:"Enter your name."});
  if(await db.prepare("SELECT 1 FROM "+table+" WHERE email=?"+w).bind(...bind).first())return J({ok:false,error:"An account with this email already exists."});
  const id=rid(12),salt=rid(16),h=await hash(pw,salt);
  await (extra?db.prepare("INSERT INTO app_users VALUES(?,?,?,?,?,?,?)").bind(id,extra,email,name,h,salt,Date.now()):db.prepare("INSERT INTO users VALUES(?,?,?,?,?,?)").bind(id,email,name,h,salt,Date.now())).run();
  return J({ok:true,token:await session(db,scope,id),user:{name,email}});
 }
 const u=await db.prepare("SELECT * FROM "+table+" WHERE email=?"+w).bind(...bind).first();
 if(!u||await hash(pw,u.salt)!==u.hash)return J({ok:false,error:"Wrong email or password."});
 return J({ok:true,token:await session(db,scope,u.id),user:{name:u.name,email}});
}
function makeCfg(b,origin,uid){
 const pkg=String(b.pkg||"").toLowerCase(),url=String(b.url||"").trim();
 if(!/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(pkg))return"Package name must look like com.yourname.app";
 if(!/^https:\/\/[^\s]+$/.test(url))return"Website URL must start with https://";
 const col=v=>/^#[0-9a-fA-F]{6}$/.test(v)?v:"#4352EB",f=b.features||{};
 return{name:String(b.name||"My App").slice(0,40),packageName:pkg,url,versionCode:b.versionCode||1,versionName:"1.0."+(b.versionCode||1),
  branding:{themeColor:col(b.color),iconUrl:/^https:\/\//.test(b.icon||"")?b.icon:"",splash:{bg:"#FFFFFF",tagline:String(b.tagline||"").slice(0,60),duration:1.5,hideOnLoad:true}},
  offline:{mode:f.offline?"cache":"network"},features:{camera:!!f.camera,microphone:!!f.microphone,location:!!f.location,downloads:true},
  navigation:{pullToRefresh:true,deepLinks:true},
  auth:f.signup?{enabled:true,apiUrl:origin+"/a/"+uid+"_"+pkg}:{enabled:false}};
}
async function refresh(env,rows){
 const live=rows.filter(r=>r.status==="building");if(!live.length)return;
 const runs=(await (await gh(env,"/actions/runs?event=workflow_dispatch&per_page=30")).json()).workflow_runs||[];
 for(const r of live){
  const run=runs.find(x=>x.display_title==="appforge-"+r.id);let st=r.status,url=r.url;
  if(run&&run.status==="completed"){
   if(run.conclusion==="success"){const rel=await gh(env,"/releases/tags/appforge-build-"+r.id);const j=rel.ok?await rel.json():{};const a=(j.assets||[])[0];if(a){st="ready";url=a.browser_download_url}}else st="failed";
  }else if(Date.now()-r.created>90*6e4)st="failed";
  if(st!==r.status){r.status=st;r.url=url;await env.DB.prepare("UPDATE builds SET status=?,url=? WHERE id=?").bind(st,url,r.id).run()}
 }
}
export default{async fetch(req,env){
 const u=new URL(req.url),p=u.pathname;await init(env.DB);
 if(req.method==="GET"&&(p==="/"||p==="/index.html"))return new Response(PAGE,{headers:{"content-type":"text/html;charset=utf-8"}});
 let m;
 if(req.method==="POST"&&(m=p.match(/^\/a\/([\w.\-]+)\/(signup|login|forgot)$/))){let b={};try{b=await req.json()}catch(e){}return account(env.DB,"app_users","app:"+m[1],m[2],b,m[1])}
 if(req.method==="POST"&&(m=p.match(/^\/api\/(signup|login)$/))){let b={};try{b=await req.json()}catch(e){}return account(env.DB,"users","builder",m[1],b)}
 const uid=await authz(req,env.DB,"builder");if(!uid)return J({error:"auth"},401);
 if(p==="/api/builds"&&req.method==="GET"){const r=(await env.DB.prepare("SELECT * FROM builds WHERE uid=? ORDER BY created DESC LIMIT 50").bind(uid).all()).results;await refresh(env,r);return J({builds:r})}
 if(p==="/api/builds"&&req.method==="POST"){
  let b={};try{b=await req.json()}catch(e){}
  const n=((await env.DB.prepare("SELECT COUNT(*) c FROM builds WHERE uid=? AND pkg=?").bind(uid,String(b.pkg||"").toLowerCase()).first()).c||0)+1;b.versionCode=n;
  const cfg=makeCfg(b,u.origin,uid);if(typeof cfg==="string")return J({error:cfg},400);
  const id=rid(10),bt=["debug","release","aab"].includes(b.btype)?b.btype:"debug";
  const put=await gh(env,"/contents/builds/"+id+".json",{method:"PUT",body:JSON.stringify({message:"AppForge build "+id,content:b64(enc.encode(JSON.stringify(cfg)))})});
  if(!put.ok)return J({error:"GitHub refused the config file ("+put.status+"). Check GH_TOKEN and GH_REPO."},502);
  const d=await gh(env,"/actions/workflows/appforge.yml/dispatches",{method:"POST",body:JSON.stringify({ref:"main",inputs:{build_id:id,build_type:bt}})});
  if(!d.ok)return J({error:"Could not start the build ("+d.status+"). The token needs Actions: write."},502);
  await env.DB.prepare("INSERT INTO builds VALUES(?,?,?,?,?,?,?,?)").bind(id,uid,cfg.name,cfg.packageName,bt,"building","",Date.now()).run();
  return J({ok:true,id});
 }
 return J({error:"Not found"},404);
}};
const PAGE=`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><title>AppForge Studio</title>
<style>
:root{--acc:#4352EB;--rgb:67,82,235;--bg:#F5F5F8;--card:#fff;--fg:#14151B;--mute:#5F6272;--line:rgba(20,21,27,.12);--err:#D6364A;--ok:#14935B}
@media(prefers-color-scheme:dark){:root{--bg:#0D0E13;--card:#171821;--fg:#F3F4F8;--mute:#9A9DAE;--line:rgba(255,255,255,.14);--err:#FF6B7D;--ok:#3DD598}}
*{box-sizing:border-box;margin:0}body{font-family:Inter,Roboto,system-ui,sans-serif;color:var(--fg);background:radial-gradient(120% 40% at 50% -6%,rgba(var(--rgb),.3),transparent 65%),var(--bg);min-height:100dvh;padding:calc(env(safe-area-inset-top) + 22px) 20px 40px}
.w{max-width:560px;margin:0 auto}.top{display:flex;justify-content:space-between;align-items:center;margin-bottom:26px}.logo{font-weight:780;font-size:19px;letter-spacing:-.02em}
h1{font-size:30px;line-height:1.1;letter-spacing:-.025em;font-weight:780}.sub{color:var(--mute);margin:10px 0 22px;line-height:1.5}
label{display:block;font-size:13.5px;font-weight:600;margin:14px 0 7px}input,select{width:100%;height:54px;border-radius:16px;border:1px solid var(--line);background:var(--card);color:var(--fg);font:inherit;font-size:16px;padding:0 15px;outline:0}
input:focus,select:focus{border-color:var(--acc);box-shadow:0 0 0 4px rgba(var(--rgb),.18)}input[type=color]{padding:4px;width:100%}
.btn{width:100%;height:56px;border:0;border-radius:18px;font:inherit;font-size:16.5px;font-weight:700;color:#fff;cursor:pointer;margin-top:20px;background:linear-gradient(145deg,var(--acc),rgba(var(--rgb),.78));box-shadow:0 14px 28px -12px rgba(var(--rgb),.75)}
.btn:disabled{opacity:.6}.lnk{background:none;border:0;color:var(--fg);font:inherit;padding:14px 0;cursor:pointer}.lnk b,.sm{color:var(--acc);font-weight:700}.sm{background:none;border:0;font:inherit;cursor:pointer}
.msg{color:var(--err);font-size:14px;min-height:20px;margin-top:12px}.card{background:var(--card);border:1px solid var(--line);border-radius:20px;padding:16px;margin-top:12px}
.row{display:flex;justify-content:space-between;align-items:center;gap:10px}.nm{font-weight:700;font-size:17px}.pk{color:var(--mute);font-size:13px;margin-top:3px;word-break:break-all}
.chip{font-size:12.5px;font-weight:700;padding:5px 11px;border-radius:99px;background:rgba(var(--rgb),.14);color:var(--acc);white-space:nowrap}.chip.ready{background:rgba(20,147,91,.14);color:var(--ok)}.chip.failed{background:rgba(214,54,74,.14);color:var(--err)}
.dl{display:block;text-align:center;margin-top:14px;height:48px;line-height:48px;border-radius:14px;background:var(--acc);color:#fff;font-weight:700;text-decoration:none}
.sw{display:flex;justify-content:space-between;align-items:center;padding:14px 0;border-bottom:1px solid var(--line)}.sw input{width:24px;height:24px;accent-color:var(--acc)}
.empty{text-align:center;color:var(--mute);padding:46px 10px;line-height:1.6}
</style></head><body><div class="w" id="app"></div>
<script>
var T=localStorage.getItem("af_t"),app=document.getElementById("app"),timer;
function esc(s){return String(s==null?"":s).replace(/[&<>"']/g,function(c){return {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]})}
function api(p,body){return fetch(p,{method:body?"POST":"GET",headers:{"content-type":"application/json",authorization:"Bearer "+T},body:body?JSON.stringify(body):undefined}).then(function(r){return r.json().then(function(j){if(r.status==401&&T){T=null;localStorage.removeItem("af_t");auth("login")}return j})})}
function auth(mode){clearTimeout(timer);var s=mode==="signup";
app.innerHTML='<div class="top"><span class="logo">AppForge Studio</span></div><h1>'+(s?"Create your account":"Welcome back")+'</h1><p class="sub">'+(s?"Turn any website into an Android app.":"Log in to build and download your apps.")+'</p><form id="f">'+(s?'<label>Full name</label><input id="n" autocomplete="name">':'')+'<label>Email</label><input id="e" type="email" autocomplete="email"><label>Password</label><input id="p" type="password" autocomplete="'+(s?"new-password":"current-password")+'" placeholder="At least 8 characters"><div class="msg" id="m"></div><button class="btn" id="b">'+(s?"Create account":"Log in")+'</button></form><button class="lnk" id="sw">'+(s?"Already have an account? <b>Log in</b>":"New here? <b>Create an account</b>")+'</button>';
document.getElementById("sw").onclick=function(){auth(s?"login":"signup")};
document.getElementById("f").onsubmit=function(ev){ev.preventDefault();var b=document.getElementById("b");b.disabled=true;
fetch("/api/"+mode,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({name:s?document.getElementById("n").value:"",email:document.getElementById("e").value,password:document.getElementById("p").value})}).then(function(r){return r.json()}).then(function(j){b.disabled=false;if(!j.ok){document.getElementById("m").textContent=j.error;return}T=j.token;localStorage.setItem("af_t",T);home()})}}
function ago(t){var m=Math.round((Date.now()-t)/6e4);return m<1?"just now":m<60?m+" min ago":m<1440?Math.round(m/60)+" h ago":Math.round(m/1440)+" d ago"}
function home(){clearTimeout(timer);api("/api/builds").then(function(j){if(!j.builds)return;var b=j.builds,busy=false;
var h='<div class="top"><span class="logo">AppForge Studio</span><button class="sm" id="lo">Log out</button></div><h1>Your apps</h1><p class="sub">Each build takes about 5 to 10 minutes.</p><button class="btn" id="nw" style="margin-top:0">New app</button>';
if(!b.length)h+='<div class="empty">No apps yet.<br>Tap New app, enter a website, and get an APK.</div>';
b.forEach(function(x){if(x.status==="building")busy=true;h+='<div class="card"><div class="row"><div><div class="nm">'+esc(x.name)+'</div><div class="pk">'+esc(x.pkg)+' \\u00b7 '+esc(x.btype)+' \\u00b7 '+ago(x.created)+'</div></div><span class="chip '+x.status+'">'+(x.status==="building"?"Building":x.status==="ready"?"Ready":"Failed")+'</span></div>'+(x.status==="ready"?'<a class="dl" href="'+esc(x.url)+'">Download '+(x.btype==="aab"?"AAB":"APK")+'</a>':'')+'</div>'});
app.innerHTML=h;document.getElementById("nw").onclick=form;document.getElementById("lo").onclick=function(){T=null;localStorage.removeItem("af_t");auth("login")};
if(busy)timer=setTimeout(home,15000)})}
function sw(id,t){return '<div class="sw"><span>'+t+'</span><input type="checkbox" id="'+id+'"></div>'}
function form(){clearTimeout(timer);
app.innerHTML='<div class="top"><button class="sm" id="bk">Back</button></div><h1>New app</h1><p class="sub">Enter your website and pick the features.</p><form id="f"><label>App name</label><input id="a" placeholder="My Store"><label>Website URL</label><input id="u" inputmode="url" placeholder="https://example.com"><label>Package name</label><input id="k" placeholder="com.yourname.mystore" autocapitalize="none"><label>Theme color</label><input id="c" type="color" value="#4352EB"><label>Icon image URL (optional, https)</label><input id="i" inputmode="url"><label>Splash tagline (optional)</label><input id="t"><label>Build type</label><select id="y"><option value="debug">Debug APK (quick test)</option><option value="release">Release APK</option><option value="aab">AAB (Play Store)</option></select><div style="margin-top:8px">'+sw("o","Offline saved pages")+sw("ca","Camera")+sw("mi","Microphone")+sw("lo","Location")+sw("su","Sign up and login for app users")+'</div><div class="msg" id="m"></div><button class="btn" id="b">Build app</button></form>';
var a=document.getElementById("a"),k=document.getElementById("k");a.oninput=function(){if(!k.dataset.t)k.value="com.app."+a.value.toLowerCase().replace(/[^a-z0-9]/g,"")};k.oninput=function(){k.dataset.t=1};
document.getElementById("bk").onclick=home;
document.getElementById("f").onsubmit=function(ev){ev.preventDefault();var b=document.getElementById("b"),g=function(i){return document.getElementById(i)};b.disabled=true;b.textContent="Starting build...";
api("/api/builds",{name:g("a").value,url:g("u").value,pkg:g("k").value,color:g("c").value,icon:g("i").value,tagline:g("t").value,btype:g("y").value,features:{offline:g("o").checked,camera:g("ca").checked,microphone:g("mi").checked,location:g("lo").checked,signup:g("su").checked}}).then(function(j){if(j.error){b.disabled=false;b.textContent="Build app";g("m").textContent=j.error;return}home()})}}
if(T)home();else auth("login");
</script></body></html>`;
