// Run: node tools/tests/android-playback.test.js (requires Google Chrome).
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const script = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/android-playback.js'), 'utf8');
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'walkman-playback-test-'));
const fixture = path.join(directory, 'test.html');
fs.writeFileSync(fixture, `<!doctype html><body>
<div id="track-title">Restored song</div><pre id="result">RUNNING</pre><script>
const playbackScript = ${JSON.stringify(script)};
let playlist = [{name:'One'}, {name:'Two'}, {name:'Three'}];
let currentTrackIndex = 1;
let starts = [], toggles = 0;
const audio = {paused:true, currentSrc:'', source:'', getAttribute: function(){return this.source;}};
const lookup = document.getElementById.bind(document);
document.getElementById = id => id === 'audio-player' ? audio : lookup(id);
window.playTrack = function(index) {
  document.getElementById('track-title-text').textContent = playlist[index].name;
  starts.push(index); currentTrackIndex = index; audio.source = 'test-stream'; audio.paused = false;
  return 'started';
};
window.togglePlay = function(){ toggles++; audio.paused = !audio.paused; };
function check(ok, message){ if(!ok) throw new Error(message); }
try {
  eval(playbackScript);
  check(lookup('track-title-text').textContent === 'Restored song', 'Restore title DOM without losing text');
  check(wmAndroidTransport('pause') && toggles === 0, 'Pause while paused must be harmless');
  check(wmAndroidTransport('play') && starts.join() === '1' && !audio.paused, 'Play must start the restored current song');
  check(wmAndroidTransport('play') && starts.length === 1 && toggles === 0, 'Repeated Play must not pause or restart');
  check(wmAndroidTransport('pause') && audio.paused && toggles === 1, 'Pause must stop playback');
  wmAndroidTransport('pause');
  check(toggles === 1, 'Repeated Pause must not resume');
  check(wmAndroidTransport('play') && !audio.paused && toggles === 2 && starts.length === 1, 'Resume loaded audio');
  lookup('track-title').textContent = 'Queue restored again';
  check(window.playTrack(2) === 'started' && starts.join() === '1,2', 'Repair title for playlist, queue and next-track commands');
  const wrapped = window.playTrack;
  eval(playbackScript);
  check(window.playTrack === wrapped, 'Repeated injection must not wrap twice');
  audio.paused = true; audio.source = ''; currentTrackIndex = 99;
  check(wmAndroidTransport('play') && starts.at(-1) === 0, 'Invalid queue index falls back to first song');
  audio.paused = true; audio.source = ''; playlist = [];
  check(wmAndroidTransport('play') === false, 'Empty queue must not throw or start unrelated audio');
  check(wmAndroidTransport('unknown') === false, 'Reject unknown actions');
  lookup('result').textContent = 'PASS: restored queue, missing title span, Play/Pause idempotence, resume and next-track compatibility';
} catch(error) { lookup('result').textContent = 'FAIL: ' + error.stack; }
</script>`);
try {
  const chrome = spawnSync(process.env.WALKMAN_TEST_CHROME || 'google-chrome', [
    '--headless', '--no-sandbox', '--disable-gpu', '--no-first-run',
    '--user-data-dir=' + path.join(directory, 'profile'), '--dump-dom', 'file://' + fixture
  ], { encoding:'utf8', timeout:30000, maxBuffer:2*1024*1024 });
  if (chrome.error) throw chrome.error;
  const result = (chrome.stdout.match(/<pre id="result">([^<]*)<\/pre>/) || [])[1];
  if (chrome.status !== 0 || !result || !result.startsWith('PASS:')) throw new Error(result || chrome.stderr);
  console.log(result);
} finally { fs.rmSync(directory, {recursive:true, force:true}); }
