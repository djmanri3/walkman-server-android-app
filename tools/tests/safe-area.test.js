const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const {spawnSync} = require('node:child_process');
const java = fs.readFileSync(path.join(__dirname, '../../app/src/main/java/com/djmanri3/Walkman/MainActivity.java'), 'utf8');
const template = java.match(/SAFE_AREA_SCRIPT = """([\s\S]*?)""";/)[1];
const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'walkman-safe-area-'));
try {
  const file = path.join(folder, 'test.html');
  fs.writeFileSync(file, `<!doctype html><meta name="viewport" content="width=device-width,viewport-fit=cover">
<style>
*{box-sizing:border-box}body{margin:0}header{height:56px;padding-top:0}header span{display:block;height:40px}
body[data-theme=music] header{padding-top:var(--wm-inset-top)}
#visualizer-overlay{position:absolute;inset:0;display:none;flex-direction:column}
#visualizer-overlay.open{display:flex}.visualizer-header{height:56px}.canvas{flex:1}
</style><body data-theme="modern"><header><span>Header</span></header>
<div id="visualizer-overlay"><div class="visualizer-header">Visualizer</div><div class="canvas"></div></div>
<pre id="result">RUNNING</pre><script>
(async()=>{try{
const template=${JSON.stringify(template)};
// Use timer-backed animation frames for deterministic headless execution.
window.requestAnimationFrame=fn=>setTimeout(fn,0);window.cancelAnimationFrame=clearTimeout;
const wait=()=>new Promise(resolve=>setTimeout(resolve,30));
const check=(v,m)=>{if(!v)throw Error(m)};
function inject(top){let i=0;const insets=[top*devicePixelRatio,0,0,0,0];eval(template.replace(/%d/g,()=>insets[i++]));}
const overlay=document.getElementById('visualizer-overlay');
inject(46);overlay.classList.add('open');
for(let repeat=0;repeat<3;repeat++)for(const theme of ['music','classic','z1','modern']){
 document.body.dataset.theme=theme;await wait();
 check(Math.abs(overlay.querySelector('.visualizer-header').getBoundingClientRect().top-46)<0.5,'Visualizer respects status bar: '+theme);
 const reserved=parseFloat(getComputedStyle(document.body).paddingTop)+parseFloat(getComputedStyle(document.querySelector('header')).paddingTop);
 check(Math.abs(reserved-46)<0.5,'Theme reserves exactly one top inset: '+theme);
}
inject(0);await wait();check(overlay.querySelector('.visualizer-header').getBoundingClientRect().top===0,'Fullscreen removes top inset');
inject(60);await wait();check(overlay.querySelector('.visualizer-header').getBoundingClientRect().top===60,'Restoring bars uses latest inset');
document.body.dataset.theme='music';await wait();
check(parseFloat(getComputedStyle(document.body).paddingTop)===0,'Theme observer uses latest inset');
for(let i=0;i<3;i++){window.dispatchEvent(new Event('resize'));await wait();check(document.querySelector('header').getBoundingClientRect().height===100,'Header minimum remains stable');}
check(document.querySelectorAll('#wm-safe-area-style').length===1,'One correction stylesheet');
document.getElementById('result').textContent='PASS: visualizer, repeated themes, fullscreen, restored insets and stable header height';
}catch(e){document.getElementById('result').textContent='FAIL: '+e.stack;}})();
</script>`);
  const chrome = spawnSync(process.env.WALKMAN_TEST_CHROME || 'google-chrome', ['--headless','--no-sandbox','--disable-gpu','--no-first-run','--user-data-dir='+path.join(folder,'profile'),'--virtual-time-budget=5000','--dump-dom','file://'+file], {encoding:'utf8',timeout:30000,maxBuffer:2*1024*1024});
  if (chrome.error) throw chrome.error;
  const result = (chrome.stdout.match(/<pre id="result">([^<]*)<\/pre>/)||[])[1];
  if (chrome.status !== 0 || !result?.startsWith('PASS:')) throw Error(result || chrome.stderr);
  console.log(result);
} finally { fs.rmSync(folder,{recursive:true,force:true}); }
