package com.djmanri3.Walkman;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Actividad principal que muestra la web WALKMAN en un WebView e inyecta el
 * puente {@link AndroidBridge} para sincronizar el estado con el sistema
 * multimedia de Android. También escucha los comandos del sistema
 * (widget/notificación/bluetooth) y los traduce a llamadas JavaScript.
 */
public class MainActivity extends Activity {

    private static final int REQ_FOLDER = 5001;

    /** Extra con la consulta de voz recibida vía VoiceSearchActivity. */
    static final String EXTRA_VOICE_QUERY = "com.djmanri3.Walkman.voice_query";

    /**
     * Script inyectado para extraer la biblioteca (álbumes, playlists y pistas)
     * de la web WALKMAN y enviarla a Android Auto a través de AndroidBridge.
     *
     * La web usa globals de nivel superior: fetchItems(), embyConfig,
     * SERVER_TYPES, localTracks, playlist, playTrack(), toggleShuffle(),
     * toggleRepeat(), renderQueueList(), refreshCarouselImages(), switchTab().
     * Cada 'let'/const de nivel superior es alcanzable por nombre desde este
     * script inyectado (ámbito léxico global).
     */
    private static final String LIBRARY_SCRIPT = """
            (function(){
            if(window.__wmAAready)return;
            window.__wmAAready=true;
            function isStreaming(){ try{ return !!embyConfig && !!SERVER_TYPES && !!embyConfig.serverType && embyConfig.serverType!=='local'; }catch(e){ return false; } }
            function getSt(){ try{ return SERVER_TYPES[embyConfig.serverType]||null; }catch(e){ return null; } }
            function rawImg(it){ try{ if(typeof getImageUrl==='function'){ return getImageUrl(it)||''; } }catch(e){ return ''; } return ''; }
            function aaImg(it){
              var u=rawImg(it);
              if(!u||u.indexOf('data:')===0||u.indexOf('blob:')===0)return '';
              if(u.indexOf('/Images/Primary')!==-1){
                u+=(u.indexOf('?')===-1)?'?':'&';
                u+='maxWidth=512&quality=80';
              }
              return u;
            }
            function pushLibraryParts(A,P,R){
              if(window.AndroidBridge){
                try{ AndroidBridge.pushAlbums(JSON.stringify(A)); }catch(e){}
                try{ AndroidBridge.pushPlaylists(JSON.stringify(P)); }catch(e){}
                try{ AndroidBridge.pushArtists(JSON.stringify(R)); }catch(e){}
              }
            }
            function wmAALibraries(){
              // Bibliotecas de música del servidor conectado (Emby/Jellyfin/Plex).
              var out=[];
              var st=null; try{ st=embyConfig.serverType||null; }catch(e){}
              var libs=(typeof availableLibraries!=='undefined'&&Array.isArray(availableLibraries))?availableLibraries:[];
              var cur=''; try{ cur=embyConfig.libraryId!=null?String(embyConfig.libraryId):''; }catch(e){}
              libs.forEach(function(l){
                var lid=l.Id!=null?String(l.Id):(l.key!=null?String(l.key):'');
                var name=l.Name||l.title||'';
                if(!lid||!name)return;
                var t=l.CollectionType||l.type||'';
                if(st==='plex'){ if(l.type&&l.type!=='artist')return; }
                else if(t&&t.toLowerCase()!=='music')return;
                out.push({id:lid,name:name,current:(!!cur&&cur===lid)});
              });
              try{
                out.sort(function(a,b){ return a.name.localeCompare(b.name); });
              }catch(e){}
              return out;
            }
            function pushLibrariesPart(){
              if(!window.AndroidBridge)return;
              try{ AndroidBridge.pushLibraries(JSON.stringify(wmAALibraries())); }catch(e){}
            }
            function pushLocal(){
              var A=[],P=[],R=[];
              if(Array.isArray(localTracks)){
                var map={}, artMap={};
                localTracks.forEach(function(t){
                  if(t.Type==='Playlist'){ P.push({id:t.Id,name:t.Name||'',imageUrl:t.coverUrl||''}); return; }
                  var key=t.Album||'Desconocido';
                  if(!map[key]){ map[key]={id:'local_album_'+key,name:key,artist:t.AlbumArtist||((t.Artists&&t.Artists[0])||''),imageUrl:t.coverUrl||''}; }
                  else if(!map[key].imageUrl&&t.coverUrl){ map[key].imageUrl=t.coverUrl; }
                  var art=(t.Artists&&t.Artists[0])||'Desconocido';
                  if(!artMap[art]){ artMap[art]={id:'local_artist_'+art,name:art,artist:'',imageUrl:t.coverUrl||''}; }
                  else if(!artMap[art].imageUrl&&t.coverUrl){ artMap[art].imageUrl=t.coverUrl; }
                });
                Object.keys(map).forEach(function(k){ A.push(map[k]); });
                Object.keys(artMap).forEach(function(k){ R.push(artMap[k]); });
              }
              pushLibraryParts(A,P,R);
              return true;
            }
            function pushStreaming(){
              var st=getSt(); if(!st) return false;
              var qA=fetchItems(st.Album).then(function(r){return r;}).catch(function(){return null;});
              var qP=fetchItems(st.Playlist).then(function(r){return r;}).catch(function(){return null;});
              var qR=(typeof st.Artist==='undefined'||st.Artist===null)
                ?Promise.resolve(null)
                :fetchItems(st.Artist).then(function(r){return r;}).catch(function(){return null;});
              return Promise.all([qA,qP,qR]).then(function(rs){
                // Corrige el AlbumId de cada álbum: muchos álbumes Emby/Jellyfin
                // reportan imagen en su Id pero 404 al pedirla; la portada real
                // está en su primera canción. resolveAlbumCoverIds asigna a
                // item.AlbumId el Id de la primera pista (que sí trae Primary),
                // de modo que getImageUrl(item) devuelva una URL que responde.
                var A=[],P=[],R=[];
                var albums=rs[0]?rs[0].Items||[]:[];
                if(typeof resolveAlbumCoverIds==='function'){
                  try{ return resolveAlbumCoverIds(albums).then(function(){
                    return buildParts(albums); }); }catch(e){}
                }
                return buildParts(albums);
                function buildParts(albums){
                  A=albums.map(function(it){
                    return {id:it.Id,name:it.Name||'',artist:it.AlbumArtist||((it.Artists&&it.Artists[0])||''),imageUrl:aaImg(it)};
                  });
                  if(rs[1]&&rs[1].Items){
                    rs[1].Items.forEach(function(it){ P.push({id:it.Id,name:it.Name||'',imageUrl:aaImg(it)}); });
                  }
                  if(rs[2]&&rs[2].Items){
                    rs[2].Items.forEach(function(it){ R.push({id:it.Id,name:it.Name||'',imageUrl:aaImg(it)}); });
                  }
                  pushLibraryParts(A,P,R);
                  return true;
                }
              }).catch(function(){ return false; });
            }
            window.wmLibraryPush=function(){
              pushLibrariesPart();
              try{ return isStreaming()?pushStreaming():pushLocal(); }catch(e){ return false; }
            };
            window.wmSelectLibrary=function(libId){
              var id=String(libId||'');
              if(!id)return false;
              try{
                embyConfig.libraryId=id;
                if(typeof saveServerSettings==='function'){ try{ saveServerSettings(); }catch(e){} }
                if(typeof updateCategoryCounts==='function'){ try{ updateCategoryCounts(); }catch(e){} }
                // Empuja la biblioteca del servidor elegido recién seleccionado.
                return window.wmLibraryPush();
              }catch(e){ return false; }
            };
            window.wmPlayShuffle=function(){
              // "Aleatorio de todas las canciones" (Android Auto). Igual que el
              // botón de la web (playShuffleAll): carga todas las pistas de la
              // biblioteca y reproduce en orden aleatorio desde la 0.
              try{
                if(isStreaming()){
                  var st=getSt();
                  return Promise.resolve(fetchItems(st.Audio)).then(function(r){
                    var items=r&&r.Items||[];
                    if(!items.length)return false;
                    if(typeof playShuffleAll==='function'){ playShuffleAll(items); try{ if(typeof wmQueueFor==='function'){ wmQueueFor(); } }catch(e2){} return true; }
                    return false;
                  }).catch(function(){ return false; });
                }
                var arr=[];
                if(Array.isArray(localTracks)){
                  localTracks.forEach(function(t){ if(t.Type!=='Playlist'){ arr.push(t); } });
                }
                if(!arr.length)return false;
                if(typeof playShuffleAll==='function'){ playShuffleAll(arr); try{ if(typeof wmQueueFor==='function'){ wmQueueFor(); } }catch(e2){} return true; }
                return false;
              }catch(e){ return false; }
            };
            window.wmQueueFor=function(){
              // Cola de reproducción actual (Android Auto): lista las pistas en el
              // orden en que suenan y marca la que está en curso. Al pulsar una
              // pista se reproduce desde ahí (playTrack(index)).
              try{
                var arr=[];
                if(Array.isArray(playlist)){
                  var cur=currentTrackIndex||0;
                  playlist.forEach(function(t,i){
                    var img='';
                    try{ img=isStreaming()?aaImg(t):(t&&t.coverUrl?t.coverUrl:''); }catch(e){ img=''; }
                    arr.push({index:i,name:t.Name||'',artist:t.AlbumArtist||((t.Artists&&t.Artists[0])||''),imageUrl:img,current:(i===cur)});
                  });
                }
                if(window.AndroidBridge){ try{ AndroidBridge.pushQueue(JSON.stringify(arr)); }catch(e){} }
                return true;
              }catch(e){ return false; }
            };
            window.wmTracksFor=function(containerId,kind){
              try{
                if(isStreaming()){
                  var st=getSt();
                  return Promise.resolve(fetchItems(st.Audio, containerId, kind==='playlist'?'Playlist':'MusicAlbum'))
                  .then(function(r){
                    var arr=(r&&r.Items||[]).map(function(t){
                      return {id:t.Id,name:t.Name||'',artist:t.AlbumArtist||((t.Artists&&t.Artists[0])||''),imageUrl:aaImg(t),duration:(t.Duration||0)*1000};
                    });
                    if(window.AndroidBridge){ try{ AndroidBridge.pushTracks(containerId,JSON.stringify(arr)); }catch(e){} }
                    return true;
                  }).catch(function(){ return false; });
                }
                var arr=[];
                if(kind==='playlist'){
                  var pl=localTracks.find(function(t){ return t.Type==='Playlist'&&String(t.Id)===String(containerId); });
                  if(pl&&Array.isArray(pl.PlaylistIds)){
                    var byId={}; localTracks.forEach(function(t){ byId[String(t.Id)]=t; });
                    arr=pl.PlaylistIds.map(function(id){ return byId[String(id)]; }).filter(Boolean);
                  }
                }else{
                  var key=containerId;
                  if(key.indexOf('local_album_')===0){ key=key.substring(11); }
                  arr=localTracks.filter(function(t){ return t.Type!=='Playlist'&&String(t.Album)===String(key); });
                }
                var out=arr.map(function(t){ return {id:t.Id,name:t.Name||'',artist:t.AlbumArtist||((t.Artists&&t.Artists[0])||''),imageUrl:t.coverUrl||'',duration:(t.Duration||0)*1000}; });
                if(window.AndroidBridge){ try{ AndroidBridge.pushTracks(containerId,JSON.stringify(out)); }catch(e){} }
                return true;
              }catch(e){ return false; }
            };
            window.wmArtistAlbums=function(artistId,name){
              try{
                if(isStreaming()){
                  var st=getSt();
                  // Igual que la web: las canciones del artista salen con
                  // fetchItems(Audio, artistId) — no hay nivel "álbumes" intermedio.
                  return Promise.resolve(fetchItems(st.Audio, artistId, 'MusicArtist'))
                  .then(function(r){
                    var arr=(r&&r.Items||[]).map(function(t){
                      return {id:t.Id,name:t.Name||'',artist:t.AlbumArtist||((t.Artists&&t.Artists[0])||''),imageUrl:aaImg(t),duration:(t.Duration||0)*1000};
                    });
                    if(window.AndroidBridge){ try{ AndroidBridge.pushArtistAlbums(artistId,JSON.stringify(arr)); }catch(e){} }
                    return true;
                  }).catch(function(){ return false; });
                }
                var nm=name||'';
                if(artistId.indexOf('local_artist_')===0){ nm=artistId.substring(13); }
                var out=[];
                if(Array.isArray(localTracks)){
                  localTracks.forEach(function(t){
                    if(t.Type==='Playlist')return;
                    if((t.Artists&&t.Artists[0])!==nm)return;
                    out.push({id:t.Id,name:t.Name||'',artist:t.AlbumArtist||t.Artists[0],imageUrl:t.coverUrl||'',duration:(t.Duration||0)*1000});
                  });
                }
                if(window.AndroidBridge){ try{ AndroidBridge.pushArtistAlbums(artistId,JSON.stringify(out)); }catch(e){} }
                return true;
              }catch(e){ return false; }
            };
            window.wmPlayContainer=function(containerId,kind,title,trackId){
              return window.__wmLoadTracks(containerId,kind).then(function(tracks){
                if(!tracks||!tracks.length){
                  try{ if(window.AndroidBridge){ AndroidBridge.setMediaState(JSON.stringify({title:title||'Lista vacía',artist:'',album:'',artwork:'',playing:false,position:0,duration:0})); } }catch(e2){}
                  return false;
                }
                try{
                  playlist=tracks.slice();
                  originalPlaylist=tracks.slice();
                  playlistVersion++;
                  if(typeof renderQueueList==='function'){ try{ renderQueueList(); }catch(e3){} }
                  if(typeof refreshCarouselImages==='function'){ try{ refreshCarouselImages(); }catch(e4){} }
                  var idx=0;
                  if(trackId){ for(var i=0;i<tracks.length;i++){ if(String(tracks[i].Id)===String(trackId)){ idx=i; break; } } }
                  if(typeof playTrack==='function'){ playTrack(idx); }
                  if(typeof switchTab==='function'){ try{ switchTab('playing'); }catch(e5){} }
                  try{ if(typeof wmQueueFor==='function'){ wmQueueFor(); } }catch(e6b){}
                  return true;
                }catch(e6){ return false; }
              });
            };
            window.wmPlayArtist=function(artistId,name){
              return window.__wmArtistTracks(artistId,name).then(function(tracks){
                if(!tracks||!tracks.length)return false;
                try{
                  playlist=tracks.slice();
                  originalPlaylist=tracks.slice();
                  playlistVersion++;
                  if(typeof renderQueueList==='function'){ try{ renderQueueList(); }catch(e3){} }
                  if(typeof refreshCarouselImages==='function'){ try{ refreshCarouselImages(); }catch(e4){} }
                  if(typeof playTrack==='function'){ playTrack(0); }
                  if(typeof switchTab==='function'){ try{ switchTab('playing'); }catch(e5){} }
                  try{ if(typeof wmQueueFor==='function'){ wmQueueFor(); } }catch(e6b){}
                  return true;
                }catch(e6){ return false; }
              });
            };
            window.wmSearch=function(query){
              var q=String(query||'').trim();
              if(!q) return Promise.resolve([]);
              try{
                if(isStreaming()){
                  var st=getSt();
                  if(st===SERVER_TYPES.plex){
                    var pu=embyConfig.host+'/search?query='+encodeURIComponent(q)+'&limit=40&X-Plex-Token='+encodeURIComponent(embyConfig.token);
                    return fetch(pu).then(function(r){ return r.json(); }).then(function(d){
                      var out=[]; var mc=d&&d.MediaContainer&&d.MediaContainer.Metadata||[];
                      mc.forEach(function(h){
                        var kind=null;
                        if(h.type==='track')kind='track';
                        else if(h.type==='album')kind='album';
                        else if(h.type==='artist')kind='artist';
                        else if(h.type==='playlist')kind='playlist';
                        if(!kind)return;
                        var img=(h.thumb?embyConfig.host+h.thumb:'');
                        var container=(kind==='track')?(h.parentRatingKey||''):'';
                        out.push({kind:kind,id:String(h.ratingKey||''),name:h.title||'',sub:h.artist||h.parentTitle||'',imageUrl:img,container:container});
                      });
                      if(window.AndroidBridge){ try{ AndroidBridge.pushSearchResults(q,JSON.stringify(out)); }catch(e){} }
                      return out;
                    }).catch(function(){ return []; });
                  }
                  var url=embyConfig.host+'/Items?SearchTerm='+encodeURIComponent(q)
                    +'&IncludeItemTypes=Audio,MusicAlbum,MusicArtist,Playlist&Recursive=true'
                    +'&Limit=60&UserId='+encodeURIComponent(embyConfig.userId||'')
                    +'&api_key='+encodeURIComponent(embyConfig.token);
                  return fetch(url).then(function(r){ return r.json(); }).then(function(d){
                    var out=[];
                    (d.Items||[]).forEach(function(it){
                      if(it.Type==='MusicAlbum'){ out.push({kind:'album',id:it.Id,name:it.Name||'',sub:it.AlbumArtist||((it.Artists&&it.Artists[0])||''),imageUrl:aaImg(it),container:''}); }
                      else if(it.Type==='MusicArtist'){ out.push({kind:'artist',id:it.Id,name:it.Name||'',sub:'',imageUrl:aaImg(it),container:''}); }
                      else if(it.Type==='Playlist'){ out.push({kind:'playlist',id:it.Id,name:it.Name||'',sub:'',imageUrl:aaImg(it),container:''}); }
                      else if(it.Type==='Audio'){ out.push({kind:'track',id:it.Id,name:it.Name||'',sub:it.AlbumArtist||((it.Artists&&it.Artists[0])||''),imageUrl:aaImg(it),container:it.AlbumId||''}); }
                    });
                    if(window.AndroidBridge){ try{ AndroidBridge.pushSearchResults(q,JSON.stringify(out)); }catch(e){} }
                    return out;
                  }).catch(function(){ return []; });
                }
                var out=[];
                if(Array.isArray(localTracks)){
                  var qt=q.toLowerCase(), seenA={}, seenR={};
                  localTracks.forEach(function(t){
                    if(t.Type==='Playlist'){
                      if((t.Name||'').toLowerCase().indexOf(qt)!==-1){
                        out.push({kind:'playlist',id:t.Id,name:t.Name||'',sub:'',imageUrl:t.coverUrl||'',container:''});
                      }
                      return;
                    }
                    var nm=t.Name||'', ar=(t.Artists&&t.Artists[0])||t.AlbumArtist||'', al=t.Album||'';
                    if(!seenA[al]&&al.toLowerCase().indexOf(qt)!==-1){ seenA[al]=1; out.push({kind:'album',id:'local_album_'+al,name:al,sub:ar,imageUrl:t.coverUrl||'',container:''}); }
                    if(!seenR[ar]&&ar.toLowerCase().indexOf(qt)!==-1){ seenR[ar]=1; out.push({kind:'artist',id:'local_artist_'+ar,name:ar,sub:'',imageUrl:t.coverUrl||'',container:''}); }
                    if(nm.toLowerCase().indexOf(qt)!==-1||ar.toLowerCase().indexOf(qt)!==-1){
                      out.push({kind:'track',id:t.Id,name:nm,sub:ar,imageUrl:t.coverUrl||'',container:'local_album_'+al});
                    }
                  });
                }
                if(window.AndroidBridge){ try{ AndroidBridge.pushSearchResults(q,JSON.stringify(out)); }catch(e){} }
                return Promise.resolve(out);
              }catch(e){ return Promise.resolve([]); }
            };
            window.wmPlaySearch=function(query){
              return window.wmSearch(query).then(function(list){
                try{
                  var lq=String(query||'').toLowerCase();
                  function pick(arr){ for(var i=0;i<arr.length;i++){ if((arr[i].name||'').toLowerCase()===lq)return arr[i]; } return arr[0]; }
                  var cand=[pick(list.filter(function(x){return x.kind==='artist';})),
                            pick(list.filter(function(x){return x.kind==='album';})),
                            pick(list.filter(function(x){return x.kind==='playlist';})),
                            pick(list.filter(function(x){return x.kind==='track';}))].filter(Boolean);
                  var exact=cand.filter(function(c){ return (c.name||'').toLowerCase()===lq; });
                  var pool=exact.length?exact:cand;
                  var best=pool[0]; if(!best) return false;
                  if(best.kind==='artist') return window.wmPlayArtist(best.id,best.name);
                  if(best.kind==='album') return window.wmPlayContainer(best.id,'album',best.name,'');
                  if(best.kind==='playlist') return window.wmPlayContainer(best.id,'playlist',best.name,'');
                  if(best.kind==='track'){
                    if(best.container) return window.wmPlayContainer(best.container,'album',best.name,best.id);
                    return false;
                  }
                  return false;
                }catch(e){ return false; }
              });
            };
            window.__wmLoadTracks=function(containerId,kind){
              if(isStreaming()){
                var st=getSt();
                var pk=kind==='playlist'?'Playlist':(kind==='artist'?'MusicArtist':'MusicAlbum');
                return Promise.resolve(fetchItems(st.Audio, containerId, pk))
                .catch(function(){ return null; }).then(function(r){ return (r&&r.Items)?r.Items:[]; });
              }
              return new Promise(function(res){
                if(!Array.isArray(localTracks)){ res([]); return; }
                if(kind==='playlist'){
                  var pl=localTracks.find(function(t){ return t.Type==='Playlist'&&String(t.Id)===String(containerId); });
                  if(pl&&Array.isArray(pl.PlaylistIds)){
                    var byId={}; localTracks.forEach(function(t){ byId[String(t.Id)]=t; });
                    res(pl.PlaylistIds.map(function(id){ return byId[String(id)]; }).filter(Boolean));
                  }else{ res([]); }
                }else if(kind==='artist'){
                  var nm=containerId;
                  if(nm.indexOf('local_artist_')===0){ nm=nm.substring(13); }
                  res(localTracks.filter(function(t){ return t.Type!=='Playlist'&&(t.Artists&&t.Artists[0])===nm; }));
                }else{
                  var key=containerId;
                  if(key.indexOf('local_album_')===0){ key=key.substring(11); }
                  res(localTracks.filter(function(t){ return t.Type!=='Playlist'&&String(t.Album)===String(key); }));
                }
              });
            };
            window.__wmArtistTracks=function(artistId,name){
              if(isStreaming()){
                var st=getSt();
                return Promise.resolve(fetchItems(st.Album, artistId, 'MusicArtist'))
                .catch(function(){ return null; }).then(function(r){
                  var albums=(r&&r.Items)?r.Items:[];
                  if(!albums.length)return [];
                  return Promise.all(albums.map(function(al){
                    return fetchItems(st.Audio, al.Id, 'MusicAlbum')
                      .then(function(r2){ return (r2&&r2.Items)?r2.Items:[]; })
                      .catch(function(){ return []; });
                  })).then(function(lists){
                    var all=[];
                    lists.forEach(function(l){ all=all.concat(l); });
                    return all;
                  });
                }).catch(function(){ return []; });
              }
              return new Promise(function(res){
                if(!Array.isArray(localTracks)){ res([]); return; }
                var nm=name||'';
                if(artistId.indexOf('local_artist_')===0){ nm=artistId.substring(13); }
                res(localTracks.filter(function(t){ return t.Type!=='Playlist'&&(t.Artists&&t.Artists[0])===nm; }));
              });
            };
            var retries=0, iv=null;
            function tryInit(){
              try{
                var r=window.wmLibraryPush();
                if(r&&typeof r.then==='function'){ r.then(function(ok){ if(ok&&iv){ clearInterval(iv); iv=null; } }); }
                else if(r&&iv){ clearInterval(iv); iv=null; }
              }catch(e){}
              if(iv&&++retries>40){ clearInterval(iv); iv=null; }
            }
            if(document.readyState==='loading'){ document.addEventListener('DOMContentLoaded',tryInit); }
            else { tryInit(); }
            iv=window.setInterval(tryInit,3000);
            })();
            """;

