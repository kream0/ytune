"""Throwaway: when is each word of a song actually sung? (to tune the karaoke sweep)

For a few songs, takes Deezer's and iTunes' 30-second previews of the version the LRCLIB
lyrics were timed on (same length), transcribes them with word timestamps, and prints the
lyrics and the timed words as JSON lines; the previews are placed in the song offline.
"""
import json, subprocess, sys, traceback, urllib.parse, urllib.request
import numpy as np
from faster_whisper import WhisperModel

SONGS = [  # name, artist, title, LRCLIB id ("-": the synced one closest to Deezer's length)
    ("power", "Maher Zain", "The Power", "6076698"),
    ("assalamu-ar", "Maher Zain", "Assalamu Alayka (Arabic Version)", "4108748"),
    ("rahmatun", "Maher Zain", "Rahmatun Lil'Alameen", "2262383"),
    ("insha-allah", "Maher Zain", "Insha Allah", "-"),
    ("ya-nabi", "Maher Zain", "Ya Nabi Salam Alayka", "-"),
    ("barakallah", "Maher Zain", "Barakallah", "-"),
    ("ramadan", "Maher Zain", "Ramadan", "-"),
    ("hasbi-rabbi", "Sami Yusuf", "Hasbi Rabbi", "-"),
    ("someone", "Adele", "Someone Like You", "-"),
    ("perfect", "Ed Sheeran", "Perfect", "-"),
    ("blinding", "The Weeknd", "Blinding Lights", "-"),
    ("yellow", "Coldplay", "Yellow", "-"),
]
UA = {"User-Agent": "YTune probe (https://github.com/kream0/ytune)"}

def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
        return json.load(r)

def fetch(url, path):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r, open(path, "wb") as f:
        f.write(r.read())
    return path

model = WhisperModel("small", device="cpu", compute_type="int8")
for name, artist, title, lrc_id in SONGS:
    try:
        dz = get("https://api.deezer.com/search?" + urllib.parse.urlencode({"q": f"{artist} {title}"})).get("data", [])
        print("deezer:", [(t.get("title"), t.get("duration")) for t in dz[:5]], flush=True)
        it = get("https://itunes.apple.com/search?" + urllib.parse.urlencode({"term": f"{artist} {title}", "entity": "song", "limit": 15}))["results"]
        if lrc_id != "-":
            lrc = get(f"https://lrclib.net/api/get/{lrc_id}")
        else:
            q = urllib.parse.urlencode({"track_name": title, "artist_name": artist})
            found = [r for r in get(f"https://lrclib.net/api/search?{q}") if r.get("syncedLyrics")]
            ref = dz[0]["duration"] if dz else it[0]["trackTimeMillis"] / 1000 if it else found[0]["duration"]
            lrc = min(found, key=lambda r: abs(r["duration"] - ref))
        dur = lrc["duration"]
        print(json.dumps({"song": name, "lrclib": lrc["id"], "lrcDuration": dur}, ensure_ascii=False), flush=True)
        print("LRC " + json.dumps(lrc["syncedLyrics"], ensure_ascii=False), flush=True)
        clips = []
        for i, t in enumerate([t for t in dz if t.get("preview") and abs(t["duration"] - dur) <= 3][:1]):
            clips.append((f"deezer:{t['id']}", fetch(t["preview"], f"{name}-dz{i}.mp3")))
        for i, t in enumerate([t for t in it if t.get("previewUrl") and abs(t.get("trackTimeMillis", 0) / 1000 - dur) <= 3][:2]):
            clips.append((f"itunes:{t['trackId']}", fetch(t["previewUrl"], f"{name}-it{i}.m4a")))
        for source, path in clips:
            print("CLIP " + json.dumps({"source": source, "full": False}), flush=True)
            pcm = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", "16000", "-"],
                                 capture_output=True, check=True).stdout
            segments, _ = model.transcribe(np.frombuffer(pcm, np.float32), word_timestamps=True, multilingual=True,
                                           condition_on_previous_text=False, beam_size=5)
            for s in segments:
                for w in s.words or []:
                    print("W " + json.dumps([round(w.start, 3), round(w.end, 3), w.word.strip(), round(w.probability, 2)],
                                            ensure_ascii=False), flush=True)
    except Exception:
        print(f"FAILED {name}: " + traceback.format_exc().replace("\n", " | "), flush=True)
# Long enough that the log tool hands the whole log over as a file.
print("PAD " + "." * 150_000, flush=True)
