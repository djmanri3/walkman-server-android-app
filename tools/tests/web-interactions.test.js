const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const {spawnSync} = require('node:child_process');
const script = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/web-interactions.js'), 'utf8');
const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'walkman-interactions-'));
try {
 for (const fallback of [false, true]) {
  const file = path.join(folder, fallback ? 'touch.html' : 'pointer.html');
  fs.writeFileSync(file, `<!doctype html><body data-theme="modern"><div id="view-playing" data-theme="modern"></div>
<div class="list-item" id="song"><span class="title">Song</span><button class="list-item-more">More</button></div>
<div class="queue-item" id="queued"><button class="queue-drag">Drag</button><span class="title">Queue</span><button class="list-item-more">More</button></div>
<article class="music-library-card" id="card"><button class="music-library-track">Song card</button><button class="music-library-card-more">More</button></article>
<pre id="result">RUNNING</pre><script>
(async()=>{try{
 const check=(value,message)=>{if(!value)throw new Error(message);};
 let clock=0,nextTimer=1,menus=0,plays=0;const timers=new Map(),themes=[];
 Date.now=()=>clock;
 window.setTimeout=(fn,ms)=>{const id=nextTimer++;timers.set(id,{fn,time:clock+ms});return id;};
 window.clearTimeout=id=>timers.delete(id);
 function advance(ms){clock+=ms;for(const [id,t]of [...timers])if(t.time<=clock){timers.delete(id);t.fn();}}
 window.AndroidBridge={setWebTheme:t=>themes.push(t)};
 const rows=[...document.querySelectorAll('.list-item,.queue-item,.music-library-card')];
 function wire(row){row.addEventListener('click',()=>plays++);row.querySelector('.list-item-more,.music-library-card-more').onclick=e=>{e.stopPropagation();menus++;};}
 rows.forEach(wire);
 const fallback=${fallback};if(fallback)window.PointerEvent=undefined;
 eval(${JSON.stringify(script)});
 check(themes.join()==='modern','Sync the initial theme');
 for(const theme of ['classic','z1','music','ericsony','walkmanhires','modern']){document.body.dataset.theme=theme;await Promise.resolve();check(themes.at(-1)===theme,'Theme change reaches Android: '+theme);}
 const count=themes.length;document.body.dataset.theme='modern';await Promise.resolve();check(themes.length===count,'Do not resend an unchanged theme');
 const song=document.querySelector('#song .title'),queued=document.querySelector('#queued .title'),card=document.querySelector('.music-library-track');
 function gesture(target,type,x=40,y=60,primary=true){
  let event;
  if(fallback){const points=primary?[{identifier:1,clientX:x,clientY:y}]:[{identifier:1,clientX:x,clientY:y},{identifier:2,clientX:x,clientY:y}];event=new Event(({pointerdown:'touchstart',pointermove:'touchmove',pointerup:'touchend',pointercancel:'touchcancel'})[type],{bubbles:true,cancelable:true});Object.defineProperties(event,{touches:{value:type==='pointerup'?[]:points},changedTouches:{value:points}});if(type==='pointerup'){event=new Event('touchend',{bubbles:true,cancelable:true});Object.defineProperties(event,{touches:{value:[]},changedTouches:{value:points}});}}
  else event=new PointerEvent(type,{bubbles:true,cancelable:true,pointerType:'touch',pointerId:1,button:0,isPrimary:primary,clientX:x,clientY:y});
  target.dispatchEvent(event);return event;
 }
 function click(target,detail=1){const e=new MouseEvent('click',{bubbles:true,cancelable:true,detail,clientX:40,clientY:60});target.dispatchEvent(e);return e;}
 for(const target of [song,queued,card]){
  const before=menus;gesture(target,'pointerdown');advance(600);check(menus===before+1,'Hold opens the existing menu');
  target.dispatchEvent(new MouseEvent('contextmenu',{bubbles:true,cancelable:true}));check(menus===before+1,'Native contextmenu does not duplicate a hold');
  gesture(target,'pointerup');const p=plays;check(click(target).defaultPrevented&&plays===p,'Release must not also play the song');
 }
 const p=plays;gesture(song,'pointerdown');advance(100);gesture(song,'pointerup');click(song);check(plays===p+1,'Short tap still plays');
 let before=menus;gesture(song,'pointerdown');gesture(song,'pointermove',40,90);advance(600);gesture(song,'pointerup');check(menus===before,'Scrolling cancels the hold');
 gesture(song,'pointerdown');gesture(song,'pointercancel');advance(600);check(menus===before,'Cancelled pointer does not open a menu');
 gesture(song,'pointerdown');document.dispatchEvent(new Event('scroll'));advance(600);check(menus===before,'Container scrolling cancels the hold');
 gesture(song,'pointerdown');gesture(song,'pointerdown',40,60,false);advance(600);check(menus===before,'Multitouch cancels the hold');
 const drag=document.querySelector('.queue-drag');gesture(drag,'pointerdown');advance(600);check(menus===before,'Queue drag handle keeps its own gesture');gesture(drag,'pointerup');
 const more=document.querySelector('#song .list-item-more');gesture(more,'pointerdown');advance(600);gesture(more,'pointerup');click(more);check(menus===before+1,'More button remains a single regular action');
 before=menus;const dynamic=rows[0].cloneNode(true);document.body.appendChild(dynamic);wire(dynamic);gesture(dynamic.querySelector('.title'),'pointerdown');advance(600);check(menus===before+1,'Delegation handles newly rendered albums and playlists');gesture(dynamic,'pointerup');
 const detached=rows[0].cloneNode(true);document.body.appendChild(detached);wire(detached);before=menus;gesture(detached.querySelector('.title'),'pointerdown');detached.remove();advance(600);check(menus===before,'Do not open a menu for a removed row');
 document.getElementById('result').textContent='PASS: themes, held rows/cards, click suppression, taps, scrolling, cancellation, drag and dynamic lists (${fallback ? 'touch fallback' : 'pointer events'})';
}catch(error){document.getElementById('result').textContent='FAIL: '+error.stack;}})();
</script>`);
  const chrome=spawnSync(process.env.WALKMAN_TEST_CHROME||'google-chrome',['--headless','--no-sandbox','--disable-gpu','--no-first-run','--user-data-dir='+path.join(folder, fallback?'touch-profile':'pointer-profile'),'--dump-dom','file://'+file],{encoding:'utf8',timeout:30000,maxBuffer:2*1024*1024});
  if(chrome.error)throw chrome.error;
  const result=(chrome.stdout.match(/<pre id="result">([^<]*)<\/pre>/)||[])[1];
  if(chrome.status!==0||!result?.startsWith('PASS:'))throw new Error(result||chrome.stderr);
  console.log(result);
 }
} finally {fs.rmSync(folder,{recursive:true,force:true});}