    /**
     * Panel de "Servidor Walkman" inyectado en la sección de Ajustes de la web
     * (sin modificar walkman-server): permite elegir entre la web oficial o una
     * URL personalizada consultando/guardando a través de AndroidBridge.
     */
    private static final String SERVER_EMBED_SCRIPT = """
            (function(){
            if(window.__wmServerEmbedded)return;
            var menu=document.querySelector('.settings-menu');
            if(!menu)return;
            if(document.getElementById('wm-server-group'))return;
            var g=document.createElement('div');
            g.className='settings-group';
            g.id='wm-server-group';
            g.innerHTML=''
              +'<div class="settings-btn" id="wm-server-btn" style="cursor:pointer;">'
              +'<span class="material-icons" style="color:var(--accent-color);">public</span>'
              +'<span>Servidor Walkman-server</span></div>'
              +'<div class="featured-size-wrap" id="wm-server-wrap" style="display:none;">'
              +'<div class="settings-group-label">Origen de la web</div>'
              +'<div class="accent-options" id="wm-server-options">'
              +'<div class="accent-option" data-mode="official">Walkman oficial</div>'
              +'<div class="accent-option" data-mode="custom">URL personalizada</div>'
              +'</div>'
              +'<div id="wm-server-custom" style="display:none; margin-top:8px;">'
              +'<input id="wm-server-url" type="text" placeholder="https://tu-servidor.com/walkman" '
              +'style="width:100%; box-sizing:border-box; padding:8px 10px; background:rgba(255,255,255,0.05); color:#fff; border:1px solid rgba(255,255,255,0.25); border-radius:5px; font-size:12px; outline:none;"/>'
              +'<div id="wm-server-save" style="margin-top:6px; padding:9px; text-align:center; color:#000; font-size:12px; font-weight:600; border-radius:5px; cursor:pointer; background:var(--accent-color);">Guardar y recargar</div>'
              +'</div>'
              +'</div>';
            var link=menu.querySelector('.settings-link');
            if(link){menu.insertBefore(g,link);}else{menu.appendChild(g);}
            function setActive(mode,url){
              var opts=g.querySelectorAll('#wm-server-options .accent-option');
              opts.forEach(function(o){o.classList.toggle('active',o.dataset.mode===mode);});
              var cust=g.querySelector('#wm-server-custom');
              if(cust){cust.style.display=(mode==='custom')?'':'none';}
              var input=g.querySelector('#wm-server-url');
              if(input&&mode==='custom'){input.value=url||'';}
            }
            var cfg={};
            try{cfg=JSON.parse(AndroidBridge.getServerConfig()||'{}');}catch(e){}
            var curMode=(cfg&&cfg.useCustom)?'custom':'official';
            setActive(curMode,(cfg&&cfg.useCustom)?(cfg.url||''):'');
            g.querySelector('#wm-server-btn').onclick=function(){
              var wrap=g.querySelector('#wm-server-wrap');
              wrap.style.display=(wrap.style.display==='none')?'':'none';
            };
            g.querySelectorAll('#wm-server-options .accent-option').forEach(function(o){
              o.onclick=function(){
                var m=o.dataset.mode;
                setActive(m,'');
                if(m==='official'){AndroidBridge.setServerConfig('off','');}
              };
            });
            g.querySelector('#wm-server-save').onclick=function(){
              var input=g.querySelector('#wm-server-url');
              var url=(input&&input.value||'').trim();
              if(!url)return;
              AndroidBridge.setServerConfig('custom',url);
            };
            window.__wmServerEmbedded=true;
            })();
            """;

