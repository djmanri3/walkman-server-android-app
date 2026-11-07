// Run: node tools/tests/back-navigation.test.js (requires Google Chrome).
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const script = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/back-navigation.js'), 'utf8');
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'walkman-back-test-'));
const fixture = path.join(directory, 'test.html');
fs.writeFileSync(fixture, `<!doctype html><body><div id="fixture"></div><pre id="result">RUNNING</pre><script>
const backScript = ${JSON.stringify(script)};
let calls = [];
function check(condition, message) { if (!condition) throw new Error(message); }
function reset(html) { document.getElementById('fixture').innerHTML = html; calls = []; }
function back() { return eval(backScript); }
const music = '<div id="view-music" class="active">My Music</div>';
const playing = '<div id="view-playing" class="active">Playing</div>';
window.switchTab = tab => { calls.push('tab:' + tab); };
let count = 0;
try {
  reset(music);
  check(back() === true && calls.join() === 'tab:playing', 'My Music root must return to Playing'); count++;

  reset(playing);
  check(back() === true && calls[0] === 'tab:music', 'Player must return to music'); count++;

  reset(music + '<button class="back-btn">Back</button>');
  let steps = 2;
  document.querySelector('.back-btn').onclick = () => {
    steps--; if (!steps) document.querySelector('.back-btn').style.display = 'none';
  };
  check(back() && steps === 1, 'Album must use the web back button');
  check(back() && steps === 0, 'Category must use the web back button');
  check(back() && steps === 0 && calls.join() === 'tab:playing', 'After leaving the library, return to Playing'); count++;

  reset(playing + '<div style="display:none"><button class="back-btn">Hidden</button></div>');
  document.querySelector('.back-btn').onclick = () => { throw new Error('Hidden button clicked'); };
  check(back() && calls[0] === 'tab:music', 'Ignore buttons in inactive views'); count++;

  reset(music + '<div id="settings-overlay" class="open">Settings</div><div id="emby-modal">Server</div>');
  window.closeServerModal = () => { calls.push('server'); document.getElementById('emby-modal').style.display = 'none'; };
  window.closeSettingsMenu = () => { calls.push('settings'); document.getElementById('settings-overlay').classList.remove('open'); };
  check(back() && calls.join() === 'server', 'Close server before underlying settings');
  check(back() && calls.join() === 'server,settings', 'Next back closes settings'); count++;

  reset(music + '<div id="settings-overlay" class="open">Settings</div><div id="settings-submenu-effects" class="settings-submenu open">Effects</div>');
  window.closeSettingsSection = name => { calls.push(name); document.querySelector('.settings-submenu').classList.remove('open'); };
  check(back() && calls.join() === 'effects', 'Submenu returns to settings');
  check(back() && calls.join() === 'effects,settings', 'Settings close on next back'); count++;

  const panels = [
    ['search-overlay', 'open', 'toggleSearchOverlay'],
    ['queue-drawer', 'open', 'toggleQueueDrawer'],
    ['view-lyrics', 'active', 'toggleLyricsView'],
    ['visualizer-overlay', 'open', 'toggleVisualizerView'],
    ['eq-overlay', 'open', 'closeEqualizer'],
    ['context-menu-overlay', 'open', 'closeContextMenu'],
    ['playlist-select-overlay', 'open', 'closePlaylistSelectMenu']
  ];
  for (const [id, state, action] of panels) {
    reset(playing + '<div id="' + id + '" class="' + state + '">Panel</div>');
    window[action] = () => { calls.push(action); document.getElementById(id).classList.remove(state); };
    check(back() && calls.join() === action, 'Close panel before navigating: ' + id);
    check(back() && calls[1] === 'tab:music', 'Navigate after panel closes: ' + id); count++;
  }
  reset('<div>Custom server page</div>');
  check(back() === false, 'Unknown webapp must use native browser history'); count++;

  reset('<div id="emby-modal" style="display:none">Hidden server</div>' + music);
  check(back() && calls.join() === 'tab:playing', 'Hidden modals must not intercept return to Playing'); count++;
  document.getElementById('result').textContent = 'PASS: ' + count + ' browser navigation scenarios';
} catch (error) { document.getElementById('result').textContent = 'FAIL: ' + error.stack; }
</script>`);
try {
  const chrome = spawnSync(process.env.WALKMAN_TEST_CHROME || 'google-chrome', [
    '--headless', '--no-sandbox', '--disable-gpu', '--no-first-run',
    '--user-data-dir=' + path.join(directory, 'profile'), '--dump-dom', 'file://' + fixture
  ], { encoding: 'utf8', timeout: 30000, maxBuffer: 2 * 1024 * 1024 });
  if (chrome.error) throw chrome.error;
  const result = (chrome.stdout.match(/<pre id="result">([^<]*)<\/pre>/) || [])[1];
  if (chrome.status !== 0 || !result || !result.startsWith('PASS:')) {
    throw new Error(result || chrome.stderr);
  }
  console.log(result);
} finally {
  fs.rmSync(directory, { recursive: true, force: true });
}
