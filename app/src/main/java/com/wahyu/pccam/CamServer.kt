package com.wahyu.pccam

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream

class CamServer(port: Int) : NanoHTTPD(port) {

    private fun json(s: String): Response {
        val r = newFixedLengthResponse(Response.Status.OK, "application/json", s)
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun ok(): Response = newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri == "/") {
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE)
        }
        val parms = session.parms
        if (parms["pin"] != CamService.pin) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "PIN salah")
        }
        val svc = CamService.instance
        return when (uri) {
            "/stream" -> {
                val r = newChunkedResponse(
                    Response.Status.OK,
                    "multipart/x-mixed-replace; boundary=frame",
                    MjpegStream()
                )
                r.addHeader("Cache-Control", "no-cache")
                r
            }
            "/snap" -> {
                val f = CamService.frame
                if (f == null) {
                    newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
                } else {
                    val r = newFixedLengthResponse(
                        Response.Status.OK, "image/jpeg",
                        ByteArrayInputStream(f), f.size.toLong()
                    )
                    r.addHeader("Content-Disposition", "attachment; filename=foto.jpg")
                    r
                }
            }
            "/switch" -> { svc?.switchCamera(); ok() }
            "/rotate" -> { CamService.extraRot = (CamService.extraRot + 90) % 360; ok() }
            "/zoom" -> { svc?.setZoom(parms["v"]?.toFloatOrNull() ?: 1f); ok() }
            "/rec" -> {
                if (parms["on"] == "1") svc?.startRecording() else svc?.stopRecording()
                ok()
            }
            "/status" -> json(svc?.statusJson() ?: "{}")
            "/files" -> json(svc?.filesJson() ?: "[]")
            "/file" -> {
                val f = svc?.fileFor(parms["name"])
                if (f == null || !f.exists()) {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
                } else {
                    val r = newFixedLengthResponse(
                        Response.Status.OK, "video/x-msvideo",
                        FileInputStream(f), f.length()
                    )
                    r.addHeader("Content-Disposition", "attachment; filename=" + f.name)
                    r
                }
            }
            "/delete" -> { svc?.deleteFile(parms["name"]); ok() }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        }
    }

    private class MjpegStream : InputStream() {
        private var buf = ByteArray(0)
        private var pos = 0
        private var lastId = -1L

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= buf.size && !nextFrame()) return -1
            val n = minOf(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, n)
            pos += n
            return n
        }

        private fun nextFrame(): Boolean {
            var f: ByteArray? = null
            synchronized(CamService.lock) {
                while (CamService.running && (CamService.frame == null || CamService.frameId == lastId)) {
                    CamService.lock.wait(1000)
                }
                if (CamService.running) {
                    f = CamService.frame
                    lastId = CamService.frameId
                }
            }
            val data = f ?: return false
            val head = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${data.size}\r\n\r\n"
                .toByteArray()
            val tail = "\r\n".toByteArray()
            buf = head + data + tail
            pos = 0
            return true
        }
    }

    companion object {
        private const val PAGE = """<!DOCTYPE html>
<html lang="id"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>PC Cam - Pengawas</title>
<style>
body{margin:0;background:#111;color:#eee;font-family:system-ui,sans-serif;text-align:center}
.bar{padding:10px;display:flex;gap:8px;justify-content:center;align-items:center;flex-wrap:wrap}
input,button{padding:9px 13px;border-radius:8px;border:0;font-size:15px}
button{background:#2d7ff9;color:#fff;cursor:pointer}
button.red{background:#d33}
#st{padding:6px;font-size:14px}
.ok{color:#6d6}.bad{color:#f66;font-weight:bold}
img{max-width:100%;max-height:74vh;background:#000;cursor:zoom-in}
#fl{max-width:560px;margin:8px auto;text-align:left;font-size:14px}
#fl div{padding:6px;border-bottom:1px solid #333;display:flex;gap:10px;align-items:center}
#fl span{flex:1}
#fl a{color:#6af}
</style></head><body>
<div class="bar">
<input id="pin" type="password" placeholder="PIN" size="8">
<button onclick="startStream()">Mulai</button>
<button onclick="api('/switch')">Ganti kamera</button>
<button onclick="api('/rotate')">Putar 90&deg;</button>
<button onclick="snap()">Foto</button>
<button id="recBtn" onclick="rec()">Rekam</button>
<button onclick="files()">Rekaman</button>
</div>
<div class="bar">Zoom <input id="zoom" type="range" min="1" max="1" step="0.1" value="1" style="width:200px"> <span id="zv">1x</span></div>
<div id="st" class="bad">Belum terhubung</div>
<img id="v" alt="">
<div id="fl"></div>
<script>
var pinEl=document.getElementById('pin'),v=document.getElementById('v'),st=document.getElementById('st');
var zoomEl=document.getElementById('zoom'),zv=document.getElementById('zv'),recBtn=document.getElementById('recBtn');
var recOn=false,lost=false;
pinEl.value=localStorage.getItem('pin')||'';
function q(){return 'pin='+encodeURIComponent(pinEl.value);}
function api(p){return fetch(p+(p.indexOf('?')<0?'?':'&')+q());}
function reload(){v.src='/stream?'+q()+'&t='+Date.now();}
function startStream(){localStorage.setItem('pin',pinEl.value);reload();poll();}
v.onerror=function(){setTimeout(function(){if(pinEl.value)reload();},2000);};
v.onclick=function(){if(v.requestFullscreen)v.requestFullscreen();};
function two(n){return (n<10?'0':'')+n;}
function fmtT(s){return two(Math.floor(s/3600))+':'+two(Math.floor(s/60)%60)+':'+two(s%60);}
function poll(){
  if(!pinEl.value)return;
  api('/status').then(function(r){if(!r.ok)throw 0;return r.json();}).then(function(s){
    if(lost){lost=false;reload();}
    recOn=s.rec;
    st.className='ok';
    st.textContent='Terhubung | Baterai '+(s.battery>=0?s.battery+'%':'?')+(s.charging?' (mengisi)':'')
      +' | Kamera '+(s.back?'belakang':'depan')+' | Sisa ruang '+(Math.round(s.freeMb/102.4)/10)+' GB'
      +(s.rec?' | REC '+fmtT(s.recSec):'');
    recBtn.textContent=s.rec?'Stop rekam':'Rekam';
    recBtn.className=s.rec?'red':'';
    zoomEl.max=s.maxZoom;
    if(document.activeElement!==zoomEl){zoomEl.value=s.zoom;zv.textContent=(Math.round(s.zoom*10)/10)+'x';}
  }).catch(function(){
    lost=true;st.className='bad';st.textContent='TERPUTUS dari HP - mencoba menyambung ulang...';
  });
}
zoomEl.oninput=function(){zv.textContent=zoomEl.value+'x';};
zoomEl.onchange=function(){api('/zoom?v='+zoomEl.value);};
function rec(){api('/rec?on='+(recOn?0:1)).then(poll);}
function snap(){
  var a=document.createElement('a');
  a.href='/snap?'+q();a.download='foto.jpg';
  document.body.appendChild(a);a.click();a.remove();
}
function del(n){
  if(confirm('Hapus '+n+' dari HP?'))api('/delete?name='+encodeURIComponent(n)).then(files);
}
function files(){
  api('/files').then(function(r){return r.json();}).then(function(list){
    var box=document.getElementById('fl');box.innerHTML='';
    if(!list.length){box.textContent='Belum ada rekaman.';return;}
    list.forEach(function(f){
      var d=document.createElement('div');
      var s=document.createElement('span');s.textContent=f.name+' ('+f.mb+' MB)';
      var a=document.createElement('a');a.textContent='Unduh';
      a.href='/file?name='+encodeURIComponent(f.name)+'&'+q();
      var b=document.createElement('button');b.textContent='Hapus';b.className='red';
      b.onclick=function(){del(f.name);};
      d.appendChild(s);d.appendChild(a);d.appendChild(b);box.appendChild(d);
    });
  });
}
setInterval(poll,3000);
if(pinEl.value)startStream();
</script></body></html>"""
    }
}