    /**
     * Script inyectado para que la web reserve la zona segura del sistema con
     * las barras transparentes (la ventana ocupa toda la pantalla y la barra de
     * gestos se dibuja sobre la propia página).
     *
     * La web usa env(safe-area-inset-*) en su &lt;body&gt; (laterales) y en sus
     * vistas (superior e inferior), así que primero se activa
     * viewport-fit=cover para que el WebView le pase esos valores. Después se
     * compensa únicamente lo que la web no deja libre:
     *
     * - los lados que el WebView no expone por env(),
     * - la barra de estado, cuando el contenido de la web empieza dentro de
     *   ella (su cabecera la ignora en horizontal, donde pone padding: 0), y
     * - el teclado, que la web no puede conocer.
     *
     * Los insets llegan en píxeles físicos y se pasan a CSS con la escala de la
     * página, para que cuadren con lo que mide env().
     */
    private static final String SAFE_AREA_SCRIPT = """
            (function(){
              var ins = [%d, %d, %d, %d, %d];
              var dpr = window.devicePixelRatio || 1;
              var d = document.documentElement;
              d.style.setProperty('--wm-inset-top', (ins[0] / dpr) + 'px');
              d.style.setProperty('--wm-inset-left', (ins[1] / dpr) + 'px');
              d.style.setProperty('--wm-inset-right', (ins[2] / dpr) + 'px');
              d.style.setProperty('--wm-inset-bottom', (ins[3] / dpr) + 'px');
              d.style.setProperty('--wm-ime-bottom', (ins[4] / dpr) + 'px');

              var meta = document.querySelector('meta[name=viewport]');
              if (meta && meta.content.indexOf('viewport-fit') === -1) {
                meta.content += ',viewport-fit=cover';
              }

              // Zona segura que el WebView ya expone a la web con env().
              var probe = document.createElement('div');
              probe.style.cssText = 'position:absolute;top:0;left:0;width:0;height:0;'
                + 'visibility:hidden;padding-top:env(safe-area-inset-top,0px);'
                + 'padding-left:env(safe-area-inset-left,0px);'
                + 'padding-right:env(safe-area-inset-right,0px);'
                + 'padding-bottom:env(safe-area-inset-bottom,0px);';
              d.appendChild(probe);
              var cs = getComputedStyle(probe);
              var nat = {
                top: (parseFloat(cs.paddingTop) || 0) * dpr,
                left: (parseFloat(cs.paddingLeft) || 0) * dpr,
                right: (parseFloat(cs.paddingRight) || 0) * dpr,
                bottom: (parseFloat(cs.paddingBottom) || 0) * dpr
              };
              d.removeChild(probe);

              var css = '';

              // Barra de estado: sólo se compensa lo que la web deja tapado.
              if (ins[0] > 0) {
                var safe = (nat.top > 0 ? nat.top : ins[0]) / dpr;
                var shift = safe - topInsetReserved();
                if (shift > 0) css += 'padding-top:' + shift + 'px;';
              }
              if (nat.left === 0 && ins[1] > 0) {
                css += 'padding-left:' + (ins[1] / dpr) + 'px;';
              }
              if (nat.right === 0 && ins[2] > 0) {
                css += 'padding-right:' + (ins[2] / dpr) + 'px;';
              }
              var bottom = nat.bottom === 0 ? ins[3] : 0;
              if (ins[4] > 0) bottom = Math.max(bottom, ins[4]);
              if (bottom > 0) css += 'padding-bottom:' + (bottom / dpr) + 'px;';

              var s = document.getElementById('wm-safe-area-style');
              if (!s) {
                s = document.createElement('style');
                s.id = 'wm-safe-area-style';
                (d.head || d).appendChild(s);
              }
              s.textContent = css ? 'body{' + css + '}' : '';
            })();

            /* Píxeles CSS que la web ya deja libres arriba. Se leen del padding
               superior de su cabecera: en vertical aplica
               env(safe-area-inset-top) y en horizontal lo anula con padding: 0,
               que es justo lo que hay que compensar. No se mira nunca el propio
               &lt;body&gt; para no depender de lo que inyectó este script. */
            function topInsetReserved() {
              var el = document.querySelector('header');
              if (!el) el = document.body ? document.body.firstElementChild : null;
              if (!el || el === document.body) return 0;
              return parseFloat(getComputedStyle(el).paddingTop) || 0;
            }
            """;

