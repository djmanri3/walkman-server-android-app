const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const { spawnSync } = require('node:child_process');
const asset = name => fs.readFileSync(path.join(__dirname, '../../app/src/main/assets', name), 'utf8');
const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'walkman-sony-web-'));
const html = path.join(folder, 'test.html');
fs.writeFileSync(html, `<!doctype html><base href="https://test.invalid/music/"><body>
<audio id="audio-player"></audio><div id="track-title">Saved title</div><button id="eq-btn"></button><pre id="result">RUNNING</pre><script>
let audioCtx = null, analyserNode = null;
let playlist = [{Id:'1'}], currentTrackIndex = 0;
const audio = document.getElementById('audio-player'), calls = [], events = [];
const nativeSrc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src').get;
let sonyInstalled = false, webEqualizerOpened = 0;
window.SonyAudio = {isAvailable:()=>true,isEqualizerInstalled:()=>sonyInstalled,getState:()=>'{"version":0}',
 load:(source,version)=>calls.push(['load',source,version]),play:()=>calls.push(['play']),pause:()=>calls.push(['pause']),
 seek:time=>calls.push(['seek',time]),setVolume:value=>calls.push(['volume',value]),openEqualizer:()=>calls.push(['eq'])};
window.toggleEqualizer = ()=>{webEqualizerOpened++;};
window.playTrack = function(){ audio.src = '/restored.mp3'; return audio.play(); };
window.togglePlay = function(){ if(audio.paused) return audio.play(); audio.pause(); };
function check(value,message){ if(!value) throw new Error(message); }
function version(){return calls.filter(c=>c[0]==='load').at(-1)[2];}
['loadedmetadata','play','playing','pause','ended','timeupdate','seeked','error'].forEach(name=>audio.addEventListener(name,()=>events.push(name)));
(async()=>{try {
 const webPlay=audio.play, webEqualizer=window.toggleEqualizer;
 eval(${JSON.stringify(asset('sony-audio.js'))});
 check(!window.__wmSonyAudio && !document.documentElement.dataset.audioEngine && audio.play===webPlay && calls.length===0, 'Without Sony preserve web playback and Web Audio DSP');
 check(window.toggleEqualizer===webEqualizer, 'Keep original web equalizer');
 toggleEqualizer();check(webEqualizerOpened===1, 'Web equalizer opens without Sony installed');
 sonyInstalled=true;
 eval(${JSON.stringify(asset('sony-audio.js'))});
 eval(${JSON.stringify(asset('android-playback.js'))});
 check(document.documentElement.dataset.audioEngine==='sony', 'Native engine selected');
 check(typeof analyserNode.getByteFrequencyData==='function', 'Native spectrum available');
 audio.src = '../first.mp3';
 check(audio.src==='https://test.invalid/first.mp3' && nativeSrc.call(audio)==='', 'Do not start the WebView decoder as well');
 const promise = audio.play();
 check(!audio.paused && !audio.readyState, 'Loading starts without pretending to be ready');
 wmSonyAudioState({version:version()-1,ready:true,playing:true,position:100,duration:200});
 check(audio.currentTime===0, 'Discard stale track state');
 wmSonyAudioState({version:version(),ready:true,playing:true,position:1,duration:30,spectrum:btoa(String.fromCharCode(...Array(128).fill(120)))});
 await promise;
 check(!events.includes('seeked'), 'Progress is not a seek completion');
 check(audio.duration===30 && audio.currentTime===1 && events.includes('loadedmetadata') && events.includes('playing'), 'Metadata and progress reach the webapp');
 const bins = new Uint8Array(128); analyserNode.getByteFrequencyData(bins);
 check(bins[0]===120 && bins[127]===120, 'Visualizer reads post-DSP spectrum');
 audio.currentTime=7;
 check(calls.at(-1).join()==='seek,7', 'Seeking reaches native player');
 audio.volume=0.4; audio.muted=true; audio.muted=false;
 check(calls.at(-1).join()==='volume,0.4', 'Volume and mute preserve level');
 audio.pause();audio.pause();
 check(audio.paused && events.filter(e=>e==='pause').length===1, 'Pause is idempotent');
 const resumed=audio.play();
 wmSonyAudioState({version:version(),ready:true,playing:true,position:8,duration:30});await resumed;
 check(events.filter(e=>e==='seeked').length===1, 'One completion per seek');
 check(!audio.paused, 'Resume native stream');
 let completed=0; audio.addEventListener('ended',()=>completed++);
 wmSonyAudioState({version:version(),ready:true,playing:false,ended:true,position:30,duration:30});
 wmSonyAudioState({version:version(),ready:true,playing:false,ended:true,position:30,duration:30});
 check(completed===1 && audio.ended, 'Only one completion event per song');
 document.getElementById('track-title').textContent='Restored again';
 wmAndroidTransport('play');
 check(document.getElementById('track-title-text'), 'Title compatibility retained');
 // Resolve the play() invoked by the transport, then verify native panel routing.
 wmSonyAudioState({version:version(),ready:true,playing:true,position:0,duration:30});
 toggleEqualizer(); check(calls.at(-1)[0]==='eq', 'Open the original Sony controller');
 wmSonyAudioState({version:version(),ready:false,playing:false,error:'Decode failed'});
 wmSonyAudioState({version:version(),ready:false,playing:false,error:'Decode failed'});
 check(events.filter(e=>e==='error').length===1, 'Repeated native status does not repeat playback failures');
 document.getElementById('result').textContent='PASS: native routing, no double decoding, metadata, progress, seek, volume, pause/resume, completion, spectrum and Sony panel';
}catch(error){document.getElementById('result').textContent='FAIL: '+error.stack;}})();
</script>`);
try {
 const chrome=spawnSync(process.env.WALKMAN_TEST_CHROME||'google-chrome',['--headless','--no-sandbox','--disable-gpu','--no-first-run','--user-data-dir='+path.join(folder,'profile'),'--dump-dom','file://'+html],{encoding:'utf8',timeout:30000,maxBuffer:2*1024*1024});
 if(chrome.error)throw chrome.error;
 const result=(chrome.stdout.match(/<pre id="result">([^<]*)<\/pre>/)||[])[1];
 if(chrome.status!==0||!result?.startsWith('PASS:'))throw new Error(result||chrome.stderr);
 console.log(result);
}finally{fs.rmSync(folder,{recursive:true,force:true});}
