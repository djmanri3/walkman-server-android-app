(function () {
    if (window.__wmAndroidPlaybackInstalled) return;
    window.__wmAndroidPlaybackInstalled = true;

    function repairTitle() {
        var title = document.getElementById('track-title');
        if (!title || document.getElementById('track-title-text')) return;
        // Algunas versiones restauran la cola asignando textContent al padre
        // y eliminan el span que playTrack necesita antes de cargar el audio.
        var text = document.createElement('span');
        text.id = 'track-title-text';
        text.textContent = title.textContent;
        title.textContent = '';
        title.appendChild(text);
    }
    repairTitle();
    if (typeof window.playTrack === 'function') {
        var playTrack = window.playTrack;
        window.playTrack = function () {
            repairTitle();
            return playTrack.apply(this, arguments);
        };
    }

    window.wmAndroidTransport = function (action) {
        var audio = document.getElementById('audio-player');
        if (!audio) return false;
        repairTitle();
        if (action === 'pause') {
            if (!audio.paused && typeof window.togglePlay === 'function') window.togglePlay();
            return true;
        }
        if (action !== 'play') return false;
        if (!audio.paused) return true;
        if (!audio.getAttribute('src') && !audio.currentSrc) {
            // La cola se restaura sin audio.src. togglePlay por sí solo no inicia nada.
            if (typeof playlist === 'undefined' || !playlist.length || typeof window.playTrack !== 'function') return false;
            var index = typeof currentTrackIndex === 'number' && currentTrackIndex >= 0 && currentTrackIndex < playlist.length
                ? currentTrackIndex : 0;
            window.playTrack(index, true);
            return true;
        }
        if (typeof window.togglePlay !== 'function') return false;
        window.togglePlay();
        return true;
    };
})();