    private WebView mWebView;
    private Handler mHandler;
    private MiniHttpServer mHttpServer;
    private boolean mWebReady;
    private String[] mPendingCommand;

    /**
     * Insets del sistema en píxeles que se pasan a la web por si no puede leer
     * los suyos con env(safe-area-inset-*): superior, izq., der., inferior y
     * teclado (ver {@link #pushSafeAreaToPage()}).
     */
    private static final int INSET_TOP = 0, INSET_LEFT = 1, INSET_RIGHT = 2,
            INSET_BOTTOM = 3, INSET_IME = 4;

    private final int[] mInsets = new int[5];


    // Runner de polling para mantener sincronizados posición, estado y pista
    // del widget con el <audio> real del WebView, incluso cuando la web no
    // notifica cada cambio a través del puente.
    private final Runnable mPositionPoll = new Runnable() {
        @Override
        public void run() {
            if (mWebView != null) {
                mWebView.evaluateJavascript(
                        "(function(){ var a=window.__wmAudio; if(!a){return '{}';} " +
                        "var t=document.getElementById('track-title'); " +
                        "var ar=document.getElementById('track-artist'); " +
                        "var curImg=document.getElementById('img-current'); " +
                        "var cArt=''; try{ if(typeof currentTrackIndex==='number' && playlist && playlist[currentTrackIndex]){" +
                        "cArt=(typeof getEmbyImageUrl==='function')?getEmbyImageUrl(playlist[currentTrackIndex]):'';}}catch(e){} " +
                        "if(!cArt && curImg){ cArt=curImg.src||''; } " +
                        "return JSON.stringify({p:a.currentTime||0, d:a.duration||0, " +
                        "playing:!a.paused, " +
                        "title:t?t.textContent:'', artist:ar?ar.textContent:'', cArt:cArt}); })()",
                        new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String value) {
                                try {
                                    // evaluateJavascript devuelve el resultado como
                                    // literal JSON; desempaquetamos la string interna.
                                    Object inner = new JSONTokener(value).nextValue();
                                    if (!(inner instanceof String)) return;
                                    JSONObject o = new JSONObject((String) inner);
                                    if (o.length() == 0) return;

                                    long pos = (long) (o.optDouble("p", 0) * 1000);
                                    long dur = (long) (o.optDouble("d", 0) * 1000);
                                    boolean playing = o.optBoolean("playing", false);
                                    String title = o.optString("title", "");
                                    String artist = o.optString("artist", "");
                                    String cArt = o.optString("cArt", "");

                                    MediaService svc = MediaService.instance();
                                    if (svc == null) return;

                                    // La web notifica la carátula ANTES de que la
                                    // animación actualice <img-current>, así que a
                                    // veces entrega la URL de la pista anterior.
                                    // Por eso aquí recalculamos la URL correcta con
                                    // getEmbyImageUrl() y re-sincronizamos si cambió
                                    // la pista O difiere de la carátula ya aplicada.
                                    boolean trackChanged = MediaService.isDifferentTrack(svc, title, artist);
                                    boolean artChanged = !cArt.isEmpty()
                                            && !cArt.equals(MediaService.appliedArtUrl());
                                    if (trackChanged || artChanged) {
                                        final String fArt = cArt;
                                        runOnUiThread(() -> {
                                            if (mWebView != null) {
                                                mWebView.evaluateJavascript(
                                                        "try { if (window.AndroidBridge) window.AndroidBridge.setMediaState(JSON.stringify({" +
                                                        "title: document.getElementById('track-title')?document.getElementById('track-title').textContent:''," +
                                                        "artist: document.getElementById('track-artist')?document.getElementById('track-artist').textContent:''," +
                                                        "album:'', artwork:'" + fArt.replace("'", "\\'") + "'," +
                                                        "playing: !window.__wmAudio.paused, position: window.__wmAudio.currentTime||0, duration: window.__wmAudio.duration||0})); } catch(e){}",
                                                        null);
                                            }
                                        });
                                    } else {
                                        MediaService.updatePlayback(svc, playing, pos, dur);
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        });
            }
            mHandler.postDelayed(mPositionPoll, 250);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        makeSystemBarsTransparent();
        setContentView(R.layout.activity_main);
        applySystemBarInsets();

        mHandler = new Handler(Looper.getMainLooper());

        // En Android 13+ hay que pedir permiso para mostrar notificaciones.
        requestNotificationPermission();

        mWebView = findViewById(R.id.webview);
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        setupWebView();

        // Aseguramos que el MediaService exista para que instance() no sea
        // null cuando la web notifique su estado a través de AndroidBridge.
        startMediaService();

        // Servidor HTTP local para servir los archivos de música elegidos en la
        // web (el <audio> de HTML5 sólo reproduce URLs http/https).
        mHttpServer = new MiniHttpServer(getApplicationContext());
        mHttpServer.start();
        AndroidBridge.setLocalFolderListener(this::launchLocalFolderPicker);

        // La web (mediante el panel de servidor inyectado en Ajustes) consulta y
        // cambia la URL de la web (oficial o personalizada).
        AndroidBridge.setServerUrlListener(new AndroidBridge.ServerUrlListener() {
            @Override
            public String onGetConfig() {
                return serverConfigJson();
            }

            @Override
            public void onSetConfig(String mode, String url) {
                final boolean custom = "custom".equals(mode);
                ServerConfig.setCustom(getApplicationContext(), custom,
                        custom ? url : "");
                runOnUiThread(() -> reloadWithConfiguredUrl());
            }
        });

        // Escuchador de comandos del sistema -> JavaScript.
        MediaService.setCommandListener(new MediaService.CommandListener() {
            @Override
            public void onCommand(String command, String argument) {
                runOnUiThread(() -> executeCommand(command, argument));
            }
        });
    }

    /** Inicia el servicio multimedia (crea la sesión y deja listo el puente). */
    private void startMediaService() {
        try {
            Intent intent = new Intent(this, MediaService.class);
            startService(intent);
        } catch (Exception e) {
            // Ignorado: la reproducción web seguirá funcionando sin el widget.
        }
    }

    /**
     * Deja la barra de navegación (o la de gestos) completamente transparente:
     * la ventana ocupa toda la pantalla y el sistema no dibuja ni el color de
     * la barra ni el velo translúcido de contraste que añade por defecto en
     * Android 10+.
     */
    private void makeSystemBarsTransparent() {
        Window w = getWindow();
        WindowCompat.setDecorFitsSystemWindows(w, false);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Sin esto el sistema superpone un fondo gris semitransparente
            // (contraste automático) aunque el color sea transparente.
            w.setStatusBarContrastEnforced(false);
            w.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Antes de Android 10 la barra con botones sólo se vuelve
            // transparente con este flag (deprecated a partir de Q).
            w.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        }
        // Iconos claros: el fondo de la app es oscuro.
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(w, w.getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);
    }

    /**
     * Mide la zona segura del sistema y se la pasa a la web, que es la que
     * reserva el hueco con env(safe-area-inset-*). La ventana no aplica ningún
     * padding para que ni la barra de estado ni la de gestos dejen una banda
     * negra sobre el fondo de la página.
     */
    private void applySystemBarInsets() {
        final View content = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            mInsets[INSET_TOP] = bars.top;
            mInsets[INSET_LEFT] = bars.left;
            mInsets[INSET_RIGHT] = bars.right;
            mInsets[INSET_BOTTOM] = bars.bottom;
            // El teclado no forma parte de la zona segura: en Android 11+ la
            // ventana ya no se reduce al abrirlo, así que lo aportamos nosotros
            // (antes lo reduce el sistema y no hay que compensarlo dos veces).
            mInsets[INSET_IME] = insets.isVisible(WindowInsetsCompat.Type.ime())
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    : 0;
            applyStatusBarScrim(mInsets[INSET_TOP]);
            pushSafeAreaToPage();
            return insets;
        });
    }

    /**
     * Velo semitransparente sobre la franja de la barra de estado. Desde
     * Android 15 el color de la barra del sistema ya no se aplica, así que el
     * tinte se dibuja dentro de la app, por encima de la web y por debajo de
     * los iconos. La barra de gestos se deja totalmente transparente.
     */
    private void applyStatusBarScrim(int topInset) {
        View scrim = findViewById(R.id.status_bar_scrim);
        if (scrim == null) return;
        ViewGroup.LayoutParams lp = scrim.getLayoutParams();
        int height = Math.max(topInset, 0);
        if (lp.height != height) {
            lp.height = height;
            scrim.setLayoutParams(lp);
        }
        scrim.setVisibility(height > 0 ? View.VISIBLE : View.GONE);
    }

    /**
     * Comunica a la web la zona segura para que su cabecera, sus vistas y su
     * fondo respeten las barras del sistema, que ya son transparentes.
     */
    private void pushSafeAreaToPage() {
        if (mWebView == null || !mWebReady) return;
        mWebView.evaluateJavascript(String.format(Locale.US, SAFE_AREA_SCRIPT,
                mInsets[INSET_TOP], mInsets[INSET_LEFT], mInsets[INSET_RIGHT],
                mInsets[INSET_BOTTOM], mInsets[INSET_IME]), null);
    }

    /** Pide permiso de notificaciones en Android 13+ (necesario para el widget). */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = mWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setAllowFileAccess(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

        // Usa un User-Agent para que el servidor sirva la versión móvil/web.
        // (En realidad WALKMAN no depende de ello, pero lo dejamos personalizado
        // para que la app sea identificable y reciba el mismo contenido.)
        String ua = s.getUserAgentString();
        s.setUserAgentString(ua + " WalkmanApp/1.0");

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                view.loadUrl(request.getUrl().toString());
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectBridgeHelpers();
                mWebReady = true;
                pushSafeAreaToPage();
                if (mPendingCommand != null) {
                    String[] p = mPendingCommand;
                    mPendingCommand = null;
                    executeCommand(p[0], p[1]);
                }
                mHandler.postDelayed(mPositionPoll, 500);
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Permitir permisos solicitados por la web sin marcos de diálogo.
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        mWebView.setBackgroundColor(0xFF000000);

        // Inyecta el puente AndroidBridge que la web ya espera.
        mWebView.addJavascriptInterface(new AndroidBridge(getApplicationContext()), "AndroidBridge");

        mWebView.loadUrl(ServerConfig.getUrl(this));
    }

    /**
     * Expone la referencia global al elemento &lt;audio&gt; para el polling de
     * posición. Se llama al terminar de cargar la página.
     */
    private void injectBridgeHelpers() {
        if (mWebView == null) return;
        // Panel "Servidor Walkman" embebido en la sección de Ajustes de la web.
        try {
            mWebView.evaluateJavascript(SERVER_EMBED_SCRIPT, null);
        } catch (Exception ignored) {
        }
        // Script de biblioteca para Android Auto (álbumes, playlists, pistas).
        try {
            mWebView.evaluateJavascript(LIBRARY_SCRIPT, null);
        } catch (Exception ignored) {
        }
        mWebView.evaluateJavascript(
                "try { window.__wmAudio = document.getElementById('audio-player'); " +
                "window.__wmAudio.addEventListener('loadedmetadata', function(){ " +
                "  if (window.AndroidBridge && window.__wmAudio) {" +
                "    try { AndroidBridge.setMediaState(JSON.stringify({" +
                "      title: document.title||'', artist:'', album:'', artwork:''," +
                "      playing: !__wmAudio.paused, position: __wmAudio.currentTime||0, duration: __wmAudio.duration||0}));" +
                "    } catch(e){} } }); } catch(e){}", null);
    }

    /** Traduce una orden del sistema a una llamada JavaScript. */
    private void executeCommand(String command, String arg) {
        if (mWebView == null) return;
        if (!mWebReady) {
            // La web aún no ha terminado de cargar (p. ej. voz en arranque en
            // frío): retenemos la última orden y la ejecutamos en onPageFinished.
            mPendingCommand = new String[]{command, arg};
            return;
        }
        switch (command) {
            case "play":
            case "pause":
                mWebView.evaluateJavascript("try { togglePlay(); } catch(e){}", null);
                break;
            case "next":
                mWebView.evaluateJavascript("try { nextTrack(); } catch(e){}", null);
                break;
            case "prev":
                mWebView.evaluateJavascript("try { prevTrack(); } catch(e){}", null);
                break;
            case "seek":
                if (arg != null) {
                    final String a = "'" + arg.replace("'", "\\'") + "'";
                    mWebView.evaluateJavascript("try { " +
                            "var d = document.getElementById('audio-player'); if (d && d.duration) {" +
                            "  var t = Math.min(Math.max(Number(" + a + ")/1000, 0), d.duration); d.currentTime = t; " +
                            "  if (window.AndroidBridge) { try { AndroidBridge.setMediaState(JSON.stringify({" +
                            "    title: document.getElementById('track-title')?document.getElementById('track-title').textContent:''," +
                            "    artist: document.getElementById('track-artist')?document.getElementById('track-artist').textContent:''," +
                            "    album:'', artwork: (document.getElementById('img-current')&&document.getElementById('img-current').src)||''," +
                            "    playing: !d.paused, position: t, duration: d.duration })); } catch(e){} }" +
                            "} } catch(e){}", null);
                }
                break;
            case "stop":
                mWebView.evaluateJavascript("try { var d=document.getElementById('audio-player'); if(d){d.pause(); d.currentTime=0;} } catch(e){}", null);
                break;
            case "play_all":
                mWebView.evaluateJavascript("try { if(window.playlist&&window.playlist.length){ playTrack(0); } } catch(e){}", null);
                break;
            case "play_album":
                callPlayContainer(arg, "album");
                break;
            case "play_playlist":
                callPlayContainer(arg, "playlist");
                break;
            case "play_artist":
                callPlayArtist(arg);
                break;
            case "play_track":
                callPlayTrack(arg);
                break;
            case "shuffle":
                mWebView.evaluateJavascript("try { var w=" + ("1".equals(arg) ? "true" : "false")
                        + "; if(typeof isShuffle!=='undefined' && (!!isShuffle)!==w){ toggleShuffle(); } } catch(e){}", null);
                break;
            case "play_shuffle":
                mWebView.evaluateJavascript("try { wmPlayShuffle(); } catch(e){}", null);
                break;
            case "refresh_queue":
                mWebView.evaluateJavascript("try { wmQueueFor(); } catch(e){}", null);
                break;
            case "play_queue":
                if (arg != null) {
                    // Reproduce desde la posición indicada en la cola actual.
                    mWebView.evaluateJavascript("try { var i=Number(" + arg + ");" +
                            " if(typeof playlist!=='undefined' && playlist.length && i>=0 && i<playlist.length){ playTrack(i); }" +
                            " try { if(typeof wmQueueFor==='function'){ wmQueueFor(); } }catch(e2){}" +
                            " } catch(e){}", null);
                }
                break;
            case "repeat":
                final int rep;
                try {
                    rep = arg == null ? 0 : Integer.parseInt(arg);
                } catch (NumberFormatException ne) {
                    return;
                }
                mWebView.evaluateJavascript("try { var t=" + rep
                        + "; if(typeof repeatMode!=='undefined'){ var c=0; while(repeatMode!==t && c<3){ toggleRepeat(); c++; } } } catch(e){}", null);
                break;
            case "refresh":
                mWebView.evaluateJavascript("try { window.wmLibraryPush(); } catch(e){}", null);
                break;
            case "reload":
                reloadWithConfiguredUrl();
                break;
            case "select_library":
                if (arg != null) {
                    JSONArray la = new JSONArray();
                    la.put(arg);
                    mWebView.evaluateJavascript("try { wmSelectLibrary.apply(null," + la.toString() + "); } catch(e){}", null);
                }
                break;
            case "refresh_tracks":
                callRefreshTracks(arg);
                break;
            case "refresh_artist_albums":
                callRefreshArtistAlbums(arg);
                break;
            case "play_search":
                if (arg != null) {
                    JSONArray qa = new JSONArray();
                    qa.put(arg);
                    mWebView.evaluateJavascript("try { wmPlaySearch.apply(null," + qa.toString() + "); } catch(e){}", null);
                }
                break;
            case "search":
                if (arg != null) {
                    JSONArray qa2 = new JSONArray();
                    qa2.put(arg);
                    mWebView.evaluateJavascript("try { wmSearch.apply(null," + qa2.toString() + "); } catch(e){}", null);
                }
                break;
            default:
                break;
        }
    }

    /** Lanza wmPlayContainer(contenedor, tipo) para reproducir un álbum/lista. */
    private void callPlayContainer(String containerId, String kind) {
        if (containerId == null || containerId.isEmpty()) return;
        JSONArray args = new JSONArray();
        args.put(containerId);
        args.put(kind);
        args.put("");
        args.put("");
        mWebView.evaluateJavascript("try { wmPlayContainer.apply(null," + args.toString() + "); } catch(e){}", null);
    }

    /** Lanza wmPlayContainer para una pista concreta: arg = "kind:contenedor:pista". */
    private void callPlayTrack(String arg) {
        if (arg == null) return;
        // arg = "<kind>:<contenedor>:<pista>" (contenedor puede contener ':')
        int last = arg.lastIndexOf(':');
        String trackId = last >= 0 ? arg.substring(last + 1) : "";
        String rest = last >= 0 ? arg.substring(0, last) : arg;
        int fc = rest.indexOf(':');
        String kind = fc > 0 ? rest.substring(0, fc) : "album";
        String containerId = fc > 0 ? rest.substring(fc + 1) : rest;
        JSONArray args = new JSONArray();
        args.put(containerId);
        args.put(kind);
        args.put("");
        args.put(trackId);
        mWebView.evaluateJavascript("try { wmPlayContainer.apply(null," + args.toString() + "); } catch(e){}", null);
    }

    /** Lanza wmTracksFor(contenedor, tipo): arg = "album|id" o "playlist|id". */
    private void callRefreshTracks(String arg) {
        if (arg == null) return;
        int bar = arg.indexOf('|');
        if (bar <= 0) return;
        String kind = arg.substring(0, bar);
        String id = arg.substring(bar + 1);
        JSONArray args = new JSONArray();
        args.put(id);
        args.put(kind);
        mWebView.evaluateJavascript("try { wmTracksFor.apply(null," + args.toString() + "); } catch(e){}", null);
    }

    /** Lanza wmPlayArtist(artista, nombre): arg = "artifactId|artistName". */
    private void callPlayArtist(String arg) {
        if (arg == null) return;
        int bar = arg.indexOf('|');
        String artistId = bar >= 0 ? arg.substring(0, bar) : arg;
        String name = bar >= 0 ? arg.substring(bar + 1) : "";
        JSONArray args = new JSONArray();
        args.put(artistId);
        args.put(name);
        mWebView.evaluateJavascript("try { wmPlayArtist.apply(null," + args.toString() + "); } catch(e){}", null);
    }

    /** Lanza wmArtistAlbums(artista, nombre): arg = "artifactId|artistName". */
    private void callRefreshArtistAlbums(String arg) {
        if (arg == null) return;
        int bar = arg.indexOf('|');
        String artistId = bar >= 0 ? arg.substring(0, bar) : arg;
        String name = bar >= 0 ? arg.substring(bar + 1) : "";
        JSONArray args = new JSONArray();
        args.put(artistId);
        args.put(name);
        mWebView.evaluateJavascript("try { wmArtistAlbums.apply(null," + args.toString() + "); } catch(e){}", null);
    }

    /** Lanza el selector de carpeta (SAF) solicitado por la web. */
    private void launchLocalFolderPicker() {
        runOnUiThread(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(i, REQ_FOLDER);
            } catch (Exception e) {
                android.util.Log.e("LocalFolder", "No se pudo abrir el selector", e);
            }
        });
    }

    /** Recopila los archivos de la carpeta elegida y los inyecta a la web. */
    private void handleFolderResult(Uri treeUri) {
        if (mWebView == null || mHttpServer == null) {
            return;
        }
        final WebView wv = mWebView;
        final MiniHttpServer server = mHttpServer;
        // La recopilación con DocumentFile es lenta con muchos archivos;
        // se hace en un hilo de fondo para no congelar la interfaz (ANR).
        new Thread(() -> {
            final List<String> ids = new ArrayList<>();
            final List<Uri> uris = new ArrayList<>();
            try {
                List<JSONObject> tracks = LocalMusicPicker.collectTree(
                        getApplicationContext(), treeUri, ids, uris, server, server.getPort());

                if (tracks.isEmpty()) {
                    runOnUiThread(() -> {
                        try {
                            wv.evaluateJavascript(
                                    "try { if (window.onAndroidLocalError) onAndroidLocalError('No se encontraron archivos de audio.'); } catch(e){}",
                                    null);
                        } catch (Exception ignored) {
                        }
                    });
                    return;
                }

                JSONArray arr = new JSONArray();
                for (JSONObject t : tracks) {
                    arr.put(t);
                }
                final String json = arr.toString();
                runOnUiThread(() -> {
                    try {
                        wv.evaluateJavascript(
                                "try { if (window.onAndroidLocalTracks) onAndroidLocalTracks(" + json + "); } catch(e){}",
                                null);
                    } catch (Exception ignored) {
                    }
                });
            } catch (Throwable t) {
                android.util.Log.e("LocalFolder", "Error recopilando carpeta", t);
            }
        }, "local-picker").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                // Persistimos el acceso al árbol para poder leerlo más adelante
                // (el servidor HTTP lo sirve desde el proceso de esta app).
                try {
                    getContentResolver().takePersistableUriPermission(treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                handleFolderResult(treeUri);
            }
        }
    }

    /** JSON con la configuración actual de servidor para el panel de la web. */
    private String serverConfigJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("useCustom", ServerConfig.isCustom(this));
            o.put("url", ServerConfig.isCustom(this) ? ServerConfig.getCustom(this) : "");
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    /** Recarga la WebView con la URL guardada en {@link ServerConfig}. */
    private void reloadWithConfiguredUrl() {
        if (mWebView == null) return;
        mWebView.loadUrl(ServerConfig.getUrl(this));
    }

    @Override
    public void onBackPressed() {
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mWebView != null) {
            mWebView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWebView != null) {
            mWebView.onResume();
        }
        handleVoiceQuery(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    /**
     * Si el intent llegó desde VoiceSearchActivity (búsqueda por voz del
     * Asistente), reproduce la consulta. Ejecuta solamente una vez por intent.
     */
    private void handleVoiceQuery(Intent in) {
        if (in == null || !in.hasExtra(EXTRA_VOICE_QUERY)) return;
        String q = in.getStringExtra(EXTRA_VOICE_QUERY);
        in.removeExtra(EXTRA_VOICE_QUERY);
        android.util.Log.i("WALKMAN_AA", "handleVoiceQuery q=" + q);
        if (q == null || q.trim().isEmpty()) {
            executeCommand("play", null);
            return;
        }
        executeCommand("play_search", q.trim());
    }

    @Override
    protected void onDestroy() {
        MediaService.setCommandListener(null);
        AndroidBridge.setLocalFolderListener(null);
        AndroidBridge.setServerUrlListener(null);
        mHandler.removeCallbacks(mPositionPoll);
        if (mHttpServer != null) {
            mHttpServer.stop();
            mHttpServer = null;
        }
        if (mWebView != null) {
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroy();
    }
}
