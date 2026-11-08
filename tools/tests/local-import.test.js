const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync(require('node:path').join(__dirname, '../../app/src/main/assets/local-import.js'), 'utf8');
function setup(receive) {
    const elements = {'local-banner': {style: {}}, 'local-status': {style: {}, textContent: ''}};
    const context = {document: {getElementById: id => elements[id]}, console: {error() {}}, onAndroidLocalTracks: receive};
    context.window = context;
    vm.runInNewContext(script, context);
    return {context, elements};
}
let received;
const {context, elements} = setup(tracks => {received = tracks;});
const tracks = [{Type: 'Audio', Name: 'Song'}, {Type: 'Playlist'}];
let refreshes = 0;
context.embyConfig = {serverType: 'local'};
context.refreshFeaturedImages = () => {refreshes++;};
context.onAndroidLocalTracks(tracks);
assert.equal(refreshes, 1, 'Import must rebuild the local album slideshow');
assert.equal(received, tracks);
assert.equal(elements['local-banner'].style.display, 'none');
assert.equal(elements['local-status'].style.display, 'none');
context.onAndroidLocalTracks([]);
assert.match(elements['local-status'].textContent, /No se encontraron/);
context.onAndroidLocalError('Acceso denegado');
assert.equal(elements['local-status'].textContent, 'Acceso denegado');
assert.equal(elements['local-banner'].style.display, 'none');
context.onAndroidLocalCancelled();
assert.equal(elements['local-status'].style.display, 'none');
const failed = setup(() => {throw new Error('Render failed');});
failed.context.onAndroidLocalTracks(tracks);
assert.match(failed.elements['local-status'].textContent, /No se pudo cargar/);
assert.equal(failed.elements['local-banner'].style.display, 'none');
const missing = setup(undefined);
missing.context.onAndroidLocalTracks(tracks);
assert.match(missing.elements['local-status'].textContent, /No se pudo cargar/);
console.log('PASS: local import success, cancellation, empty folder and native/web errors finish loading');
const cached = [{Id: 'song', Type: 'Audio', Name: 'Cached song', IsLocal: true,
    Path: 'http://127.0.0.1:9001/song', objUrl: 'http://127.0.0.1:9001/song',
    coverUrl: 'http://127.0.0.1:9001/cover', MediaSources: [{SampleRate: 96000}]}];
const restored = setup(() => {});
Object.assign(restored.context, {embyConfig: {serverType: 'local'}, localTracks: [], playlist: [],
    refreshFeaturedImages() {refreshes++;},
    originalPlaylist: [], currentTrackIndex: 0, restoreQueue() {
        restored.context.playlist = [{Id: 'song', IsLocal: true, objUrl: 'http://127.0.0.1:8001/song'},
            {Id: 'removed', IsLocal: true}];
        restored.context.originalPlaylist = [...restored.context.playlist];
    }});
assert.equal(restored.context.wmRestoreAndroidLocalTracks(cached), true);
assert.equal(refreshes, 2, 'Restoring the cache must rebuild the local album slideshow');
assert.equal(restored.context.playlist.length, 1);
assert.equal(restored.context.playlist[0].objUrl, cached[0].objUrl);
assert.equal(restored.context.originalPlaylist[0].coverUrl, cached[0].coverUrl);
assert.equal(restored.context.playlist[0].MediaSources[0].SampleRate, 96000);
const remote = setup(() => {throw new Error('Remote server must not be replaced');});
Object.assign(remote.context, {embyConfig: {serverType: 'emby', host: 'server', token: 'saved'}, localTracks: []});
remote.context.refreshFeaturedImages = () => {throw new Error('Do not refresh local slides on a remote server');};
assert.equal(remote.context.wmRestoreAndroidLocalTracks(cached), false);
assert.equal(remote.context.embyConfig.serverType, 'emby');
assert.equal(remote.context.embyConfig.token, 'saved');
assert.equal(remote.context.localTracks, cached);
console.log('PASS: cached local queue URLs and quality restored, missing tracks removed, remote server preserved');
