(function () {
    if (window.__wmSonyAudio || !window.SonyAudio || !SonyAudio.isAvailable()) return;
    // Keep the web decoder and its Web Audio equalizer together when Sony is absent.
    if (!SonyAudio.isEqualizerInstalled()) return;
    var audio = document.getElementById('audio-player');
    if (!audio) return;
    window.__wmSonyAudio = true;
    var nativeState = JSON.parse(SonyAudio.getState());
    var version = Number(nativeState.version) || 0;
    var source = audio.getAttribute('src') || '';
    var volume = audio.volume, muted = audio.muted, requestedPlay = false;
    var state = { playing: false, ready: false, ended: false, position: 0, duration: 0, error: '' };
    var spectrum = new Uint8Array(128), pending = [], seeking = false;
    var originalPause = audio.pause.bind(audio), originalLoad = audio.load.bind(audio);
    var getAttribute = audio.getAttribute.bind(audio), setAttribute = audio.setAttribute.bind(audio);
    var removeAttribute = audio.removeAttribute.bind(audio);
    originalPause();
    removeAttribute('src');
    originalLoad();
    function event(name) { audio.dispatchEvent(new Event(name)); }
    function settle(error) {
        var waiters = pending.splice(0);
        waiters.forEach(function (promise) { if (error) promise.reject(error); else promise.resolve(); });
    }
    function load(value) {
        settle(new DOMException('La pista ha cambiado', 'AbortError'));
        source = value ? new URL(String(value), document.baseURI).href : '';
        requestedPlay = false;
        seeking = false;
        state = { playing: false, ready: false, ended: false, position: 0, duration: 0, error: '' };
        spectrum.fill(0);
        SonyAudio.load(source, ++version);
        event('emptied'); event('loadstart');
    }
    function property(name, get, set) {
        Object.defineProperty(audio, name, { configurable: true, get: get, set: set });
    }
    property('src', function () { return source; }, load);
    property('currentSrc', function () { return source; });
    property('paused', function () { return !(state.playing || state.loading || requestedPlay); });
    property('ended', function () { return !!state.ended; });
    property('duration', function () { return state.ready ? Number(state.duration) : NaN; });
    property('currentTime', function () { return Number(state.position) || 0; }, function (value) {
        value = Number(value);
        if (!Number.isFinite(value)) return;
        state.position = Math.max(0, value); state.ended = false;
        seeking = true;
        SonyAudio.seek(state.position); event('seeking');
    });
    property('readyState', function () { return state.ready ? 4 : 0; });
    property('networkState', function () { return !source ? 0 : state.ready ? 1 : 2; });
    property('error', function () { return state.error ? { code: 4, message: state.error } : null; });
    property('volume', function () { return volume; }, function (value) {
        value = Number(value);
        if (!Number.isFinite(value) || value < 0 || value > 1) throw new DOMException('Volumen inválido', 'IndexSizeError');
        volume = value; SonyAudio.setVolume(muted ? 0 : volume); event('volumechange');
    });
    property('muted', function () { return muted; }, function (value) {
        muted = !!value; SonyAudio.setVolume(muted ? 0 : volume); event('volumechange');
    });
    audio.getAttribute = function (name) { return String(name).toLowerCase() === 'src' ? source || null : getAttribute(name); };
    audio.setAttribute = function (name, value) { if (String(name).toLowerCase() === 'src') load(value); else setAttribute(name, value); };
    audio.removeAttribute = function (name) { if (String(name).toLowerCase() === 'src') load(''); else removeAttribute(name); };
    audio.load = function () { load(source); };
    audio.play = function () {
        if (!source) return Promise.reject(new DOMException('No hay ninguna pista cargada', 'NotSupportedError'));
        if (state.playing) return Promise.resolve();
        if (state.error) load(source);
        var wasPaused = audio.paused;
        requestedPlay = true; state.ended = false;
        var promise = new Promise(function (resolve, reject) { pending.push({ resolve: resolve, reject: reject }); });
        SonyAudio.play();
        if (wasPaused) event('play');
        return promise;
    };
    audio.pause = function () {
        var wasPaused = audio.paused;
        requestedPlay = false; state.playing = state.loading = false;
        SonyAudio.pause();
        settle(new DOMException('Reproducción pausada', 'AbortError'));
        if (!wasPaused) event('pause');
    };
    window.wmSonyAudioState = function (next) {
        if (Number(next.version) !== version) return;
        var wasPaused = audio.paused, wasReady = state.ready, wasEnded = state.ended;
        var wasPlaying = state.playing, previousPosition = state.position, previousError = state.error;
        state = next;
        requestedPlay = !!(next.playing || next.loading);
        if (next.spectrum) {
            var bytes = atob(next.spectrum);
            for (var i = 0; i < spectrum.length; i++) spectrum[i] = bytes.charCodeAt(i) || 0;
        } else spectrum.fill(0);
        if (!wasReady && next.ready) {
            event('durationchange'); event('loadedmetadata'); event('loadeddata'); event('canplay');
        }
        if (wasPaused && !audio.paused) event('play');
        if (next.playing) {
            settle();
            if (!wasPlaying) event('playing');
        }
        if (!wasPaused && audio.paused) event('pause');
        if (previousPosition !== next.position || next.playing) event('timeupdate');
        if (seeking && next.ready) { seeking = false; event('seeked'); }
        if (next.error && next.error !== previousError) {
            settle(new DOMException(next.error, 'NotSupportedError')); event('error');
        }
        if (next.ended && !wasEnded) event('ended');
    };
    // Preserve the web visualizers, reading the real PCM spectrum after Sony DSP.
    var analyser = {
        fftSize: 256,
        get frequencyBinCount() { return this.fftSize / 2; },
        getByteFrequencyData: function (target) {
            for (var i = 0; i < target.length; i++) target[i] = audio.paused ? 0 : spectrum[Math.min(127, Math.floor(i * 128 / target.length))];
        }
    };
    var clock = { state: 'running', resume: function () { return Promise.resolve(); },
        get currentTime() { return audio.currentTime; }, get sampleRate() { return state.sampleRate || 48000; } };
    if (typeof audioCtx !== 'undefined' && audioCtx && typeof audioCtx.suspend === 'function') audioCtx.suspend();
    window.initAudioContext = function () {
        if (typeof audioCtx !== 'undefined') audioCtx = clock;
        if (typeof analyserNode !== 'undefined') analyserNode = analyser;
    };
    window.initAudioContext();
    if (typeof window.toggleEqualizer === 'function') window.toggleEqualizer = function () { SonyAudio.openEqualizer(); };
    var eqButton = document.getElementById('eq-btn');
    if (eqButton) { eqButton.title = 'Sony Equalizer · Clear Bass · VPT'; eqButton.setAttribute('aria-label', eqButton.title); }
    SonyAudio.setVolume(muted ? 0 : volume);
    load(source);
    document.documentElement.dataset.audioEngine = 'sony';
})();
