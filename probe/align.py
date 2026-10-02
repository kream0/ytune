"""Throwaway: when is each word of a song actually sung? (to tune the karaoke sweep)

Downloads the song's audio from YouTube (the version whose length matches the LRCLIB lyrics),
transcribes it with word timestamps, and prints the LRCLIB lines and the timed words as JSON
lines, for offline comparison with the app's highlighting models.
"""
import json, subprocess, sys, urllib.parse, urllib.request

name, query, lrc_id, artist, title = sys.argv[1:6]
UA = {"User-Agent": "YTune probe (https://github.com/kream0/ytune)"}

def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
        return json.load(r)

def entries(q):
    out = subprocess.run(["yt-dlp", "--flat-playlist", "-J", f"ytsearch8:{q}"],
                         capture_output=True, text=True)
    if out.returncode != 0:
        print("yt-dlp search failed:", out.stderr[-2000:], flush=True)
        return []
    return [e for e in json.loads(out.stdout)["entries"] if e.get("duration")]

videos = entries(query)
print("videos:", [(e["id"], e.get("title"), e["duration"]) for e in videos], flush=True)

if lrc_id != "-":
    lrc = get(f"https://lrclib.net/api/get/{lrc_id}")
else:
    q = urllib.parse.urlencode({"track_name": title, "artist_name": artist})
    found = [r for r in get(f"https://lrclib.net/api/search?{q}") if r.get("syncedLyrics")]
    best = None
    for r in found:
        for v in videos:
            d = abs(r["duration"] - v["duration"])
            if best is None or d < best[0]:
                best = (d, r)
    lrc = best[1]
video = min(videos, key=lambda v: abs(v["duration"] - lrc["duration"]))
print(json.dumps({"song": name, "lrclib": lrc["id"], "lrcDuration": lrc["duration"],
                  "video": video["id"], "videoTitle": video.get("title"),
                  "videoDuration": video["duration"]}, ensure_ascii=False), flush=True)
print("LRC " + json.dumps(lrc["syncedLyrics"], ensure_ascii=False), flush=True)

dl = None
for client in [None, "web_safari", "tv", "mweb", "ios", "android"]:
    cmd = ["yt-dlp", "-f", "bestaudio", "-x", "--audio-format", "wav",
           "--postprocessor-args", "ffmpeg:-ar 16000 -ac 1", "-o", "song.%(ext)s",
           f"https://www.youtube.com/watch?v={video['id']}"]
    if client:
        cmd[1:1] = ["--extractor-args", f"youtube:player_client={client}"]
    dl = subprocess.run(cmd, capture_output=True, text=True)
    print(f"download ({client or 'default'}):", dl.returncode, dl.stderr[-600:], flush=True)
    if dl.returncode == 0:
        break
if dl.returncode != 0:
    sys.exit(1)

from faster_whisper import WhisperModel
model = WhisperModel("small", device="cpu", compute_type="int8")
segments, info = model.transcribe("song.wav", word_timestamps=True, multilingual=True,
                                  condition_on_previous_text=False, beam_size=5)
for s in segments:
    print("SEG " + json.dumps({"s": round(s.start, 2), "e": round(s.end, 2), "t": s.text}, ensure_ascii=False), flush=True)
    for w in s.words or []:
        print("W " + json.dumps([round(w.start, 3), round(w.end, 3), w.word.strip(), round(w.probability, 2)],
                                ensure_ascii=False), flush=True)
