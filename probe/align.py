"""Throwaway: when is each word of a song actually sung? (to tune the karaoke sweep)

Gets the song's audio (the full track from YouTube when the runner is allowed to, otherwise
Deezer's and iTunes' 30-second previews of the same version), transcribes it with word
timestamps, and prints the LRCLIB lines and the timed words as JSON lines, for offline
comparison with the app's highlighting models (previews are placed in the song offline).
"""
import json, subprocess, sys, urllib.parse, urllib.request

name, artist, title, lrc_id = sys.argv[1:5]
UA = {"User-Agent": "YTune probe (https://github.com/kream0/ytune)"}

def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
        return json.load(r)

def fetch(url, path):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r, open(path, "wb") as f:
        f.write(r.read())

def to_wav(src, dst):
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", src, "-ar", "16000", "-ac", "1", dst], check=True)

if lrc_id != "-":
    lrc = get(f"https://lrclib.net/api/get/{lrc_id}")
else:
    q = urllib.parse.urlencode({"track_name": title, "artist_name": artist})
    found = [r for r in get(f"https://lrclib.net/api/search?{q}") if r.get("syncedLyrics")]
    dz = get("https://api.deezer.com/search?" + urllib.parse.urlencode({"q": f'artist:"{artist}" track:"{title}"'}))["data"]
    ref = dz[0]["duration"] if dz else found[0]["duration"]
    lrc = min(found, key=lambda r: abs(r["duration"] - ref))
dur = lrc["duration"]
print(json.dumps({"song": name, "lrclib": lrc["id"], "lrcDuration": dur}, ensure_ascii=False), flush=True)
print("LRC " + json.dumps(lrc["syncedLyrics"], ensure_ascii=False), flush=True)

clips = []  # (source, wav, full)
# 1. The full track from YouTube (often refused to CI runners: "confirm you're not a bot").
out = subprocess.run(["yt-dlp", "--flat-playlist", "-J", f"ytsearch8:{artist} {title}"], capture_output=True, text=True)
if out.returncode == 0:
    vids = [e for e in json.loads(out.stdout)["entries"] if e.get("duration")]
    vids = [v for v in vids if abs(v["duration"] - dur) <= 2]
    if vids:
        dl = subprocess.run(["yt-dlp", "-f", "bestaudio", "-o", "yt.%(ext)s", "--print", "after_move:filepath",
                             f"https://www.youtube.com/watch?v={vids[0]['id']}"], capture_output=True, text=True)
        print("youtube:", vids[0]["id"], dl.returncode, dl.stderr[-300:].strip(), flush=True)
        if dl.returncode == 0:
            to_wav(dl.stdout.strip().splitlines()[-1], "yt.wav")
            clips.append(("youtube", "yt.wav", True))
# 2. 30-second previews of the same version (same length as the lyrics).
if not clips:
    try:
        dz = get("https://api.deezer.com/search?" + urllib.parse.urlencode({"q": f'artist:"{artist}" track:"{title}"'}))["data"]
        for i, t in enumerate([t for t in dz if t.get("preview") and abs(t["duration"] - dur) <= 3][:2]):
            fetch(t["preview"], f"dz{i}.mp3"); to_wav(f"dz{i}.mp3", f"dz{i}.wav")
            clips.append((f"deezer:{t['id']}", f"dz{i}.wav", False))
    except Exception as e:
        print("deezer:", e, flush=True)
    try:
        it = get("https://itunes.apple.com/search?" + urllib.parse.urlencode({"term": f"{artist} {title}", "entity": "song", "limit": 10}))["results"]
        for i, t in enumerate([t for t in it if t.get("previewUrl") and abs(t.get("trackTimeMillis", 0) / 1000 - dur) <= 3][:2]):
            fetch(t["previewUrl"], f"it{i}.m4a"); to_wav(f"it{i}.m4a", f"it{i}.wav")
            clips.append((f"itunes:{t['trackId']}", f"it{i}.wav", False))
    except Exception as e:
        print("itunes:", e, flush=True)
print("clips:", clips, flush=True)

from faster_whisper import WhisperModel
model = WhisperModel("small", device="cpu", compute_type="int8")
for source, wav, full in clips:
    print("CLIP " + json.dumps({"source": source, "full": full}), flush=True)
    segments, info = model.transcribe(wav, word_timestamps=True, multilingual=True,
                                      condition_on_previous_text=False, beam_size=5)
    for s in segments:
        print("SEG " + json.dumps({"s": round(s.start, 2), "e": round(s.end, 2), "t": s.text}, ensure_ascii=False), flush=True)
        for w in s.words or []:
            print("W " + json.dumps([round(w.start, 3), round(w.end, 3), w.word.strip(), round(w.probability, 2)],
                                    ensure_ascii=False), flush=True)
