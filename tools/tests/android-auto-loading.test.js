// Run: node tools/tests/android-auto-loading.test.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const java = fs.readFileSync(path.join(__dirname, '../../app/src/main/java/com/djmanri3/Walkman/MainActivity.java'), 'utf8');
const script = java.match(/private static final String LIBRARY_SCRIPT = """\n([\s\S]*?)\n\s*""";/)[1];
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve(); };
function setup(fetch, configure = () => {}) {
  const pushes = [], calls = [];
  const context = {
    embyConfig: { serverType: 'emby', host: 'test', token: 'test-token', libraryId: 'one' },
    SERVER_TYPES: { emby: { Album: 'Album', Playlist: 'Playlist', Artist: 'Artist', Audio: 'Audio' } },
    availableLibraries: [], document: { readyState: 'complete' },
    setInterval: () => 1, clearInterval: () => {},
    getImageUrl: item => 'https://test/' + (item.AlbumId || item.Id),
    fetchItems: (...args) => { calls.push(args); return fetch(...args); },
    AndroidBridge: Object.fromEntries(['pushAlbums', 'pushPlaylists', 'pushArtists', 'pushLibraries']
      .map(method => [method, json => pushes.push({ method, items: JSON.parse(json) })]))
  };
  context.window = context;
  configure(context);
  vm.createContext(context);
  vm.runInContext(script, context);
  return { context, pushes, calls };
}
(async () => {
  // Real startup regression: default empty config must not overwrite a warm
  // native cache. Restore the saved session once, then publish real categories.
  const restore = deferred();
  let restorationCalls = 0;
  const startup = setup(() => Promise.resolve({ Items: [{ Id: 'restored', Name: 'Restored', ImageTags: { Primary: 'yes' } }] }), context => {
    const saved = { ...context.embyConfig };
    context.embyConfig = { serverType: 'emby', host: '', token: '', libraryId: '' };
    context.localStorage = { getItem: () => JSON.stringify(saved) };
    context.restoreServerConfig = () => {
      restorationCalls++;
      return restore.promise.then(() => { context.embyConfig = saved; return true; });
    };
  });
  for (let i = 0; i < 20; i++) startup.context.wmLibraryPush();
  await flush();
  assert.equal(restorationCalls, 1);
  assert.equal(startup.calls.length, 0);
  assert.equal(startup.pushes.length, 0, 'Unrestored connection must not erase the native library');
  restore.resolve();
  assert.equal(await startup.context.wmLibraryPush(), true);
  assert(startup.pushes.some(p => p.method === 'pushAlbums' && p.items[0].id === 'restored'));
  assert(startup.pushes.some(p => p.method === 'pushPlaylists' && p.items[0].id === 'restored'));

  const disconnected = setup(() => { throw new Error('Must not fetch without credentials'); }, context => {
    context.embyConfig = { serverType: 'emby' };
    context.localStorage = { getItem: () => null };
  });
  await flush();
  assert.equal(disconnected.context.wmLibraryPush(), false);
  assert.equal(disconnected.pushes.length, 0);

  // A slow artist response must not block albums or playlists. Repeated refreshes
  // during startup must not multiply the requests.
  const albums = deferred(), artists = deferred();
  const first = setup(type => type === 'Album' ? albums.promise : type === 'Artist'
    ? artists.promise : Promise.resolve({ Items: [] }));
  for (let i = 0; i < 20; i++) first.context.wmLibraryPush();
  await flush();
  assert.equal(first.calls.length, 3);
  assert(first.pushes.some(p => p.method === 'pushPlaylists' && p.items.length === 0));
  assert(!first.pushes.some(p => p.method === 'pushArtists'));
  albums.resolve({ Items: [{ Id: 'album', Name: 'Album', ImageTags: { Primary: 'yes' } }] });
  await flush();
  assert(first.pushes.some(p => p.method === 'pushAlbums'));
  assert(!first.pushes.some(p => p.method === 'pushArtists'));
  artists.resolve({ Items: [{ Id: 'artist', Name: 'Artist' }] });
  await first.context.wmLibraryPush();
  const before = first.pushes.length;
  await first.context.wmLibraryPush();
  assert.equal(first.calls.length, 3);
  assert.equal(first.pushes.length, before, 'Unchanged categories must not reset scrolling');

  // Album art is optional during browsing; cap parallel cover fetches at four.
  const coverRequests = [];
  const covers = setup((type, id) => {
    if (type === 'Audio') { const response = deferred(); coverRequests.push({ id, response }); return response.promise; }
    return Promise.resolve({ Items: type === 'Album' ? Array.from({ length: 12 }, (_, i) => ({ Id: '' + i, Name: 'Album ' + i })) : [] });
  });
  assert.equal(await covers.context.wmLibraryPush(), true, 'Catalog must load before covers finish');
  await flush();
  assert.equal(coverRequests.length, 4);
  assert(covers.pushes.some(p => p.method === 'pushAlbums' && p.items.length === 12));
  for (let i = 0; i < 12; i++) {
    assert(coverRequests[i], 'Cover workers must continue after completion');
    coverRequests[i].response.resolve({ Items: [{ Id: 'track-' + coverRequests[i].id }] });
    await flush();
    assert(coverRequests.length <= Math.min(12, i + 5), 'Too many simultaneous cover requests');
  }
  assert(covers.pushes.filter(p => p.method === 'pushAlbums').at(-1).items.every(item => item.imageUrl.includes('track-')));

  const coverPushes = covers.pushes.length;
  vm.runInContext('Date.now = () => 2000000000000', covers.context);
  await covers.context.wmLibraryPush();
  await flush();
  assert.equal(coverRequests.length, 12, 'Reuse resolved covers on later refreshes');
  assert.equal(covers.pushes.length, coverPushes, 'Cached artwork must not flicker or reset the list');

  // A failed category must not erase its cache or restart successful categories.
  let failed = true;
  const retry = setup(type => type === 'Artist' && failed ? Promise.reject(new Error('offline')) : Promise.resolve({ Items: [] }));
  await flush();
  assert(!retry.pushes.some(p => p.method === 'pushArtists'));
  failed = false;
  assert.equal(await retry.context.wmLibraryPush(), true);
  assert.equal(retry.calls.filter(c => c[0] === 'Album').length, 1);
  assert.equal(retry.calls.filter(c => c[0] === 'Artist').length, 2);

  // Late results from the previously selected library must never replace new data.
  const old = deferred();
  let active;
  active = setup(type => {
    if (type === 'Album' && (!active || active.context.embyConfig.libraryId === 'one')) return old.promise;
    return Promise.resolve({ Items: type === 'Album' ? [{ Id: 'new', Name: 'New', ImageTags: { Primary: 'yes' } }] : [] });
  });
  await flush();
  await active.context.wmSelectLibrary('two');
  old.resolve({ Items: [{ Id: 'old', Name: 'Old' }] });
  await flush();
  assert.deepEqual(active.pushes.filter(p => p.method === 'pushAlbums').map(p => p.items[0].id), ['new']);
  console.log('PASS: independent categories, shared requests, empty lists, cached refresh, bounded covers, retries and library changes');
})().catch(error => { console.error(error); process.exitCode = 1; });
