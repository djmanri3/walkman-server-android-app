(function () {
    if (window.__wmLocalImport) return;
    window.__wmLocalImport = true;
    function finish(message) {
        if (typeof window.hideLocalImportBanner === 'function') window.hideLocalImportBanner();
        var banner = document.getElementById('local-banner');
        if (banner) banner.style.display = 'none';
        var status = document.getElementById('local-status');
        if (status) {
            status.textContent = message || '';
            status.style.display = message ? 'block' : 'none';
        }
    }
    window.onAndroidLocalError = function (message) {
        finish(message || 'No se pudo cargar la carpeta de música. Inténtalo de nuevo.');
    };
    window.onAndroidLocalCancelled = function () { finish(''); };
    function refreshLocalSlideshow() {
        if (typeof embyConfig === 'undefined' || embyConfig.serverType !== 'local') return;
        if (typeof window.refreshFeaturedImages !== 'function') return;
        Promise.resolve(window.refreshFeaturedImages()).catch(function (error) {
            console.error('No se pudo actualizar el slideshow local', error);
        });
    }
    var receive = window.onAndroidLocalTracks;
    window.onAndroidLocalTracks = function (tracks) {
        if (!Array.isArray(tracks) || !tracks.some(function (t) { return t.Type === 'Audio'; })) {
            finish('No se encontraron archivos de audio en la carpeta.');
            return;
        }
        try {
            if (typeof receive !== 'function') throw new Error('Missing local music callback');
            receive.apply(this, arguments);
            refreshLocalSlideshow();
            finish('');
        } catch (error) {
            console.error('No se pudo completar la importación local', error);
            finish('No se pudo cargar la biblioteca local. Vuelve a seleccionar la carpeta.');
        }
    };
    window.wmAndroidLocalRestoreError = function () {
        if (typeof embyConfig !== 'undefined' && embyConfig.serverType === 'local') {
            finish('No se pudo abrir la biblioteca guardada. Vuelve a seleccionar la carpeta de música.');
        }
    };
    window.wmRestoreAndroidLocalTracks = function (tracks) {
        if (!Array.isArray(tracks) || !tracks.length) return false;
        // Keep a selected remote server and its credentials intact.
        if (typeof embyConfig === 'undefined' || embyConfig.serverType !== 'local') {
            if (typeof localTracks !== 'undefined') localTracks = tracks;
            return false;
        }
        if (typeof localTracks !== 'undefined') {
            localTracks = tracks.slice().sort(function (a, b) { return (a.Name || '').localeCompare(b.Name || ''); });
        }
        if (typeof localRootName !== 'undefined') localRootName = tracks[0].Album || 'Local';
        if (typeof window.updateLocalCounts === 'function') window.updateLocalCounts();
        if (typeof window.switchServerType === 'function') window.switchServerType('local');
        refreshLocalSlideshow();
        var modal = document.getElementById('emby-modal');
        if (modal) modal.style.display = 'none';
        finish('');
        // The web skips restoreQueue() when local folders need authorizing again.
        if (typeof window.restoreQueue === 'function') window.restoreQueue();
        var byId = Object.create(null);
        tracks.forEach(function (track) { byId[track.Id] = track; });
        function refresh(queue) {
            return queue.filter(function (track) { return !track.IsLocal || !!byId[track.Id]; })
                .map(function (track) { return track.IsLocal ? Object.assign({}, track, byId[track.Id]) : track; });
        }
        if (typeof playlist !== 'undefined') {
            var selected = typeof currentTrackIndex === 'number' && playlist[currentTrackIndex];
            playlist = refresh(playlist);
            if (typeof currentTrackIndex !== 'undefined') {
                var index = selected ? playlist.findIndex(function (t) { return t.Id === selected.Id; }) : -1;
                currentTrackIndex = index >= 0 ? index : 0;
            }
            if (typeof originalPlaylist !== 'undefined') originalPlaylist = refresh(originalPlaylist);
            var current = playlist[typeof currentTrackIndex === 'number' ? currentTrackIndex : 0];
            if (current) {
                var title = document.getElementById('track-title-text') || document.getElementById('track-title');
                var artist = document.getElementById('track-artist');
                if (title) title.textContent = current.Name || '';
                if (artist) artist.textContent = (current.Artists || []).join(', ') || current.AlbumArtist || '';
            }
            if (typeof window.renderQueueList === 'function') window.renderQueueList();
            if (typeof window.refreshCarouselImages === 'function') window.refreshCarouselImages();
            if (typeof window.applyPlayingHiResState === 'function') window.applyPlayingHiResState();
            if (typeof window.saveQueue === 'function') window.saveQueue();
        }
        return true;
    };
})();
